package app.shaqati.core.sources

import app.shaqati.core.model.Feature
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.text.ArabicText
import app.shaqati.core.util.FetchMode
import app.shaqati.core.util.Geo
import app.shaqati.core.util.HttpRequest
import kotlinx.serialization.json.JsonElement

/**
 * OpenSooq (sa.opensooq.com): the Riyadh "apartments for rent" pages carry their results
 * as Next.js page data. Ad pages add every photo, the map pin and the full description.
 */
class OpenSooqSource : ListingSource {
    override val source = Source.OPENSOOQ

    override suspend fun search(ctx: SearchContext): SearchResult {
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        var mode = FetchMode.DIRECT
        var page = 1
        var complete = false
        while (page <= ctx.maxPages) {
            val resp = runCatching {
                ctx.http.send(HttpRequest(listUrl(page), headers = mapOf("Accept-Language" to "ar"), mode = mode))
            }.getOrElse { errors += it.message ?: "error"; null } ?: break
            if ((resp.status == 403 || resp.status == 429) && mode == FetchMode.DIRECT) { mode = FetchMode.BROWSER; continue }
            pages++
            if (resp.status == 403 || resp.status == 429) return SearchResult(source, out.values.toList(), pages, errors + "HTTP ${resp.status}", blocked = true)
            if (!resp.ok) { errors += "HTTP ${resp.status}"; break }
            val (items, pageCount) = parseSearch(resp.body)
            items.filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
                .forEach { out[it.id] = it }
            if (items.isEmpty() || page >= pageCount) { complete = true; break }
            page++
        }
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5), complete = complete && errors.isEmpty())
    }

    override suspend fun details(listing: Listing, ctx: SearchContext): Listing {
        val resp = ctx.http.send(HttpRequest(listing.url, headers = mapOf("Accept-Language" to "ar")))
        if (!resp.ok) return listing
        return mergeDetails(listing, resp.body)
    }

    companion object {
        const val BASE = "https://sa.opensooq.com"
        private const val IMG = "https://opensooq-imagesv2.os-cdn.com/previews/700x0/"

        fun listUrl(page: Int): String {
            val path = AqarSource.encode("$BASE/ar/الرياض/عقارات/شقق-للايجار")
            return "$path?sort_code=recent" + if (page > 1) "&page=$page" else ""
        }

        fun image(uri: String): String = if (uri.startsWith("http")) uri.replace("{size}", "700x0") else "$IMG${uri.trimStart('/')}.webp"

        /** Listings and the page count from a results page. */
        fun parseSearch(html: String): Pair<List<Listing>, Int> {
            val data = NextData.extract(html) ?: return emptyList<Listing>() to 0
            val serp = data["props"]["pageProps"]["serpApiResponse"]["listings"]
            val items = serp["items"].arr.mapNotNull { runCatching { toListing(it) }.getOrNull() }
            return items to (serp["meta"]["pages"].int ?: 1)
        }

        internal fun toListing(o: JsonElement): Listing? {
            val id = o["id"].lng ?: return null
            if (o["is_active"].bool == false) return null
            if (o["cat2_code"].str?.let { it != "ApartmentsForRent" } == true) return null
            if (o["city_id"].str?.let { it != "24" } == true) return null
            val facts = Cps.fromLabels(o["cps"].arr.mapNotNull { it.str })
            val title = o["title"].str ?: ""
            val district = o["nhood_label"].str
            val point = district?.let { Districts.find(it) }?.let { GeoPoint(it.lat, it.lng) }
            val price = o["price_amount"].str?.let { ArabicText.asciiDigits(it).filter(Char::isDigit).toDoubleOrNull() }
            return Listing(
                source = Source.OPENSOOQ,
                sourceId = id.toString(),
                url = "$BASE/ar/search/$id",
                title = title,
                description = o["masked_description"].str?.removeSuffix("…") ?: "",
                kind = when {
                    title.contains("دور") -> PropertyKind.FLOOR
                    title.contains("استديو") || title.contains("استوديو") -> PropertyKind.STUDIO
                    else -> PropertyKind.APARTMENT
                },
                price = price?.takeIf { it > 0 },
                period = facts.period,
                bedrooms = facts.bedrooms,
                bathrooms = facts.bathrooms,
                areaSqm = facts.area,
                floor = facts.floor,
                buildingAgeYears = facts.ageYears,
                furnishing = facts.furnishing,
                city = o["city_label"].str,
                district = district,
                location = point,
                locationPrecision = if (point != null) LocationPrecision.DISTRICT else LocationPrecision.UNKNOWN,
                photos = listOfNotNull(o["image_uri"].str?.let(::image)),
                advertiserName = o["member_display_name"].str,
                features = if (facts.ageYears == 0) setOf(Feature.NEW_BUILDING) else emptySet(),
            )
        }

        /** Adds the ad page's photos, map pin, full text and attributes. */
        fun mergeDetails(listing: Listing, html: String): Listing {
            val p = RscPayload.decode(html)
            if (p.isEmpty()) return listing
            val galleryAt = p.indexOf("\"gallery\":{\"chips\"")
            val media = if (galleryAt >= 0) RscPayload.valueAfter(p, "\"gallery\":", galleryAt)["media"].arr else emptyList()
            val photos = media.filter { it["mimeType"].str?.startsWith("image") == true && it["isFloorPlan"].bool != true }
                .mapNotNull { it["uri"].str?.let(::image) }
            val mapAt = p.indexOf("\"postMap\":")
            val map = if (mapAt >= 0) RscPayload.valueAfter(p, "\"postMap\":", mapAt) else null
            val pin = runCatching { GeoPoint(map["lat"].dbl!!, map["lng"].dbl!!) }.getOrNull()?.takeIf { Geo.inRiyadh(it) }
            val infoAt = p.indexOf("\"commonInfo\":{\"postId\"")
            val description = if (infoAt >= 0) RscPayload.valueAfter(p, "\"description\":", infoAt)["content"].str else null
            val cpsAt = p.indexOf("\"cps\":[{\"fieldName\"")
            val fields = if (cpsAt >= 0) RscPayload.valueAfter(p, "\"cps\":", cpsAt).arr
                .mapNotNull { f -> f["fieldName"].str?.let { k -> f["value"].str?.let { k to it } } }.toMap() else emptyMap()
            val facts = Cps.fromFields(fields)
            return listing.copy(
                description = description ?: listing.description,
                photos = photos.ifEmpty { listing.photos },
                location = pin ?: listing.location,
                locationPrecision = if (pin != null) LocationPrecision.EXACT else listing.locationPrecision,
                bedrooms = facts.bedrooms ?: listing.bedrooms,
                bathrooms = facts.bathrooms ?: listing.bathrooms,
                areaSqm = facts.area ?: listing.areaSqm,
                floor = facts.floor ?: listing.floor,
                period = if (facts.period != RentPeriod.UNKNOWN) facts.period else listing.period,
                furnishing = if (facts.furnishing != Furnishing.UNKNOWN) facts.furnishing else listing.furnishing,
                buildingAgeYears = facts.ageYears ?: listing.buildingAgeYears,
                features = listing.features + facts.features,
            )
        }
    }

    /** The attribute chips OpenSooq shows ("٢ غرفتا نوم", "حمّامين", "مفروشة", "سنوي"). */
    internal data class Cps(
        val bedrooms: Int? = null,
        val bathrooms: Int? = null,
        val furnishing: Furnishing = Furnishing.UNKNOWN,
        val period: RentPeriod = RentPeriod.UNKNOWN,
        val area: Double? = null,
        val floor: Int? = null,
        val ageYears: Int? = null,
        val features: Set<String> = emptySet(),
    ) {
        companion object {
            private val floors = listOf("الارضي", "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
            private val floorsEn = listOf("ground", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")

            private fun count(t: String, one: String, two: String): Int? {
                val n = Regex("\\d+").find(t)?.value?.toIntOrNull()
                return when {
                    t.contains("اكثر من") -> (n ?: 6) + 1
                    n != null -> n
                    t.contains(two) -> 2
                    t.contains(one) -> 1
                    else -> null
                }
            }

            private fun age(t: String): Int? {
                if (t.contains("شهر") || t.contains("month")) return 0
                return Regex("\\d+").find(t)?.value?.toIntOrNull()
            }

            fun fromLabels(labels: List<String>): Cps {
                var c = Cps()
                for (raw in labels) {
                    val t = ArabicText.normalize(raw)
                    c = when {
                        t.contains("غرف") && !t.contains("حمام") -> c.copy(bedrooms = count(t, "غرفه", "غرفتا"))
                        t.contains("حمام") -> c.copy(bathrooms = count(t, "حمام", "حمامين"))
                        t.contains("غير مفروش") -> c.copy(furnishing = Furnishing.UNFURNISHED)
                        t.contains("جزي") -> c.copy(furnishing = Furnishing.SEMI_FURNISHED)
                        t.contains("مفروش") -> c.copy(furnishing = Furnishing.FURNISHED)
                        t.contains("شهري") -> c.copy(period = RentPeriod.MONTHLY)
                        t.contains("سنوي") -> c.copy(period = RentPeriod.YEARLY)
                        t.contains("يومي") -> c.copy(period = RentPeriod.DAILY)
                        t.contains("المساحه") -> c.copy(area = Regex("\\d+(?:\\.\\d+)?").find(t)?.value?.toDoubleOrNull())
                        t.contains("الطابق") -> c.copy(floor = floors.indexOfFirst { t.contains(it) }.takeIf { it >= 0 })
                        t.contains("سنوات") || t.contains("سنه") || t.contains("شهر") -> c.copy(ageYears = age(t))
                        else -> c
                    }
                }
                return c
            }

            fun fromFields(f: Map<String, String>): Cps {
                val features = mutableSetOf<String>()
                val amenities = (f["Main_amenities"].orEmpty() + ":" + f["Additional_amenities"].orEmpty()).lowercase()
                if ("elevator" in amenities) features += Feature.ELEVATOR
                if ("air conditioning" in amenities) features += Feature.AC
                if ("parking" in amenities || "garage" in amenities) features += Feature.PARKING
                if ("balcony" in amenities) features += Feature.BALCONY
                if ("pool" in amenities) features += Feature.POOL
                if ("maid" in amenities) features += Feature.MAID_ROOM
                val floor = f["Floors"]?.lowercase()?.let { fl -> floorsEn.indexOfFirst { fl.startsWith(it) }.takeIf { it >= 0 } }
                return Cps(
                    bedrooms = f["Rooms"]?.let { if (it.contains("+") || it.contains("more", true)) 7 else it.filter(Char::isDigit).toIntOrNull() },
                    bathrooms = f["Bathrooms"]?.let { if (it.contains("+") || it.contains("more", true)) 7 else it.filter(Char::isDigit).toIntOrNull() },
                    furnishing = when (f["Furnished_RealEstate"]?.lowercase()) {
                        "furnished" -> Furnishing.FURNISHED
                        "unfurnished" -> Furnishing.UNFURNISHED
                        null -> Furnishing.UNKNOWN
                        else -> if (f["Furnished_RealEstate"]!!.contains("semi", true) || f["Furnished_RealEstate"]!!.contains("partly", true)) Furnishing.SEMI_FURNISHED else Furnishing.UNKNOWN
                    },
                    period = when (f["RentalPeriod"]?.lowercase()) {
                        "monthly" -> RentPeriod.MONTHLY
                        "yearly", "annually" -> RentPeriod.YEARLY
                        "daily" -> RentPeriod.DAILY
                        else -> RentPeriod.UNKNOWN
                    },
                    area = f["Surface"]?.let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() },
                    floor = floor,
                    ageYears = (f["Age_RealEstate_for_rent"] ?: f["Age_RealEstate"])?.let { age(it.lowercase()) },
                    features = features,
                )
            }
        }
    }
}
