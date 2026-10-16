package app.shaqati.core.sources

import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.util.Geo
import app.shaqati.core.util.HttpRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Haraj (haraj.com.sa) through the same GraphQL API its website uses. Posts are free text;
 * the REGA licence block and map link in the body supply price, rooms and location.
 */
class HarajSource : ListingSource {
    override val source = Source.HARAJ

    override suspend fun search(ctx: SearchContext): SearchResult {
        val out = LinkedHashMap<String, Listing>()
        val errors = mutableListOf<String>()
        var pages = 0
        val queries = mutableListOf<Map<String, Any>>()
        // Newest Riyadh apartment and floor posts, then district tags around work.
        for (tag in listOf("شقق للايجار", "ادوار للايجار")) {
            for (page in 1..ctx.maxPages) queries += mapOf("tag" to tag, "cities" to listOf("الرياض"), "page" to page, "limit" to 50)
        }
        for (d in Districts.near(ctx.area.center, ctx.area.radiusKm).take(MAX_DISTRICT_TAGS)) {
            queries += mapOf("tag" to "شقق للايجار في حي ${d.ar} في الرياض", "page" to 1, "limit" to 50)
        }
        for (q in queries) {
            val resp = runCatching { post(ctx, q) }.getOrElse { errors += it.message ?: "error"; null } ?: continue
            pages++
            if (resp.status == 403 || resp.status == 429) return SearchResult(source, out.values.toList(), pages, errors + "HTTP ${resp.status}", blocked = true)
            if (!resp.ok) { errors += "HTTP ${resp.status}"; continue }
            val items = parse(resp.body)
            items.filter { it.location == null || Geo.distanceKm(it.location, ctx.area.center) <= ctx.area.radiusKm * 1.5 }
                .forEach { out[it.id] = it }
        }
        return SearchResult(source, out.values.toList(), pages, errors.distinct().take(5))
    }

    private suspend fun post(ctx: SearchContext, vars: Map<String, Any>) = ctx.http.send(
        HttpRequest(
            url = ENDPOINT,
            method = "POST",
            headers = mapOf("Origin" to "https://haraj.com.sa", "Referer" to "https://haraj.com.sa/"),
            contentType = "application/json",
            body = buildJsonObject {
                put("query", QUERY)
                put("variables", buildJsonObject {
                    for ((k, v) in vars) when (v) {
                        is String -> put(k, v)
                        is Int -> put(k, v)
                        is Boolean -> put(k, v)
                        is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
                    }
                })
            }.toString(),
        )
    )

    companion object {
        private const val MAX_DISTRICT_TAGS = 18
        const val ENDPOINT = "https://graphql.haraj.com.sa/?queryName=posts&clientId=xJHIubOo-8gzA-Nez7-aKHw-ehgDyO8pibQyv3&version=N0.0.1%20,%202026-10-11%2023/"

        val QUERY = """
            query FetchAds(${'$'}id: [Int] = null, ${'$'}city: String = null, ${'$'}cities: [String], ${'$'}page: Int = null, ${'$'}limit: Int = null, ${'$'}tag: String = null, ${'$'}onlyWithImage: Boolean = null) {
              posts(id: ${'$'}id, city: ${'$'}city, cities: ${'$'}cities, page: ${'$'}page, limit: ${'$'}limit, tag: ${'$'}tag, onlyWithImage: ${'$'}onlyWithImage) {
                items { id title postDate updateDate authorUsername URL bodyTEXT city geoNeighborhood geoHash tags imagesList hasVideo status postType
                  price { formattedPrice inputPrice }
                  realEstateInfo { re_REGA_Advertiser_registration_number re_REGA_Authorization_number } }
                pageInfo { hasNextPage }
              }
            }
        """.trimIndent()

        fun parse(body: String): List<Listing> {
            val root = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
            return root["data"]["posts"]["items"].arr.mapNotNull { runCatching { toListing(it) }.getOrNull() }
        }

        private val regaPrice = Regex("""سعر الوحد[ةه]\s*:\s*([\d.,]+)""")
        private val regaRooms = Regex("""عدد الغرف\s*:\s*(\d+)""")
        private val regaArea = Regex("""مساحة العقار\s*:\s*([\d.]+)""")
        private val regaPurpose = Regex("""غرض الإعلان\s*:\s*(\S+)""")
        private val regaType = Regex("""نوع العقار\s*:\s*([^\n]+)""")

        internal fun toListing(o: JsonElement): Listing? {
            val id = o["id"].lng ?: return null
            val title = o["title"].str ?: ""
            if (WantedAd.matches(title)) return null
            if (o["status"].bool == false) return null
            val body = o["bodyTEXT"].str ?: ""
            // Posts whose licence says "sale" are not rentals.
            val purpose = regaPurpose.find(body)?.groupValues?.get(1)?.lowercase()
            if (purpose != null && (purpose.startsWith("sell") || purpose.startsWith("بيع"))) return null
            val type = regaType.find(body)?.groupValues?.get(1)?.lowercase().orEmpty()
            if (type.contains("villa") || type.contains("land") || type.contains("عمارة") || type.contains("أرض") || type.contains("ارض")) return null

            val textPrice = PriceText.extract(title + "\n" + body)
            val rega = regaPrice.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val price = textPrice?.amount ?: rega
            val period = textPrice?.period ?: RentPeriod.UNKNOWN

            val mapPoint = MapLink.find(body)
            val hashPoint = o["geoHash"].str?.let { Geohash.decode(it) }?.takeIf { Geo.inRiyadh(it) }
            val point = mapPoint ?: hashPoint
            val kind = when {
                o["tags"].arr.any { it.str?.contains("ادوار") == true } || title.contains("دور") -> PropertyKind.FLOOR
                title.contains("ملحق") -> PropertyKind.ANNEX
                title.contains("استديو") || title.contains("استوديو") || title.contains("ستديو") -> PropertyKind.STUDIO
                else -> PropertyKind.APARTMENT
            }
            val path = o["URL"].str ?: "$id"
            return Listing(
                source = Source.HARAJ,
                sourceId = id.toString(),
                url = "https://haraj.com.sa/" + path.trimStart('/'),
                title = title,
                description = body,
                kind = kind,
                price = price,
                period = period,
                bedrooms = regaRooms.find(body)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..8 },
                areaSqm = regaArea.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                city = o["city"].str,
                district = o["geoNeighborhood"].str,
                location = point,
                locationPrecision = when {
                    mapPoint != null -> LocationPrecision.EXACT
                    hashPoint != null -> LocationPrecision.APPROXIMATE
                    else -> LocationPrecision.UNKNOWN
                },
                photos = o["imagesList"].arr.mapNotNull { it.str },
                advertiserName = o["authorUsername"].str,
                licenseNumber = o["realEstateInfo"]["re_REGA_Authorization_number"].str,
                postedAt = o["postDate"].lng?.times(1000),
                updatedAt = o["updateDate"].lng?.times(1000),
            )
        }
    }
}
