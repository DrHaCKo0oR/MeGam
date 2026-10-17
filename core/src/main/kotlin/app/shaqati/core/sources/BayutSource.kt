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

/**
 * Bayut (bayut.sa): search and ad pages embed the site's state as `window.state = {…}`, with the
 * search results as Algolia hits. Bayut answers plain clients with a bot check, so its pages are
 * read in the in-app browser, like the person's own browsing; when the site still asks for a
 * check, the result is marked blocked and the app offers to open the site.
 */
class BayutSource : ListingSource {
    override val source = Source.BAYUT

    override suspend fun search(ctx: SearchContext): SearchResult {
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        var complete = true
        // Yearly-priced ads up to the budget, then monthly-priced ads up to a twelfth of it.
        for ((period, max) in listOf("yearly" to ctx.criteria.maxAnnualRent, "monthly" to ctx.criteria.maxAnnualRent / 12)) {
            // Results are city-wide and newest first, 24 to a page.
            val maxPages = if (period == "yearly") ctx.maxPages * 3 else ctx.maxPages
            var reachedEnd = false
            for (page in 1..maxPages) {
                val resp = runCatching { ctx.http.send(request(searchUrl(page, max, period))) }
                    .getOrElse { errors += it.message ?: "error"; null } ?: break
                pages++
                if (BotCheck.blocked(resp)) {
                    return SearchResult(source, out.values.toList(), pages, errors + BotCheck.MESSAGE, blocked = true)
                }
                if (!resp.ok) { errors += "HTTP ${resp.status}"; break }
                val result = parseSearch(resp.body)
                if (result == null) { errors += "no listings data"; break }
                if (page == 1) ctx.log("bayut $period: ${result.total} ads in ${result.pageCount} pages")
                result.listings.filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
                    .forEach { out[it.id] = it }
                if (result.listings.isEmpty() || page >= result.pageCount) { reachedEnd = true; break }
            }
            if (!reachedEnd) complete = false
        }
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5), complete = complete && errors.isEmpty())
    }

    override suspend fun details(listing: Listing, ctx: SearchContext): Listing {
        val resp = ctx.http.send(request(detailUrl(listing.sourceId)))
        if (!resp.ok || BotCheck.blocked(resp)) return listing
        return mergeDetails(listing, resp.body)
    }

    data class Page(val listings: List<Listing>, val total: Int, val pageCount: Int)

    companion object {
        const val BASE = "https://www.bayut.sa"
        private const val IMAGES = "https://images.bayut.sa/thumbnails/"

        private fun request(url: String) =
            HttpRequest(url, headers = mapOf("Accept-Language" to "ar"), mode = FetchMode.BROWSER)

        /** Riyadh apartments for rent, priced up to [maxPrice] per [period] (yearly or monthly), newest first. */
        fun searchUrl(page: Int, maxPrice: Double, period: String = "yearly"): String {
            val path = "$BASE/en/to-rent/apartments/riyadh/" + if (page > 1) "page-$page/" else ""
            return "$path?rent_frequency=$period&price_max=${maxPrice.toLong()}&sort=date_desc"
        }

        /** The Arabic ad page, which carries the advertiser's own (Arabic) description. */
        fun detailUrl(id: String): String = AqarSource.encode("$BASE/property/تفاصيل-$id.html")

        /** The `window.state` object of a Bayut page, or null when the page has none (bot check). */
        fun state(html: String): JsonElement? {
            val i = html.indexOf(STATE)
            if (i < 0) return null
            return RscPayload.valueAfter(html, STATE, i)
        }

        private const val STATE = "window.state = "

        /** The listings on a search page with the total count and number of pages; null when the page has no results data. */
        fun parseSearch(html: String): Page? {
            val content = state(html)["algolia"]["content"] ?: return null
            val hits = content["hits"].arr
            val listings = hits.mapNotNull { runCatching { toListing(it) }.getOrNull() }
            return Page(listings, content["nbHits"].int ?: listings.size, content["nbPages"].int ?: 1)
        }

        /** Amenity slugs and names on ad pages. */
        private val amenityNames = listOf(
            "elevator" to Feature.ELEVATOR, "lift" to Feature.ELEVATOR, "مصعد" to Feature.ELEVATOR,
            "parking" to Feature.PARKING, "موقف" to Feature.PARKING,
            "air-condition" to Feature.AC, "central-ac" to Feature.AC, "تكييف" to Feature.AC, "مكيف" to Feature.AC,
            "maid" to Feature.MAID_ROOM, "خادمة" to Feature.MAID_ROOM,
            "driver" to Feature.DRIVER_ROOM, "سائق" to Feature.DRIVER_ROOM,
            "pool" to Feature.POOL, "مسبح" to Feature.POOL,
            "balcony" to Feature.BALCONY, "شرفة" to Feature.BALCONY, "بلكونة" to Feature.BALCONY,
            "storage" to Feature.STORAGE, "مخزن" to Feature.STORAGE, "مستودع" to Feature.STORAGE,
            "kitchen-appliances" to Feature.KITCHEN_FITTED, "مطبخ راكب" to Feature.KITCHEN_FITTED,
            "smart-home" to Feature.SMART_HOME,
        )

        /** Adds the ad page's description, every photo, the exact pin when there is one, amenities and the ad licence. */
        fun mergeDetails(listing: Listing, html: String): Listing {
            val d = state(html)["property"]["data"] ?: return listing
            if (d["externalID"].str != listing.sourceId) return listing
            val photos = d["photos"].arr.sortedBy { it["orderIndex"].int ?: 0 }.mapNotNull { it["id"].str?.let(::photo) }
            val amenityList = d["amenities"].arr.flatMap { it["amenities"].arr }
            val amenities = amenityList.mapNotNull { a ->
                val key = "${a["slug"].str.orEmpty()} ${a["text"].str.orEmpty()}".lowercase()
                amenityNames.firstOrNull { key.contains(it.first) }?.second
            }
            val floor = amenityList.firstOrNull { it["slug"].str == "floor" }?.get("value").int
            val extra = d["extraFields"]
            val isNew = extra["rega_additional_info_listing_age"]["en"].str.equals("New", ignoreCase = true)
            val exact = d["hasExactGeography"].bool
            val descriptions = listOfNotNull(HtmlText.plain(d["description"].str), HtmlText.plain(d["description_l1"].str))
            return listing.copy(
                description = descriptions.firstOrNull { ArabicLetters.has(it) } ?: descriptions.firstOrNull() ?: listing.description,
                photos = photos.ifEmpty { listing.photos },
                bedrooms = d["rooms"].int?.takeIf { it > 0 } ?: listing.bedrooms,
                bathrooms = d["baths"].int?.takeIf { it > 0 } ?: listing.bathrooms,
                floor = floor ?: listing.floor,
                locationPrecision = when {
                    listing.location == null -> listing.locationPrecision
                    exact == true -> LocationPrecision.EXACT
                    exact == false -> LocationPrecision.DISTRICT
                    else -> listing.locationPrecision
                },
                licenseNumber = d["permitNumber"].str ?: extra["rega_license_info_ad_license_number"].str ?: listing.licenseNumber,
                features = listing.features + amenities + if (isNew) setOf(Feature.NEW_BUILDING) else emptySet(),
            )
        }

        fun photo(id: String): String = "$IMAGES$id-800x600.jpeg"

        internal fun toListing(h: JsonElement): Listing? {
            val id = h["externalID"].str ?: return null
            if (h["purpose"].str != "for-rent" || h["state"].str.let { it != null && it != "active" }) return null
            val titles = listOfNotNull(h["title"].str, h["title_l1"].str)
            val title = titles.firstOrNull { ArabicLetters.has(it) } ?: titles.firstOrNull().orEmpty()
            val rooms = h["rooms"].int
            val baths = h["baths"].int
            // Both zero means the advertiser left them out; zero rooms alone is a studio.
            val unknownRooms = rooms == 0 && (baths ?: 0) == 0
            val studio = !unknownRooms && rooms == 0 || titles.any { it.contains("Studio", ignoreCase = true) || it.contains("استوديو") || it.contains("استديو") }
            val loc = h["location"].arr
            fun name(level: Int): String? = loc.firstOrNull { it["level"].int == level }?.let { l ->
                listOfNotNull(l["name"].str, l["name_l1"].str).firstOrNull { ArabicLetters.has(it) } ?: l["name"].str
            }
            val geo = h["geography"]
            val point = runCatching { GeoPoint(geo["lat"].dbl!!, geo["lng"].dbl!!) }.getOrNull()?.takeIf { Geo.inRiyadh(it) }
            val photoIds = h["photoIDs"].arr.mapNotNull { it.str }.ifEmpty { listOfNotNull(h["coverPhoto"]["id"].str) }
            val phones = h["phoneNumber"]
            val agency = h["agency"]
            return Listing(
                source = Source.BAYUT,
                sourceId = id,
                url = detailUrl(id),
                title = title,
                kind = when {
                    h["category"].arr.lastOrNull()?.get("slug").str != "apartments" -> PropertyKind.OTHER
                    studio && (rooms ?: 0) <= 1 -> PropertyKind.STUDIO
                    else -> PropertyKind.APARTMENT
                },
                price = h["price"].dbl?.takeIf { h["hidePrice"].bool != true && it > 0 },
                period = when (h["rentFrequency"].str) {
                    "yearly" -> RentPeriod.YEARLY
                    "monthly" -> RentPeriod.MONTHLY
                    "weekly", "daily" -> RentPeriod.DAILY
                    else -> RentPeriod.UNKNOWN
                },
                bedrooms = if (unknownRooms) null else rooms,
                bathrooms = baths?.takeIf { it > 0 },
                areaSqm = h["area"].dbl?.takeIf { it > 0 },
                furnishing = when (h["furnishingStatus"].str) {
                    "unfurnished" -> Furnishing.UNFURNISHED
                    "furnished" -> Furnishing.FURNISHED
                    "semi-furnished", "partly-furnished" -> Furnishing.SEMI_FURNISHED
                    else -> Furnishing.UNKNOWN
                },
                city = name(1),
                district = name(3) ?: name(2),
                location = point,
                // Advertisers may leave the pin on the district centre; the ad page says which it is.
                locationPrecision = if (point == null) LocationPrecision.UNKNOWN else LocationPrecision.APPROXIMATE,
                photos = photoIds.map(::photo),
                advertiserName = listOfNotNull(agency["name_l1"].str, agency["name"].str).firstOrNull { ArabicLetters.has(it) }
                    ?: agency["name"].str ?: h["contactName"].str,
                phone = phones["mobile"].str?.takeUnless { it.contains('X') } ?: phones["proxyMobile"].str,
                whatsapp = phones["whatsapp"].str,
                postedAt = h["createdAt"].lng?.let { it * 1000 },
                updatedAt = (h["updatedAt"].lng ?: h["reactivatedAt"].lng)?.let { it * 1000 },
            )
        }
    }
}

/** Signs that a site answered with a bot check instead of its page. */
object BotCheck {
    const val MESSAGE = "bot check"

    private val markers = listOf(
        "hcaptcha", "h-captcha", "cf-turnstile", "challenges.cloudflare.com", "cf_chl_opt",
        "Security check", "Just a moment", "لحظة…", "Attention Required", "captcha-delivery",
    )

    fun blocked(resp: app.shaqati.core.util.HttpResponse): Boolean {
        if (resp.status == 401 || resp.status == 403 || resp.status == 429 || resp.status == 503) return true
        // A real page is large; a check page is small and names its captcha.
        return resp.body.length < 60_000 && markers.any { resp.body.contains(it, ignoreCase = true) }
    }
}

/** Arabic letters in a string (to pick the Arabic one of a bilingual pair). */
object ArabicLetters {
    private val letter = Regex("[\\u0621-\\u064A]")
    fun has(s: String?): Boolean = s != null && letter.containsMatchIn(s)
}
