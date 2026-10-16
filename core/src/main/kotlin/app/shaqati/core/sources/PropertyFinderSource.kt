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

    override suspend fun details(listing: Listing, ctx: SearchContext): Listing {
        var resp = ctx.http.send(HttpRequest(listing.url, originPage = BASE + "/ar"))
        if (resp.status == 403 || resp.status == 405 || resp.status == 202) {
            resp = ctx.http.send(HttpRequest(listing.url, mode = FetchMode.BROWSER, originPage = BASE + "/ar"))
        }
        if (!resp.ok) return listing
        return mergeDetails(listing, resp.body)
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

        /** Amenity names on ad pages (the list view only carries utility codes such as EL = electricity). */
        private val amenityNames = listOf(
            "مصعد" to Feature.ELEVATOR, "elevator" to Feature.ELEVATOR,
            "تكييف" to Feature.AC, "مكيف" to Feature.AC, "air condition" to Feature.AC,
            "موقف" to Feature.PARKING, "parking" to Feature.PARKING,
            "غرفة خادمة" to Feature.MAID_ROOM, "maid" to Feature.MAID_ROOM,
            "مسبح" to Feature.POOL, "pool" to Feature.POOL,
            "شرفة" to Feature.BALCONY, "بلكونة" to Feature.BALCONY, "balcony" to Feature.BALCONY,
            "مخزن" to Feature.STORAGE, "storage" to Feature.STORAGE,
        )

        /** Adds the ad page's full photo set, description, building age and amenities. */
        fun mergeDetails(listing: Listing, html: String): Listing {
            val p = NextData.extract(html)["props"]["pageProps"]["propertyResult"]["property"] ?: return listing
            val photos = p["images"]["property"].arr.mapNotNull { it["full"].str ?: it["medium"].str ?: it["small"].str }
            val features = p["amenities"].arr.mapNotNull { a ->
                val name = (a["name"].str ?: a.str ?: return@mapNotNull null).lowercase()
                amenityNames.firstOrNull { name.contains(it.first) }?.second
            }
            return listing.copy(
                description = p["description"].str ?: listing.description,
                photos = photos.ifEmpty { listing.photos },
                buildingAgeYears = p["age"].int ?: listing.buildingAgeYears,
                bedrooms = p["bedrooms_value"].int ?: listing.bedrooms,
                bathrooms = p["bathrooms_value"].int ?: listing.bathrooms,
                phone = listing.phone ?: p["rega_attributes"]["responsible_employee_phone_number"].str,
                features = listing.features + features,
            )
        }

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
                // Coordinates are the district centre unless the location is a building or street.
                locationPrecision = when {
                    point == null -> LocationPrecision.UNKNOWN
                    p["location"]["type"].str == "DISTRICT" -> LocationPrecision.DISTRICT
                    else -> LocationPrecision.APPROXIMATE
                },
                photos = p["images"].arr.mapNotNull { it["full"].str ?: it["medium"].str ?: it["small"].str },
                advertiserName = p["broker"]["name"].str ?: p["agent"]["name"].str,
                phone = contact("phone"),
                whatsapp = contact("whatsapp"),
                licenseNumber = p["rera"].str,
                postedAt = p["listed_date"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                updatedAt = p["last_refreshed_at"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                features = if (p["is_new_construction"].bool == true) setOf(Feature.NEW_BUILDING) else emptySet(),
            )
        }
    }
}
