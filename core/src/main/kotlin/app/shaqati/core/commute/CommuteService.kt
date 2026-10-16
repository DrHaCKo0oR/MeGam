package app.shaqati.core.commute

import app.shaqati.core.model.GeoPoint
import app.shaqati.core.util.Geo
import kotlinx.serialization.Serializable
import java.time.Instant

/** Cached forecast for one home cell. */
@Serializable
data class CommuteCacheEntry(val key: String, val estimate: CommuteEstimate)

/**
 * Computes morning (home → work) and evening (work → home) drive times for
 * many homes at once, reusing cached results.
 */
class CommuteService(
    private val google: GoogleRoutesClient?,
    private val schedule: CommuteSchedule = CommuteSchedule(),
    private val clock: () -> Instant = Instant::now,
) {
    /** Keyed by [cacheKey]. Callers persist and restore this map. */
    val cache: MutableMap<String, CommuteEstimate> = linkedMapOf()

    private var correctionMorning = Correction()
    private var correctionEvening = Correction()

    val corrections: Pair<Correction, Correction> get() = correctionMorning to correctionEvening

    fun cacheKey(home: GeoPoint, work: GeoPoint): String =
        "${Geo.cellKey(home, 3)}>${Geo.cellKey(work, 3)}>${schedule.morningDeparture}>${schedule.eveningDeparture}"

    /**
     * Learns the correction from the user's real trip. Returns the Google forecast
     * for that trip (morning, evening) or null when no traffic service is set.
     */
    suspend fun calibrate(trip: CalibrationTrip, work: GeoPoint): Pair<Int?, Int?>? {
        if (!trip.enabled) {
            correctionMorning = Correction(); correctionEvening = Correction(); return null
        }
        val g = google ?: return null
        val now = clock()
        val am = g.matrix(listOf(trip.home), listOf(work), schedule.next(schedule.morningDeparture, now))[0 to 0]?.minutes
        val pm = g.matrix(listOf(work), listOf(trip.home), schedule.next(schedule.eveningDeparture, now))[0 to 0]?.minutes
        correctionMorning = am?.let { Correction.learn(trip.actualMorningMinutes, it) } ?: Correction()
        correctionEvening = pm?.let { Correction.learn(trip.actualEveningMinutes, it) } ?: Correction()
        return am to pm
    }

    fun setCorrections(morning: Correction, evening: Correction) {
        correctionMorning = morning
        correctionEvening = evening
    }

    /** Estimates for [homes]; failed lookups fall back to the approximate model. */
    suspend fun estimate(homes: List<GeoPoint>, work: GeoPoint, maxAgeDays: Int = 30): Map<GeoPoint, CommuteEstimate> {
        val now = clock()
        val result = linkedMapOf<GeoPoint, CommuteEstimate>()
        val todo = mutableListOf<GeoPoint>()
        for (h in homes.distinct()) {
            val cached = cache[cacheKey(h, work)]
            val fresh = cached != null && now.toEpochMilli() - cached.computedAt < maxAgeDays * 86_400_000L &&
                (google == null || cached.method == CommuteMethod.GOOGLE_TRAFFIC)
            if (fresh) result[h] = cached!!.withCorrection() else todo += h
        }
        if (todo.isEmpty()) return result

        val g = google
        if (g == null) {
            for (h in todo) result[h] = approximate(h, work, now).also { cache[cacheKey(h, work)] = it }
            return result
        }
        val amAt = schedule.next(schedule.morningDeparture, now)
        val pmAt = schedule.next(schedule.eveningDeparture, now)
        for (chunk in todo.chunked(GoogleRoutesClient.MAX_ELEMENTS)) {
            val am = runCatching { g.matrix(chunk, listOf(work), amAt) }.getOrNull()
            val pm = runCatching { g.matrix(listOf(work), chunk, pmAt) }.getOrNull()
            chunk.forEachIndexed { i, h ->
                val a = am?.get(i to 0)
                val p = pm?.get(0 to i)
                val e = if (a == null && p == null) {
                    approximate(h, work, now)
                } else {
                    CommuteEstimate(
                        morningMinutes = null,
                        eveningMinutes = null,
                        morningRawMinutes = a?.minutes,
                        eveningRawMinutes = p?.minutes,
                        roadKm = (a?.meters ?: p?.meters)?.let { it / 1000.0 },
                        method = CommuteMethod.GOOGLE_TRAFFIC,
                        computedAt = now.toEpochMilli(),
                        morningDeparture = amAt.toEpochMilli(),
                        eveningDeparture = pmAt.toEpochMilli(),
                    )
                }
                cache[cacheKey(h, work)] = e
                result[h] = e.withCorrection()
            }
        }
        return result
    }

    private fun approximate(h: GeoPoint, work: GeoPoint, now: Instant): CommuteEstimate {
        val m = ApproximateCommute.minutes(h, work)
        return CommuteEstimate(
            morningMinutes = m,
            eveningMinutes = m,
            morningRawMinutes = m,
            eveningRawMinutes = m,
            roadKm = ApproximateCommute.roadKm(h, work),
            method = CommuteMethod.APPROXIMATE,
            computedAt = now.toEpochMilli(),
            morningDeparture = null,
            eveningDeparture = null,
        )
    }

    /** Google forecasts are corrected; the approximate model is already calibrated. */
    private fun CommuteEstimate.withCorrection(): CommuteEstimate =
        if (method != CommuteMethod.GOOGLE_TRAFFIC) this
        else copy(
            morningMinutes = correctionMorning.apply(morningRawMinutes),
            eveningMinutes = correctionEvening.apply(eveningRawMinutes),
        )
}
