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
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Wasalt (wasalt.sa): list and ad pages are Next.js pages whose data (`searchResult`,
 * `propertyDetailsV3`) is in `__NEXT_DATA__`. Wasalt sits behind Cloudflare, which challenges
 * plain clients, so pages are read in the in-app browser; a challenge marks the site blocked.
 */
class WasaltSource : ListingSource {
    override val source = Source.WASALT

    override suspend fun search(ctx: SearchContext): SearchResult {
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        var complete = false
        // Riyadh-wide in the site's default order (recent ads first), 32 to a page; the budget
        // filter keeps monthly prices too.
        for (page in 1..ctx.maxPages * 4) {
            val resp = runCatching { ctx.http.send(request(searchUrl(page, ctx.criteria.maxAnnualRent))) }
                .getOrElse { errors += it.message ?: "error"; null } ?: break
            pages++
            if (BotCheck.blocked(resp)) {
                return SearchResult(source, out.values.toList(), pages, errors + BotCheck.MESSAGE, blocked = true)
            }
            if (!resp.ok) { errors += "HTTP ${resp.status}"; break }
            val result = parseSearch(resp.body)
            if (result == null) { errors += "no listings data"; break }
            if (page == 1) ctx.log("wasalt: ${result.total} ads in ${result.pageCount} pages")
            result.listings.filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
                .forEach { out[it.id] = it }
            if (result.listings.isEmpty() || page >= result.pageCount) { complete = true; break }
        }
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5), complete = complete && errors.isEmpty())
    }

    override suspend fun details(listing: Listing, ctx: SearchContext): Listing {
        val resp = ctx.http.send(request(listing.url))
        if (!resp.ok || BotCheck.blocked(resp)) return listing
        return mergeDetails(listing, resp.body)
    }

    data class Page(val listings: List<Listing>, val total: Int, val pageCount: Int)

    companion object {
        const val BASE = "https://wasalt.sa"
        private const val IMAGES = "https://imagedelivery.net/1DNKFJPRaeUdy_j8F7HT3w/production/properties/"

        private fun request(url: String) =
            HttpRequest(url, headers = mapOf("Accept-Language" to "ar"), mode = FetchMode.BROWSER)

        /**
         * Apartments for rent in Riyadh up to [maxPrice]. The site counts pages from 0 and leaves
         * the first one out of the URL, as its own filter code does.
         */
        fun searchUrl(page: Int, maxPrice: Double): String {
            val path = AqarSource.encode("$BASE/شقق-للايجار-في-الرياض")
            return "$path?max_price=${maxPrice.toLong()}" + if (page > 1) "&page=${page - 1}" else ""
        }

        /** The listings on a list page with the total count and number of pages; null when the page has no results data. */
        fun parseSearch(html: String): Page? {
            val sr = NextData.extract(html)["props"]["pageProps"]["searchResult"] ?: return null
            val listings = sr["properties"].arr.mapNotNull { runCatching { toListing(it) }.getOrNull() }
            val total = sr["count"].int ?: listings.size
            return Page(listings, total, sr["totalPages"].int ?: ((total + 31) / 32))
        }

        private val amenityNames = listOf(
            "مصعد" to Feature.ELEVATOR, "elevator" to Feature.ELEVATOR, "lift" to Feature.ELEVATOR,
            "موقف" to Feature.PARKING, "parking" to Feature.PARKING,
            "تكييف" to Feature.AC, "مكيف" to Feature.AC, "air condition" to Feature.AC,
            "غرفة خادمة" to Feature.MAID_ROOM, "خادمة" to Feature.MAID_ROOM, "maid" to Feature.MAID_ROOM,
            "غرفة سائق" to Feature.DRIVER_ROOM, "driver" to Feature.DRIVER_ROOM,
            "مسبح" to Feature.POOL, "pool" to Feature.POOL,
            "شرفة" to Feature.BALCONY, "بلكونة" to Feature.BALCONY, "balcony" to Feature.BALCONY,
            "مخزن" to Feature.STORAGE, "مستودع" to Feature.STORAGE, "storage" to Feature.STORAGE,
            "مطبخ" to Feature.KITCHEN_FITTED, "kitchen" to Feature.KITCHEN_FITTED,
        )

        private fun features(names: List<String>): Set<String> = names.mapNotNull { n ->
            val key = n.lowercase()
            amenityNames.firstOrNull { key.contains(it.first) }?.second
        }.toSet()

        /** Adds the ad page's description, every photo, exact pin flag, building age and amenities. */
        fun mergeDetails(listing: Listing, html: String): Listing {
            val pp = NextData.extract(html)["props"]["pageProps"]
            val d = pp["propertyDetailsV3"] ?: return listing
            if (d["id"].str != listing.sourceId) return listing
            val info = d["propertyInfo"]
            val gallery = pp["galleryDetails"]["images"]["data"].arr
                .filter { (it["type"].str ?: "image") == "image" }
                .sortedBy { it["sequence"].int ?: 0 }
                .mapNotNull { it["content"].str?.let { name -> photo(listing.sourceId, name) } }
            val attrs = d["attributes"].arr.associate { (it["key"].str ?: "") to it["value"] }
            val extra = d["additionalAttributes"].arr.associate { (it["key"].str ?: "") to it["value"] }
            val amenities = (pp["infoDetails"]["amenities"].arr + pp["infoDetails"]["fittings"].arr)
                .mapNotNull { it["name"].str ?: it["title"].str ?: it.str }
            return listing.copy(
                description = HtmlText.plain(info["description"].str) ?: listing.description,
                photos = gallery.ifEmpty { listing.photos },
                bedrooms = attrs["noOfBedrooms"].int ?: listing.bedrooms,
                livingRooms = attrs["noOfLivingrooms"].int ?: listing.livingRooms,
                bathrooms = attrs["noOfBathrooms"].int ?: listing.bathrooms,
                hasMajlis = attrs["noOfGuestrooms"].int?.let { it > 0 } ?: listing.hasMajlis,
                areaSqm = attrs["builtUpArea"].dbl ?: listing.areaSqm,
                buildingAgeYears = age(extra["completionYear"]) ?: listing.buildingAgeYears,
                furnishing = furnishing(info["furnishingType"].str).takeIf { it != Furnishing.UNKNOWN } ?: listing.furnishing,
                locationPrecision = when {
                    listing.location == null -> listing.locationPrecision
                    d["isApproxLocation"].bool == false -> LocationPrecision.EXACT
                    else -> listing.locationPrecision
                },
                licenseNumber = listing.licenseNumber ?: d["propertyOwner"]["regaAdvLicNo"].str,
                features = listing.features + features(amenities),
            )
        }

        fun photo(id: String, name: String): String = "$IMAGES$id/images/$name/width=1000,quality=70,format=auto"

        private fun furnishing(s: String?): Furnishing = when (s?.trim()?.lowercase()) {
            null, "" -> Furnishing.UNKNOWN
            "مفروشة", "مفروش", "furnished" -> Furnishing.FURNISHED
            "غير مفروشة", "غير مفروش", "un-furnished", "unfurnished" -> Furnishing.UNFURNISHED
            "شبه مفروشة", "شبه مفروش", "semi-furnished", "semi furnished" -> Furnishing.SEMI_FURNISHED
            else -> Furnishing.UNKNOWN
        }

        /** "4 سنة", 6, "جديد" (new). */
        private fun age(v: JsonElement?): Int? {
            val s = v.str ?: return null
            if (s.contains("جديد") || s.equals("new", ignoreCase = true)) return 0
            return Regex("""\d+""").find(s)?.value?.toIntOrNull()
        }

        private fun instant(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        private fun phone(code: String?, number: String?): String? {
            val n = number?.takeIf { it.any(Char::isDigit) } ?: return null
            return if (n.startsWith("+") || code.isNullOrBlank()) n else code + n
        }

        internal fun toListing(p: JsonElement): Listing? {
            val info = p["propertyInfo"] ?: return null // projects (several units) have projectInfo instead
            val id = p["id"].str ?: return null
            if (info["propertyFor"].str.let { it != null && it != "rent" }) return null
            val freqs = info["rentFreq"]
            val chosen = (freqs as? JsonObject)?.values?.let { all -> all.firstOrNull { it["default_freq"].bool == true } ?: all.singleOrNull() }
            val price = chosen?.get("amount").dbl ?: info["expectedRent"].dbl
            val period = when (chosen?.get("freq").str ?: info["expectedRentType"].str) {
                "yearly", "/سنة", "/Year" -> RentPeriod.YEARLY
                "monthly", "/الشهر", "/Month" -> RentPeriod.MONTHLY
                "half_yearly", "semi_annually" -> RentPeriod.SEMI_ANNUAL
                "daily" -> RentPeriod.DAILY
                else -> RentPeriod.UNKNOWN
            }
            val attrs = p["attributes"].arr.associate { (it["key"].str ?: "") to it["value"] }
            val loc = p["location"]
            val point = runCatching { GeoPoint(loc["lat"].dbl!!, loc["lon"].dbl!!) }.getOrNull()?.takeIf { Geo.inRiyadh(it) }
            val images = p["propertyFiles"]["images"].arr.mapNotNull { it.str ?: it["name"].str }
            val owner = p["propertyOwner"]
            val contact = p["contactDetails"]
            val slug = info["slug"].str
            return Listing(
                source = Source.WASALT,
                sourceId = id,
                url = AqarSource.encode(if (slug != null) "$BASE/property/rent/$slug" else "$BASE/property/rent/$id"),
                title = info["title"].str ?: info["propertyName"].str ?: "",
                kind = when (info["propertySubTypeSlug"].str) {
                    "apartment" -> PropertyKind.APARTMENT
                    "floor" -> PropertyKind.FLOOR
                    "studio" -> PropertyKind.STUDIO
                    "villa" -> PropertyKind.VILLA
                    else -> PropertyKind.OTHER
                },
                price = price?.takeIf { it > 0 },
                period = period,
                bedrooms = attrs["noOfBedrooms"].int,
                livingRooms = attrs["noOfLivingrooms"].int,
                bathrooms = attrs["noOfBathrooms"].int,
                hasMajlis = attrs["noOfGuestrooms"].int?.let { it > 0 },
                areaSqm = (p["floorSize"].dbl ?: attrs["builtUpArea"].dbl)?.takeIf { it > 0 },
                buildingAgeYears = age(attrs["completionYear"]),
                furnishing = furnishing(info["furnishingType"].str),
                city = info["city"].str,
                district = info["district"].str ?: info["zone"].str,
                location = point,
                locationPrecision = when {
                    point == null -> LocationPrecision.UNKNOWN
                    p["isApproxLocation"].bool == false -> LocationPrecision.EXACT
                    else -> LocationPrecision.APPROXIMATE
                },
                photos = images.map { photo(id, it) },
                advertiserName = owner["regaAdvName"].str ?: owner["name"].str?.trim() ?: owner["arName"].str,
                phone = phone(contact["phoneCountryCode"].str, contact["phoneNumber"].str),
                whatsapp = phone(contact["whatsappCountryCode"].str, contact["whatsappNumber"].str),
                licenseNumber = owner["regaAdvLicNo"].str,
                postedAt = instant(p["publishedAt"].str) ?: instant(p["createdAt"].str),
                updatedAt = instant(p["updatedAt"].str),
                features = features(p["featureAmenities"].arr.mapNotNull { it["name"].str }),
            )
        }
    }
}
