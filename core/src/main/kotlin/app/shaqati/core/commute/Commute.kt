package app.shaqati.core.commute

import app.shaqati.core.model.GeoPoint
import app.shaqati.core.util.Geo
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")

@Serializable
enum class CommuteMethod { GOOGLE_TRAFFIC, APPROXIMATE, UNAVAILABLE }

/** Door-to-door drive times for one home location. Minutes are after calibration. */
@Serializable
data class CommuteEstimate(
    val morningMinutes: Int?,
    val eveningMinutes: Int?,
    val morningRawMinutes: Int?,
    val eveningRawMinutes: Int?,
    val roadKm: Double?,
    val method: CommuteMethod,
    val computedAt: Long,
    val morningDeparture: Long?,
    val eveningDeparture: Long?,
)

/** When the user drives. Defaults: leave home 08:00, leave work 17:00, Sunday–Thursday. */
@Serializable
data class CommuteSchedule(
    val morningDeparture: String = "08:00",
    val eveningDeparture: String = "17:00",
    val workdays: List<Int> = listOf(7, 1, 2, 3, 4), // ISO: Sun=7, Mon=1 … Thu=4
) {
    /**
     * The next workday at [time] that is at least [leadHours] in the future,
     * in Riyadh time. Google only forecasts traffic for future departures.
     */
    fun next(time: String, now: Instant, leadHours: Long = 2): Instant {
        val t = LocalTime.parse(time)
        var day: LocalDate = now.atZone(RIYADH).toLocalDate()
        repeat(14) {
            if (DayOfWeek.from(day).value in workdays) {
                val candidate = ZonedDateTime.of(day, t, RIYADH).toInstant()
                if (candidate.isAfter(now.plusSeconds(leadHours * 3600))) return candidate
            }
            day = day.plusDays(1)
        }
        error("no workday found in schedule $workdays")
    }
}

/**
 * The user's real trip today, used to correct forecasts to door-to-door reality.
 * Default: Al Murabba compound → work, about 75 min in the morning, 80 in the evening.
 */
@Serializable
data class CalibrationTrip(
    val home: GeoPoint,
    val actualMorningMinutes: Int = 75,
    val actualEveningMinutes: Int = 80,
    val enabled: Boolean = true,
)

/** Converts a forecast into a door-to-door estimate learned from the calibration trip. */
@Serializable
data class Correction(val factor: Double = 1.0, val fixedMinutes: Int = 0) {
    fun apply(raw: Int?): Int? = raw?.let { (it * factor + fixedMinutes).roundToInt() }

    companion object {
        private const val MAX_FIXED = 8
        private const val MIN_FACTOR = 0.8
        private const val MAX_FACTOR = 1.5

        /**
         * Up to [MAX_FIXED] minutes of the gap is treated as fixed door-to-door
         * overhead (parking, walking); the rest scales with drive time.
         */
        fun learn(actual: Int, forecast: Int): Correction {
            if (forecast <= 0 || actual <= 0) return Correction()
            val gap = actual - forecast
            val fixed = gap.coerceIn(0, MAX_FIXED)
            val factor = ((actual - fixed).toDouble() / forecast).coerceIn(MIN_FACTOR, MAX_FACTOR)
            return Correction(factor, fixed)
        }
    }
}

/**
 * Estimate used only when no traffic service is configured. Calibrated on
 * Riyadh rush hour: Al Murabba → Al Masif (~12 km straight line) takes ~75 min
 * at 08:00 and ~24 min on a weekend afternoon.
 */
object ApproximateCommute {
    private const val ROAD_FACTOR = 1.3
    private const val RUSH_SPEED_KMH = 14.0
    private const val OVERHEAD_MIN = 5.0

    fun roadKm(a: GeoPoint, b: GeoPoint): Double = Geo.distanceKm(a, b) * ROAD_FACTOR

    fun minutes(a: GeoPoint, b: GeoPoint): Int =
        (OVERHEAD_MIN + roadKm(a, b) / RUSH_SPEED_KMH * 60.0).roundToInt()
}
