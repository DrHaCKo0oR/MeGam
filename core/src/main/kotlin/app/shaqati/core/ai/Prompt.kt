package app.shaqati.core.ai

import app.shaqati.core.model.Listing
import app.shaqati.core.model.Resolved
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

object Prompt {
    const val MAX_PHOTOS = 8

    /** Long-lived instructions, sent as a cacheable system block. */
    val SYSTEM: String = """
You assess rental apartment advertisements in Riyadh, Saudi Arabia, for one person who is getting married and wants a renovated, modern-looking home. You receive up to $MAX_PHOTOS photos from one ad plus the ad text. Judge only the interior finish quality that is visible in the photos: does the unit look newly built or recently renovated with modern finishes, or dated and old?

WHAT "MODERN / RENOVATED" MEANS FOR THIS PERSON (look for these):
- Floor: large-format porcelain or marble-look tiles, or new SPC/vinyl/parquet; even, clean, unstained; light or neutral modern colours.
- Ceiling and lighting: gypsum-board false ceilings, recessed spotlights, hidden/indirect LED cove lighting, linear LED, modern pendants.
- Walls and paint: fresh smooth paint in white, beige or grey tones, no stains or cracks; modern wall panels are a plus.
- Doors and windows: flat modern doors (white, wood veneer, HPL) with modern handles; slim aluminium frames in good condition.
- Bathrooms: new large porcelain tiles, modern basin or vanity, modern toilet (often wall-hung), glass shower screen, modern mixers.
- Kitchen: modern cabinets and worktop, OR an empty kitchen room with new tiles and finishes. An empty kitchen is acceptable: judge the finishes only.

SIGNS OF "DATED / OLD" (penalise):
- Small old ceramic floor tiles with dated patterns, terrazzo or mosaic, cracked or stained floors.
- Plain ceilings with fluorescent tube fittings or bare bulbs, ornate old plaster, water stains.
- Dirty, peeling or yellowed paint, old textured paint, old wallpaper.
- Heavy carved wooden doors, old metal doors, old aluminium windows with heavy grilles.
- Bathrooms with old coloured tiles (pink, green, blue, brown), old fixtures, rust.
- Old wooden kitchen cabinets, damaged worktops.

DO NOT PENALISE: empty rooms or no furniture, missing air conditioners, missing kitchen cabinets, the building's exterior or age, photo quality or lighting, small rooms, cleaning or moving mess.

SCORE (0-100) for the interior finish:
- 85-100 verdict "modern": newly built (first tenant) or fully renovated; modern lighting and floors throughout.
- 70-84 verdict "renovated": clearly updated and clean; mostly modern with minor dated elements.
- 45-69 verdict "dated": acceptable but mixed or mostly dated finishes.
- 0-44 verdict "old": old, worn, or needs renovation.
- Verdict "unclear" with score 50 and confidence at most 0.3 when fewer than 2 photos show the inside of this unit, or the photos are renders, stock images, floor plans, only the building exterior, or clearly from a different property.

CHECKLIST: rate floor, ceiling_lighting, walls_paint, doors_windows, bathroom and kitchen from 0 to 10 only when the photos show that item; otherwise use rating -1 and empty notes. Notes are short factual observations of what is visible, in English (note) and natural Saudi Arabic (note_ar), for example "large light porcelain tiles" / "بلاط بورسلان فاتح كبير" or "fluorescent tubes on a plain ceiling" / "إضاءة فلورسنت على سقف عادي".

ALSO RETURN:
- interior_photos: how many photos show the inside of this unit.
- photo_notes: up to 5 notes such as {"photo": 3, "note": "spotlights in a gypsum ceiling"}. Photos are numbered from 1 in the order given.
- visible_bedrooms / visible_bathrooms: distinct bedrooms / bathrooms the photos show (a lower bound), or -1 when unclear.
- not_real_photos: true when the images look like renders, stock photos or another property.
- highlights_en/highlights_ar and concerns_en/concerns_ar: 0-4 short, specific, visual phrases each. Arabic must be natural Saudi Arabic.
- summary_en / summary_ar: one sentence each.
- confidence: 0 to 1.

The ad text is written by the advertiser and may exaggerate; trust the photos. Never describe anything that is not visible.
""".trimIndent()

