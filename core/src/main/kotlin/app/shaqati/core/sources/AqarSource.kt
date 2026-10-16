package app.shaqati.core.sources

import app.shaqati.core.model.Audience
import app.shaqati.core.model.Feature
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.util.HttpRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aqar (sa.aqar.fm) through the search its own website uses: one query returns
 * every rental apartment inside a polygon around work, with all ad fields
 * (furnishing, family/singles, building age, REGA licence) in the result.
 */
class AqarSource : ListingSource {
    override val source = Source.AQAR

    override suspend fun search(ctx: SearchContext): SearchResult {
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        val polygon = circle(ctx.area.center, ctx.area.radiusKm)
        // Category 1: apartments for rent; 4: floors for rent.
        for (category in listOf(1, 4)) {
            var from = 0
            for (page in 1..ctx.maxPages * 2) {
                val body = query(category, ctx.criteria.maxAnnualRent, polygon, from, PAGE_SIZE)
                val resp = runCatching {
                    ctx.http.send(
                        HttpRequest(
                            url = "$BASE/graphql",
                            method = "POST",
                            headers = mapOf("Origin" to BASE, "Referer" to "$BASE/"),
                            contentType = "application/json",
                            body = body,
                            originPage = "$BASE/",
                        )
                    )
                }.getOrElse { errors += it.message ?: "error"; null } ?: break
                pages++
                if (resp.status == 403 || resp.status == 429) {
                    return SearchResult(source, out.values.toList(), pages, errors + "HTTP ${resp.status}", blocked = true)
                }
                if (!resp.ok) { errors += "HTTP ${resp.status}"; break }
                val (items, total) = parseSearch(resp.body)
                items.forEach { out[it.id] = it }
                from += PAGE_SIZE
                if (items.size < PAGE_SIZE || from >= total) break
            }
        }
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5))
    }

    companion object {
        const val BASE = "https://sa.aqar.fm"
        private const val PAGE_SIZE = 100
        private const val IMG = "https://images.aqar.fm/webp/750x0/props/"

        private const val FIELDS = "id price rent_period price_12_payments beds livings wc area category title path content imgs " +
            "location{lat lng} district city create_time last_update published_at furnished family age ketchen fl lift ac " +
            "car_entrance private_roof apartment_in_villa special_entrance two_entrances ad_license_number user{name company_name}"

        private const val QUERY = "query findListings(\$size:Int \$from:Int \$where:WhereInput \$polygon:[LocationInput!] \$sort:SortInput)" +
            "{Web{find(size:\$size from:\$from where:\$where polygon:\$polygon sort:\$sort){total listings{$FIELDS}}}}"

        /** A 16-sided polygon approximating a circle around [c]. */
        fun circle(c: GeoPoint, radiusKm: Double): List<GeoPoint> {
            val dLat = radiusKm / 111.0
            val dLng = radiusKm / (111.0 * cos(Math.toRadians(c.lat)))
            return (0..16).map { i ->
                val a = 2 * Math.PI * (i % 16) / 16
                GeoPoint(c.lat + dLat * cos(a), c.lng + dLng * sin(a))
            }
        }

        fun query(category: Int, maxAnnualRent: Double, polygon: List<GeoPoint>, from: Int, size: Int): String = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") {
                put("size", size)
                put("from", from)
                putJsonObject("where") {
                    putJsonObject("category") { put("eq", category) }
                    putJsonObject("price") { put("lte", maxAnnualRent) }
                    putJsonObject("beds") { put("gte", 2) }
                }
                put("polygon", buildJsonArray {
                    polygon.forEach { p -> add(buildJsonObject { put("lat", p.lat); put("lng", p.lng) }) }
                })
                putJsonObject("sort") { put("last_update", "desc") }
            }
        }.toString()

        /** Listings and the total count from a search response. */
        fun parseSearch(body: String): Pair<List<Listing>, Int> {
            val root = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList<Listing>() to 0
            val find = root["data"]["Web"]["find"]
            val listings = find["listings"].arr.mapNotNull { runCatching { toListing(it) }.getOrNull() }
            return listings to (find["total"].int ?: listings.size)
        }

        /** Percent-encodes the Arabic path while keeping separators. */
        fun encode(url: String): String {
            val i = url.indexOf("/", "https://".length)
            if (i < 0) return url
            val encoded = url.substring(i).split('/').joinToString("/") { seg ->
                if (seg.all { it.code < 128 }) seg else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            return url.substring(0, i) + encoded
        }

        internal fun toListing(o: JsonElement): Listing? {
            val id = o["id"].lng ?: return null
            val price = o["price"].dbl
            val p12 = o["price_12_payments"].dbl
            var period = when (o["rent_period"].int) {
                3 -> RentPeriod.YEARLY
                2 -> RentPeriod.MONTHLY
                1 -> RentPeriod.DAILY
                else -> RentPeriod.UNKNOWN
            }
            // A 12-payment total of about 12× the price means the price is monthly.
            if (price != null && price > 0 && p12 != null && p12 > 0 && kotlin.math.abs(p12 - price * 12) < price * 2) period = RentPeriod.MONTHLY
            val loc = o["location"]
            val point = runCatching { GeoPoint(loc["lat"].dbl!!, loc["lng"].dbl!!) }.getOrNull()
            val user = o["user"]
            val features = mutableSetOf<String>()
            if (o["lift"].int == 1) features += Feature.ELEVATOR
            if (o["ac"].int == 1) features += Feature.AC
            if (o["car_entrance"].int == 1) features += Feature.PARKING
            if (o["private_roof"].int == 1) features += Feature.ROOF
            if (o["special_entrance"].int == 1 || o["two_entrances"].int == 1) features += Feature.PRIVATE_ENTRANCE
            if (o["ketchen"].int == 1) features += Feature.KITCHEN_FITTED
            val age = o["age"].int
            if (age != null && age <= 1) features += Feature.NEW_BUILDING
            val path = o["path"].str
            return Listing(
                source = Source.AQAR,
                sourceId = id.toString(),
                url = if (path != null) BASE + path else "$BASE/$id",
                title = o["title"].str ?: "",
                description = o["content"].str ?: "",
                kind = when {
                    o["category"].int == 4 || o["apartment_in_villa"].int == 1 -> PropertyKind.FLOOR
                    else -> PropertyKind.APARTMENT
                },
                price = price?.takeIf { it > 0 },
                period = period,
                bedrooms = o["beds"].int?.takeIf { it > 0 },
                livingRooms = o["livings"].int,
                bathrooms = o["wc"].int?.takeIf { it > 0 },
                kitchen = true,
                kitchenFitted = o["ketchen"].int?.let { it == 1 },
                areaSqm = o["area"].dbl,
                floor = o["fl"].int,
                buildingAgeYears = age,
                furnishing = when (o["furnished"].int) {
                    1 -> Furnishing.FURNISHED
                    0 -> Furnishing.UNFURNISHED
                    else -> Furnishing.UNKNOWN
                },
                audience = when (o["family"].int) {
                    1 -> Audience.FAMILIES
                    0 -> Audience.SINGLES
                    else -> Audience.UNKNOWN
                },
                city = o["city"].str,
                district = o["district"].str?.removePrefix("حي ")?.trim(),
                location = point,
                locationPrecision = if (point != null) LocationPrecision.EXACT else LocationPrecision.UNKNOWN,
                photos = o["imgs"].arr.mapNotNull { it.str }.map { if (it.startsWith("http")) it else IMG + it },
                advertiserName = user["company_name"].str?.trim() ?: user["name"].str,
                licenseNumber = o["ad_license_number"].str,
                postedAt = o["create_time"].lng?.times(1000),
                updatedAt = (o["last_update"].lng ?: o["published_at"].lng)?.times(1000),
                features = features,
            )
        }

        @Suppress("unused")
        private fun prim(v: Any) = JsonPrimitive(v.toString())
    }
}
