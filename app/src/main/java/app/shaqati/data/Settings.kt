package app.shaqati.data

import app.shaqati.core.commute.CalibrationTrip
import app.shaqati.core.commute.CommuteSchedule
import app.shaqati.core.commute.Correction
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Source
import app.shaqati.core.sources.Districts
import app.shaqati.core.sources.RiyadhNorth
import kotlinx.serialization.Serializable

/** Everything the user can change. Stored as one JSON document. */
@Serializable
data class Settings(
    val work: GeoPoint = RiyadhNorth.DEFAULT_WORK,
    val workLabel: String = "",
    /** False until the user confirms or moves the work pin. */
    val workConfirmed: Boolean = false,
    val searchRadiusKm: Double = 9.0,
    val criteria: Criteria = Criteria(),
    val schedule: CommuteSchedule = CommuteSchedule(),
    val calibration: CalibrationTrip = CalibrationTrip(home = defaultCalibrationHome()),
    val corrections: LearnedCorrections? = null,
    val enabledSources: Set<Source> = SUPPORTED_SOURCES,
    /** Sites the settings have offered so far; a site added in an update starts switched on. */
    val sourcesOffered: Set<Source> = FIRST_SOURCES,
    val maxPages: Int = 5,
    /** 0 turns background refresh off. */
    val autoRefreshHours: Int = 6,
    val ai: AiSettings = AiSettings(),
    /** "" follows the phone; otherwise "ar" or "en". */
    val language: String = "",
    val onboarded: Boolean = false,
) {
    /** Switches on sites added since these settings were saved; sites the user turned off stay off. */
    fun withNewSources(): Settings {
        val added = SUPPORTED_SOURCES - sourcesOffered
        return if (added.isEmpty()) this else copy(enabledSources = enabledSources + added, sourcesOffered = SUPPORTED_SOURCES)
    }

    companion object {
        /** Sites the app can read. Bayut and Wasalt are read in a browser on the phone. */
        val SUPPORTED_SOURCES = setOf(
            Source.AQAR, Source.BAYUT, Source.WASALT, Source.HARAJ, Source.PROPERTY_FINDER, Source.DEAL, Source.OPENSOOQ,
        )

        /** The sites of the first versions, before Bayut and Wasalt. */
        val FIRST_SOURCES = setOf(Source.AQAR, Source.HARAJ, Source.PROPERTY_FINDER, Source.DEAL, Source.OPENSOOQ)

        /** Centre of the user's current district, refined in Settings. */
        fun defaultCalibrationHome(): GeoPoint =
            Districts.find("المربع")?.center ?: GeoPoint(24.6608, 46.7066)
    }
}

@Serializable
data class AiSettings(
    val enabled: Boolean = true,
    /** Message Batches: half price, results usually within an hour. */
    val useBatch: Boolean = true,
    val monthlyCapUsd: Double = 5.0,
    val photosPerListing: Int = 6,
    val maxPerRefresh: Int = 25,
)

/** Correction learned from the calibration trip, with the forecasts it was learned from. */
@Serializable
data class LearnedCorrections(
    val morning: Correction,
    val evening: Correction,
    val forecastMorning: Int?,
    val forecastEvening: Int?,
    val learnedAt: Long,
    /** Work pin, home pin and trip times the correction belongs to. */
    val signature: String,
)
