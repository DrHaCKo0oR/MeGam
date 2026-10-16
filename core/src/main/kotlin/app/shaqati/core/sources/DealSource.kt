package app.shaqati.core.sources

import app.shaqati.core.model.Audience
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.util.Geo
import app.shaqati.core.util.HttpRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Deal (dealapp.sa) through its public app API with a guest session.
 * The API returns at most 10 ads per page and rate-limits, so requests are paced.
 */
class DealSource(private val pace: Long = 900) : ListingSource {
    override val source = Source.DEAL

    @Volatile
    private var token: String? = null

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
        token = t?.let { if (it.startsWith("Bearer")) it else "Bearer $it" }
        return token
    }

    private fun headers(t: String) = mapOf("Authorization" to t, "Origin" to "https://dealapp.sa", "Accept-Language" to "ar")

    override suspend fun search(ctx: SearchContext): SearchResult {
        val t = runCatching { guest(ctx) }.getOrNull()
            ?: return SearchResult(source, emptyList(), 0, listOf("guest session failed"), blocked = true)
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        for (type in PROPERTY_TYPES) {
            for (page in 1..ctx.maxPages * 3) {
                val url = "$API/ad?page=$page&limit=10&city=$RIYADH&purpose=RENT&propertyType=$type&maxPrice=${ctx.criteria.maxAnnualRent.toLong()}"
                val resp = runCatching { ctx.http.send(HttpRequest(url, headers = headers(t))) }
                    .getOrElse { errors += it.message ?: "error"; null } ?: break
                pages++
                if (resp.status == 401) { token = null; errors += "session expired"; break }
                if (resp.status == 429) { errors += "rate limited"; break }
                if (!resp.ok) { errors += "HTTP ${resp.status}"; break }
                val root = runCatching { lenientJson.parseToJsonElement(resp.body) }.getOrNull() ?: break
                val items = root["data"].arr.mapNotNull { runCatching { toListing(it) }.getOrNull() }
                items.filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
                    .forEach { out[it.id] = it }
                if (root["hasNextPage"].bool != true) break
                delay(pace)
            }
        }
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5))
    }

    override suspend fun details(listing: Listing, ctx: SearchContext): Listing {
        val t = guest(ctx) ?: return listing
        val resp = ctx.http.send(HttpRequest("$API/ad/${listing.sourceId}", headers = headers(t)))
        if (!resp.ok) return listing
        val d = lenientJson.parseToJsonElement(resp.body)["data"] ?: return listing
        return toListing(d)?.let { full -> full.copy(title = full.title.ifBlank { listing.title }) } ?: listing
    }

    companion object {
        const val API = "https://api.dealapp.sa/production"
        const val RIYADH = "6009d941950ada00061eeeab"

        /** Apartment and apartment-in-compound. */
        val PROPERTY_TYPES = listOf("63091bcd45e4ba93412d5d25", "63091cd645e4ba2cf22d5d64")

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
                kind = PropertyKind.APARTMENT,
                price = o["price"].dbl?.takeIf { it > 0 },
                period = when (o["rentType"].str?.uppercase()) {
                    "ANNUALLY", "YEARLY" -> RentPeriod.YEARLY
                    "SEMI_ANNUALLY" -> RentPeriod.SEMI_ANNUAL
                    "MONTHLY" -> RentPeriod.MONTHLY
                    "DAILY" -> RentPeriod.DAILY
                    else -> RentPeriod.UNKNOWN
                },
                bedrooms = rega["numberOfRooms"].int?.takeIf { it in 1..10 },
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
