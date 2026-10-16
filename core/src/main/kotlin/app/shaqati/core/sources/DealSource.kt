package app.shaqati.core.sources

import app.shaqati.core.model.Audience
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.text.TextSignals
import app.shaqati.core.util.Geo
import app.shaqati.core.util.HttpRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Deal (dealapp.sa) through its public app API with a guest session.
 * Guests may read the first [GUEST_PAGES] pages (10 ads each) of a query, and the API
 * rate-limits and blocks fast clients, so each refresh reads only the newest ads at a
 * gentle pace and stops at the first refusal.
 */
class DealSource(private val pace: Long = 2_500) : ListingSource {
    override val source = Source.DEAL

    @Volatile
    private var token: String? = null

    /** District ids seen in Deal's own results (Arabic name → id), added to [KNOWN_DISTRICTS]. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun guest(ctx: SearchContext): String? {
        token?.let { return it }
        val resp = ctx.http.send(
            HttpRequest(
                url = "$API/user/skip",
                method = "POST",
                headers = mapOf("Origin" to "https://dealapp.sa", "Accept-Language" to "ar"),
                contentType = "application/json",
                body = """{"os":"Android","name":"Android","devicePlatform":"web"}""",
            )
        )
        val t = resp.headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
        token = t?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("Bearer")) it else "Bearer $it" }
        return token
    }

    private fun headers(t: String) = mapOf("Authorization" to t, "Origin" to "https://dealapp.sa", "Accept-Language" to "ar")

    override suspend fun search(ctx: SearchContext): SearchResult {
        // A fresh guest session per refresh, like opening the site again.
        token = null
        val t = runCatching { guest(ctx) }.getOrNull()
            ?: return SearchResult(source, emptyList(), 0, listOf("guest session failed"), blocked = true)
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        var refused = false
        types@ for (type in PROPERTY_TYPES) {
            for (page in 1..GUEST_PAGES) {
                if (pages > 0) delay(pace)
                val url = "$API/ad?page=$page&limit=10&city=$RIYADH&purpose=RENT&propertyType=$type&maxPrice=${ctx.criteria.maxAnnualRent.toLong()}"
                val resp = runCatching { ctx.http.send(HttpRequest(url, headers = headers(t))) }
                    .getOrElse { errors += it.message ?: "error"; null } ?: break
                pages++
                if (resp.status == 401 || resp.status == 403 || resp.status == 429) {
                    errors += "HTTP ${resp.status}"
                    refused = true
                    break@types
                }
                if (!resp.ok) { errors += "HTTP ${resp.status}"; break }
                val root = runCatching { lenientJson.parseToJsonElement(resp.body) }.getOrNull() ?: break
                collect(root, ctx, out)
                if (root["hasNextPage"].bool != true) break
            }
        }
        // Then the first page of each nearby district Deal has an id for (apartments only).
        if (!refused) {
            val ids = Districts.near(ctx.area.center, ctx.area.radiusKm)
                .mapNotNull { d -> learned[d.ar] ?: KNOWN_DISTRICTS[d.ar] }
                .distinct()
                .take(MAX_DISTRICT_QUERIES)
            for (id in ids) {
                delay(pace)
                val url = "$API/ad?page=1&limit=10&city=$RIYADH&district=$id&purpose=RENT&propertyType=${PROPERTY_TYPES[0]}&maxPrice=${ctx.criteria.maxAnnualRent.toLong()}"
                val resp = runCatching { ctx.http.send(HttpRequest(url, headers = headers(t))) }.getOrNull() ?: break
                pages++
                if (resp.status == 401 || resp.status == 403 || resp.status == 429) { errors += "HTTP ${resp.status}"; break }
                if (!resp.ok) continue
                runCatching { lenientJson.parseToJsonElement(resp.body) }.getOrNull()?.let { collect(it, ctx, out) }
            }
        }
        token = null
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5), blocked = refused && out.isEmpty())
    }

    private fun collect(root: kotlinx.serialization.json.JsonElement, ctx: SearchContext, out: MutableMap<String, Listing>) {
        root["data"].arr.forEach { d ->
            val id = d["district"]["_id"].str
            val name = d["district"]["name_ar"].str
            if (id != null && name != null) learned[name] = id
        }
        root["data"].arr.mapNotNull { runCatching { toListing(it) }.getOrNull() }
            .filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
            .forEach { out[it.id] = it }
    }

    override suspend fun details(listing: Listing, ctx: SearchContext): Listing {
        val t = guest(ctx) ?: return listing
        delay(pace)
        val resp = ctx.http.send(HttpRequest("$API/ad/${listing.sourceId}", headers = headers(t)))
        if (resp.status == 401 || resp.status == 403) token = null
        if (!resp.ok) return listing
        val d = lenientJson.parseToJsonElement(resp.body)["data"] ?: return listing
        return toListing(d)?.let { full -> full.copy(title = full.title.ifBlank { listing.title }) } ?: listing
    }

    companion object {
        const val API = "https://api.dealapp.sa/production"
        const val RIYADH = "6009d941950ada00061eeeab"

        const val GUEST_PAGES = 4
        private const val MAX_DISTRICT_QUERIES = 8

        /** Deal district ids for Riyadh districts around Al Masif, recorded from its own results. */
        val KNOWN_DISTRICTS = mapOf(
            "النفل" to "5dd490aebc2e91004242e4a2",
            "الربيع" to "5dd490aebc2e91004242e4b7",
            "الصحافة" to "5dd490aebc2e91004242e4ca",
            "الشهداء" to "5dd490aebc2e91004242e45f",
            "حطين" to "5dd490aebc2e91004242e4de",
            "القدس" to "5dd490aebc2e91004242e4a0",
            "الياسمين" to "5dd490aebc2e91004242e4e1",
            "غرناطة" to "5dd490aebc2e91004242e4e8",
            "الملقا" to "5dd490aebc2e91004242e4df",
            "الروضة" to "5dd490aebc2e91004242e4a7",
            "الملك فيصل" to "5dd490aebc2e91004242e46a",
            "النرجس" to "5dd490aebc2e91004242e4e4",
            "اليرموك" to "5dd490aebc2e91004242e4d9",
            "المونسية" to "5ec2d42f40b9ea98a7cae7bf",
        )
        const val FLOOR_TYPE = "63091bec45e4bacf002d5d2d"

