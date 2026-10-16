package app.shaqati.core.rank

import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.Verdict
import app.shaqati.core.commute.CommuteEstimate
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.Feature
import app.shaqati.core.model.Listing
import app.shaqati.core.model.Resolved
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Breakdown of the match score shown in the app. Each part is 0–100. */
@Serializable
data class MatchScore(
    val total: Int,
    val finish: Int,
    val finishSource: FinishSource,
    val commute: Int,
    val price: Int,
    val space: Int,
    val freshness: Int,
)

@Serializable
enum class FinishSource { AI, TEXT, NONE }

object Ranker {
    /**
     * Interior finish dominates because it is what the user cannot filter for on
     * other apps; commute and price follow.
     */
    private const val W_FINISH = 0.45
    private const val W_COMMUTE = 0.22
    private const val W_PRICE = 0.15
    private const val W_SPACE = 0.10
    private const val W_FRESH = 0.08

    fun score(
        l: Listing,
        r: Resolved,
        c: Criteria,
        commute: CommuteEstimate?,
        ai: AiAssessment?,
        now: Long,
        userBoost: Int = 0,
    ): MatchScore {
        val (finish, source) = finish(l, r, ai)
        val commuteScore = commute?.morningMinutes?.let { m ->
            val evening = commute.eveningMinutes ?: m
            val avg = (m * 0.6 + evening * 0.4)
            lerp(avg, best = 12.0, worst = c.maxMorningMinutes + 15.0)
        } ?: 45
        val priceScore = r.annualRent?.let { lerp(it, best = c.maxAnnualRent * 0.6, worst = c.maxAnnualRent * 1.02) } ?: 40
        val spaceScore = space(r, l)
        val stamp = l.updatedAt ?: l.postedAt
        val fresh = stamp?.let { lerp((now - it) / 86_400_000.0, best = 1.0, worst = 45.0) } ?: 50

        val total = (finish * W_FINISH + commuteScore * W_COMMUTE + priceScore * W_PRICE + spaceScore * W_SPACE + fresh * W_FRESH)
            .roundToInt() + userBoost
        return MatchScore(total.coerceIn(0, 100), finish, source, commuteScore, priceScore, spaceScore, fresh)
    }

    /** Finish quality from AI when available, otherwise a conservative text-based estimate. */
    fun finish(l: Listing, r: Resolved, ai: AiAssessment?): Pair<Int, FinishSource> {
        if (ai != null && ai.body.verdict != Verdict.UNCLEAR && !ai.body.notRealPhotos) {
            return ai.body.score to FinishSource.AI
        }
        val t = r.text
        val hasSignals = t.positive.isNotEmpty() || t.negative.isNotEmpty() || l.buildingAgeYears != null
        if (!hasSignals) return 40 to FinishSource.NONE
        var s = 45 + t.renovationScore * 5
        l.buildingAgeYears?.let { age ->
            s += when {
                age <= 1 -> 15
                age <= 3 -> 8
                age <= 6 -> 3
                age >= 20 -> -5
                else -> 0
            }
        }
        if (t.firstTenant) s += 5
        // Text alone never earns a top score: advertisers exaggerate.
        return s.coerceIn(10, 78) to FinishSource.TEXT
    }

    private fun space(r: Resolved, l: Listing): Int {
        var s = 40
        val rooms = (r.bedrooms ?: 0) + if (r.hasMajlis) 1 else 0
        s += when {
            rooms >= 4 -> 30
            rooms == 3 -> 20
            rooms == 2 -> 5
            else -> 0
        }
        s += when {
            (r.bathrooms ?: 0) >= 3 -> 12
            (r.bathrooms ?: 0) == 2 -> 5
            else -> 0
        }
        l.areaSqm?.let { a -> s += when { a >= 180 -> 15; a >= 140 -> 10; a >= 110 -> 5; else -> 0 } }
        if (r.kitchenFitted == true) s += 5
        if (Feature.PRIVATE_ENTRANCE in r.features) s += 3
        return s.coerceIn(0, 100)
    }

    /** 100 at [best], 0 at [worst], linear between. Works for either direction. */
    private fun lerp(v: Double, best: Double, worst: Double): Int {
        val t = (v - best) / (worst - best)
        return ((1 - t.coerceIn(0.0, 1.0)) * 100).roundToInt()
    }
}
