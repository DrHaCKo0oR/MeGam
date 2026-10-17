package app.shaqati

import app.shaqati.core.model.Source
import app.shaqati.data.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun defaultsMatchTheAgreedSearch() {
        val s = Settings()
        assertEquals(50_000.0, s.criteria.maxAnnualRent, 0.0)
        assertEquals(40, s.criteria.maxMorningMinutes)
        assertEquals(2, s.criteria.minBathrooms)
        assertEquals("08:00", s.schedule.morningDeparture)
        assertEquals("17:00", s.schedule.eveningDeparture)
        assertEquals(75, s.calibration.actualMorningMinutes)
        assertEquals(Settings.SUPPORTED_SOURCES, s.withNewSources().enabledSources)
        // The calibration home defaults to the Al Murabba district centre.
        assertEquals(24.66, s.calibration.home.lat, 0.02)
    }

    @Test
    fun survivesAJsonRoundTrip() {
        val s = Settings(workLabel = "Office", onboarded = true, autoRefreshHours = 12)
        assertEquals(s, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), s)))
    }

    @Test
    fun sitesAddedInAnUpdateStartSwitchedOn() {
        // Saved by an earlier version, with OpenSooq turned off.
        val old = json.decodeFromString(
            Settings.serializer(),
            """{"enabledSources":["AQAR","HARAJ","PROPERTY_FINDER","DEAL"],"onboarded":true}""",
        ).withNewSources()
        assertEquals(setOf(Source.AQAR, Source.HARAJ, Source.PROPERTY_FINDER, Source.DEAL, Source.BAYUT, Source.WASALT), old.enabledSources)
        assertEquals(Settings.SUPPORTED_SOURCES, old.sourcesOffered)
        // Turning Bayut off afterwards sticks.
        val off = old.copy(enabledSources = old.enabledSources - Source.BAYUT)
        val again = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), off)).withNewSources()
        assertTrue(Source.BAYUT !in again.enabledSources)
    }

    @Test
    fun readsOlderOrNewerDocuments() {
        val s = json.decodeFromString(Settings.serializer(), """{"work":{"lat":24.77,"lng":46.68},"somethingNew":true}""")
        assertEquals(24.77, s.work.lat, 0.0)
        assertEquals(50_000.0, s.criteria.maxAnnualRent, 0.0)
    }
}
