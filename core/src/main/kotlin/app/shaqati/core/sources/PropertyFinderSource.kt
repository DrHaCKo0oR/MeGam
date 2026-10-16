package app.shaqati.core.sources

import app.shaqati.core.model.Feature
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.util.FetchMode
import app.shaqati.core.util.Geo
import app.shaqati.core.util.HttpRequest
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Property Finder Saudi (propertyfinder.sa): search pages embed results in Next.js page data.
 * Protected by AWS WAF; falls back to the in-app browser when plain requests are refused.
 */
class PropertyFinderSource : ListingSource {
    override val source = Source.PROPERTY_FINDER

    override suspend fun search(ctx: SearchContext): SearchResult {
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        var mode = FetchMode.DIRECT
        // Yearly-priced ads up to the budget, then monthly-priced ads up to a twelfth of it.
        for ((period, max) in listOf("y" to ctx.criteria.maxAnnualRent, "m" to ctx.criteria.maxAnnualRent / 12)) {
            val maxPages = if (period == "y") ctx.maxPages else (ctx.maxPages + 1) / 2
            for (page in 1..maxPages) {
                val url = searchUrl(max, page, period)
                var resp = runCatching { ctx.http.send(HttpRequest(url, mode = mode, originPage = BASE + "/ar")) }
                    .getOrElse { errors += it.message ?: "error"; null } ?: break
                if ((resp.status == 403 || resp.status == 405 || resp.status == 202) && mode == FetchMode.DIRECT) {
                    mode = FetchMode.BROWSER
                    resp = runCatching { ctx.http.send(HttpRequest(url, mode = mode, originPage = BASE + "/ar")) }.getOrNull() ?: break
                }
                pages++
                if (resp.status == 403) return SearchResult(source, out.values.toList(), pages, errors, blocked = true)
                if (resp.status != 200) { errors += "HTTP ${resp.status}"; break }
                val (items, pageCount) = parseSearch(resp.body)
                items.filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
                    .forEach { out[it.id] = it }
                if (items.isEmpty() || page >= pageCount) break
            }
        }
        return SearchResult(source, out.values.toList(), pages, errors.take(5))
    }

    companion object {
        const val BASE = "https://www.propertyfinder.sa"

        /** Riyadh (location 8216), rent, apartments, price ≤ [maxPrice] per [period] (y or m), newest first. */
        fun searchUrl(maxPrice: Double, page: Int, period: String = "y"): String =
            "$BASE/ar/search?c=2&l=8216&t=1&rp=$period&pt=${maxPrice.toLong()}&ob=nd&page=$page"

        /** Returns the listings on a search page and the number of pages. */
        fun parseSearch(html: String): Pair<List<Listing>, Int> {
            val nd = NextData.extract(html) ?: return emptyList<Listing>() to 0
            val sr = nd["props"]["pageProps"]["searchResult"]
            val listings = sr["listings"].arr.mapNotNull { runCatching { toListing(it["property"] ?: return@runCatching null) }.getOrNull() }
            return listings to (sr["meta"]["page_count"].int ?: 1)
        }

        private val amenityMap = mapOf(
            "AC" to Feature.AC, "BA" to Feature.BALCONY, "CP" to Feature.PARKING, "MR" to Feature.MAID_ROOM,
            "SP" to Feature.POOL, "PP" to Feature.POOL, "ST" to Feature.STORAGE, "EL" to Feature.ELEVATOR,
        )

        internal fun toListing(p: JsonElement): Listing? {
            val id = p["id"].str ?: return null
            if (p["is_available"].bool == false) return null
            val period = when (p["price"]["period"].str?.lowercase()) {
                "yearly" -> RentPeriod.YEARLY
                "monthly" -> RentPeriod.MONTHLY
                "daily", "weekly" -> RentPeriod.DAILY
                else -> RentPeriod.UNKNOWN
            }
            val coords = p["location"]["coordinates"]
            val point = runCatching { GeoPoint(coords["lat"].dbl!!, coords["lon"].dbl!!) }.getOrNull()
            val contacts = p["contact_options"].arr
            fun contact(type: String) = contacts.firstOrNull { it["type"].str == type }?.get("value").str
            return Listing(
                source = Source.PROPERTY_FINDER,
                sourceId = id,
                url = p["share_url"].str ?: "$BASE/ar/plp/$id.html",
                title = p["title"].str ?: "",
                description = p["description"].str ?: "",
                kind = when (p["property_type_id"].int) {
                    1 -> PropertyKind.APARTMENT
                    51 -> PropertyKind.FLOOR
                    24 -> PropertyKind.APARTMENT
                    35 -> PropertyKind.VILLA
                    else -> PropertyKind.OTHER
                },
                price = p["price"]["value"].dbl?.takeIf { p["price"]["is_hidden"].bool != true },
                period = period,
                bedrooms = p["bedrooms"].int,
                bathrooms = p["bathrooms"].int,
                areaSqm = p["size"]["value"].dbl,
                furnishing = when (p["furnished"].str?.uppercase()) {
                    "YES" -> Furnishing.FURNISHED
                    "NO" -> Furnishing.UNFURNISHED
                    "PARTLY", "PARTIAL", "SEMI" -> Furnishing.SEMI_FURNISHED
                    else -> Furnishing.UNKNOWN
                },
                city = p["location_tree"].arr.firstOrNull { it["type"].str == "CITY" }?.get("name").str,
                district = p["location"]["name"].str,
                location = point,
                locationPrecision = if (point != null) LocationPrecision.APPROXIMATE else LocationPrecision.UNKNOWN,
                photos = p["images"].arr.mapNotNull { it["full"].str ?: it["medium"].str ?: it["small"].str },
                advertiserName = p["broker"]["name"].str ?: p["agent"]["name"].str,
                phone = contact("phone"),
                whatsapp = contact("whatsapp"),
                licenseNumber = p["rera"].str,
                postedAt = p["listed_date"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                updatedAt = p["last_refreshed_at"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                features = p["amenities"].arr.mapNotNull { amenityMap[it.str] }.toSet(),
            )
        }
    }
}