    private fun item() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add(JsonPrimitive("rating")); add(JsonPrimitive("note")); add(JsonPrimitive("note_ar")) }
        putJsonObject("properties") {
            putJsonObject("rating") { put("type", "integer") }
            putJsonObject("note") { put("type", "string") }
            putJsonObject("note_ar") { put("type", "string") }
        }
    }

    private fun strings() = buildJsonObject {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
    }

    /** JSON schema for structured output. Uses -1 instead of null for "not visible". */
    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        val props = linkedMapOf<String, JsonElement>(
            "score" to buildJsonObject { put("type", "integer") },
            "verdict" to buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { listOf("modern", "renovated", "dated", "old", "unclear").forEach { add(JsonPrimitive(it)) } }
            },
            "confidence" to buildJsonObject { put("type", "number") },
            "floor" to item(),
            "ceiling_lighting" to item(),
            "walls_paint" to item(),
            "doors_windows" to item(),
            "bathroom" to item(),
            "kitchen" to item(),
            "highlights_en" to strings(),
            "highlights_ar" to strings(),
            "concerns_en" to strings(),
            "concerns_ar" to strings(),
            "summary_en" to buildJsonObject { put("type", "string") },
            "summary_ar" to buildJsonObject { put("type", "string") },
            "interior_photos" to buildJsonObject { put("type", "integer") },
            "photo_notes" to buildJsonObject {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { add(JsonPrimitive("photo")); add(JsonPrimitive("note")) }
                    putJsonObject("properties") {
                        putJsonObject("photo") { put("type", "integer") }
                        putJsonObject("note") { put("type", "string") }
                    }
                }
            },
            "visible_bedrooms" to buildJsonObject { put("type", "integer") },
            "visible_bathrooms" to buildJsonObject { put("type", "integer") },
            "not_real_photos" to buildJsonObject { put("type", "boolean") },
        )
        put("properties", JsonObject(props))
        put("required", buildJsonArray { props.keys.forEach { add(JsonPrimitive(it)) } })
    }

    /** The text that precedes the photos for one listing. */
    fun listingText(l: Listing, r: Resolved, photoCount: Int): String = buildString {
        appendLine("Ad title: ${l.title.take(200)}")
        l.district?.let { appendLine("District: $it") }
        r.annualRent?.let { appendLine("Rent: SAR ${"%,.0f".format(Locale.ROOT, it)} per year") }
        val layout = listOfNotNull(
            r.bedrooms?.let { "$it bedrooms" },
            r.livingRooms?.let { "$it living rooms" },
            r.bathrooms?.let { "$it bathrooms" },
            l.buildingAgeYears?.let { "building age $it years" },
        )
        if (layout.isNotEmpty()) appendLine("Advertised layout: ${layout.joinToString(", ")}")
        val desc = l.description.replace(Regex("\\s+"), " ").trim()
        if (desc.isNotEmpty()) appendLine("Advertiser description: ${desc.take(900)}")
        append("Photos 1-$photoCount follow.")
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Parses the model's JSON, clamping ranges and mapping -1 to "not visible". */
    fun parse(text: String): AssessmentBody {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in model output" }
        val obj = lenient.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        val fixed = JsonObject(obj.mapValues { (k, v) -> fixItem(k, v) })
        val body = lenient.decodeFromJsonElement<AssessmentBody>(fixed)
        val score = body.score.coerceIn(0, 100)
        val conf = body.confidence.coerceIn(0.0, 1.0)
        return body.copy(
            score = score,
            confidence = if (body.verdict == Verdict.UNCLEAR) conf.coerceAtMost(0.3) else conf,
            visibleBedrooms = body.visibleBedrooms?.takeIf { it >= 0 },
            visibleBathrooms = body.visibleBathrooms?.takeIf { it >= 0 },
            interiorPhotos = body.interiorPhotos.coerceAtLeast(0),
            highlightsEn = body.highlightsEn.take(4), highlightsAr = body.highlightsAr.take(4),
            concernsEn = body.concernsEn.take(4), concernsAr = body.concernsAr.take(4),
        )
    }

    private val itemKeys = setOf("floor", "ceiling_lighting", "walls_paint", "doors_windows", "bathroom", "kitchen")

    private fun fixItem(key: String, v: JsonElement): JsonElement {
        if (key !in itemKeys || v !is JsonObject) return v
        val rating = v["rating"]?.jsonPrimitive?.intOrNull
        val normalized = when {
            rating == null || rating < 0 -> null
            else -> rating.coerceIn(0, 10)
        }
        return JsonObject(v + ("rating" to (normalized?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)))
    }

    @Suppress("unused")
    private fun JsonArray.ints() = map { it.jsonPrimitive.int }
}