        /** Apartment, apartment in a residential compound, and floor (دور). */
        val PROPERTY_TYPES = listOf("63091bcd45e4ba93412d5d25", "63091cd645e4ba2cf22d5d64", FLOOR_TYPE)

        internal fun toListing(o: JsonElement): Listing? {
            val id = o["_id"].str ?: o["id"].str ?: return null
            if (o["purpose"].str?.uppercase() != "RENT") return null
            val coords = o["location"]["value"]["coordinates"].arr
            val point = runCatching { GeoPoint(coords[1].dbl!!, coords[0].dbl!!) }.getOrNull()?.takeIf { Geo.inRiyadh(it) }
            val rega = o["regaRawData"]
            val media = o["media"]
            val photos = buildList {
                val main = media["main"]
                when (main["type"].str) {
                    "IMAGE" -> main["url"].str?.let(::add)
                    else -> main["thumbnail"].str?.let(::add)
                }
                media["extra"].arr.forEach { m ->
                    if (m["type"].str == "IMAGE") m["url"].str?.let(::add) else m["thumbnail"].str?.let(::add)
                }
            }.distinct()
            val videos = buildList {
                (listOf(media["main"]) + media["extra"].arr).forEach { m -> if (m["type"].str == "VIDEO") (m["preview"].str ?: m["url"].str)?.let(::add) }
            }
            val accommodation = o["accommodationType"].arr.mapNotNull { it.str }
            val code = o["code"].lng
            return Listing(
                source = Source.DEAL,
                sourceId = id,
                url = if (code != null) "https://dealapp.sa/ar/ad-details/$code" else "https://dealapp.sa/ar",
                title = o["title"].str ?: "",
                description = o["description"].str ?: "",
                kind = if (o["propertyType"]["_id"].str == FLOOR_TYPE) PropertyKind.FLOOR else PropertyKind.APARTMENT,
                price = o["price"].dbl?.takeIf { it > 0 },
                period = when (o["rentType"].str?.uppercase()) {
                    "ANNUALLY", "YEARLY" -> RentPeriod.YEARLY
                    "SEMI_ANNUALLY" -> RentPeriod.SEMI_ANNUAL
                    "MONTHLY" -> RentPeriod.MONTHLY
                    "DAILY" -> RentPeriod.DAILY
                    else -> RentPeriod.UNKNOWN
                },
                bedrooms = TextSignals.analyze(o["title"].str, o["description"].str).bedrooms
                    ?: rega["numberOfRooms"].int?.let { RegaRooms.toBedrooms(it) },
                areaSqm = o["area"].dbl ?: rega["propertyArea"].dbl,
                audience = when {
                    "FAMILY" in accommodation && "SINGLES" in accommodation -> Audience.ANY
                    "FAMILY" in accommodation -> Audience.FAMILIES
                    "SINGLES" in accommodation -> Audience.SINGLES
                    else -> Audience.UNKNOWN
                },
                buildingAgeYears = rega["propertyAge"].str?.let { if (it.contains("جديد")) 0 else Regex("\\d+").find(it)?.value?.toIntOrNull() },
                city = o["city"]["name_ar"].str,
                district = o["district"]["name_ar"].str,
                location = point,
                locationPrecision = when {
                    point == null -> LocationPrecision.UNKNOWN
                    o["location"]["type"].str == "PRECISE" -> LocationPrecision.EXACT
                    else -> LocationPrecision.APPROXIMATE
                },
                photos = photos,
                videos = videos,
                advertiserName = o["createdBy"]["entity"]["name"].str ?: o["createdBy"]["name"].str,
                phone = rega["responsibleEmployeePhoneNumber"].str?.takeIf { it.any(Char::isDigit) },
                licenseNumber = o["adLicenseNumber"].str ?: rega["adLicenseNumber"].str,
                postedAt = o["createdAt"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                updatedAt = (o["refreshed"]["at"].str ?: o["updatedAt"].str)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
    }
}
