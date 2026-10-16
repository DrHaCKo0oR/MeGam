package app.shaqati.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Verdict {
    @SerialName("modern") MODERN,
    @SerialName("renovated") RENOVATED,
    @SerialName("dated") DATED,
    @SerialName("old") OLD,
    @SerialName("unclear") UNCLEAR,
}

/** One checklist item. [rating] is 0–10, or null when the photos do not show it. */
@Serializable
data class ItemRating(val rating: Int? = null, val note: String = "")

@Serializable
data class PhotoNote(val photo: Int, val note: String)

/** The model's structured answer. Field names match the JSON schema sent to the API. */
@Serializable
data class AssessmentBody(
    val score: Int,
    val verdict: Verdict,
    val confidence: Double,
    val floor: ItemRating = ItemRating(),
    @SerialName("ceiling_lighting") val ceilingLighting: ItemRating = ItemRating(),
    @SerialName("walls_paint") val wallsPaint: ItemRating = ItemRating(),
    @SerialName("doors_windows") val doorsWindows: ItemRating = ItemRating(),
    val bathroom: ItemRating = ItemRating(),
    val kitchen: ItemRating = ItemRating(),
    @SerialName("highlights_en") val highlightsEn: List<String> = emptyList(),
    @SerialName("highlights_ar") val highlightsAr: List<String> = emptyList(),
    @SerialName("concerns_en") val concernsEn: List<String> = emptyList(),
    @SerialName("concerns_ar") val concernsAr: List<String> = emptyList(),
    @SerialName("summary_en") val summaryEn: String = "",
    @SerialName("summary_ar") val summaryAr: String = "",
    @SerialName("interior_photos") val interiorPhotos: Int = 0,
    @SerialName("photo_notes") val photoNotes: List<PhotoNote> = emptyList(),
    @SerialName("visible_bedrooms") val visibleBedrooms: Int? = null,
    @SerialName("visible_bathrooms") val visibleBathrooms: Int? = null,
    @SerialName("not_real_photos") val notRealPhotos: Boolean = false,
)

/** A stored assessment: the body plus what it cost and which photos it saw. */
@Serializable
data class AiAssessment(
    val listingId: String,
    val body: AssessmentBody,
    val model: String,
    val photosUsed: List<String>,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val assessedAt: Long,
    val viaBatch: Boolean,
)

/** Sonnet 5.5 list prices per million tokens; batch requests are half price. */
object Pricing {
    const val MODEL = "claude-sonnet-5-5"
    private const val INPUT = 2.00
    private const val OUTPUT = 10.00
    private const val CACHE_WRITE = 2.50
    private const val CACHE_READ = 0.20

    fun cost(input: Long, output: Long, cacheWrite: Long, cacheRead: Long, batch: Boolean): Double {
        val usd = (input * INPUT + output * OUTPUT + cacheWrite * CACHE_WRITE + cacheRead * CACHE_READ) / 1_000_000.0
        return if (batch) usd / 2 else usd
    }

    /** Rough pre-flight estimate used to respect the monthly cap before sending. */
    fun estimate(photos: Int, batch: Boolean): Double = cost(
        input = 900L + photos * 420L,
        output = 700L,
        cacheWrite = 0,
        cacheRead = 2_000L,
        batch = batch,
    )
}
