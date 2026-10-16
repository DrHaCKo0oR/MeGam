package app.shaqati.core

import app.shaqati.core.commute.ApproximateCommute
import app.shaqati.core.commute.CommuteMethod
import app.shaqati.core.commute.CommuteSchedule
import app.shaqati.core.commute.CommuteService
import app.shaqati.core.commute.Correction
import app.shaqati.core.commute.GoogleRoutesClient
import app.shaqati.core.commute.RIYADH
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpResponse
import kotlinx.coroutines.test.runTest
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommuteTest {
    private val masif = GeoPoint(24.7655, 46.6802)
    private val murabba = GeoPoint(24.6629, 46.7071)

    @Test
    fun `next departure skips Friday and Saturday and is in the future`() {
        // Friday 16 Oct 2026, 15:00 Riyadh.
        val now = ZonedDateTime.of(2026, 10, 16, 15, 0, 0, 0, RIYADH).toInstant()
        val s = CommuteSchedule()
        val am = s.next("08:00", now).atZone(RIYADH)
        assertEquals(DayOfWeek.SUNDAY, am.dayOfWeek)
        assertEquals(8, am.hour)
        assertEquals(18, am.dayOfMonth)
        val pm = s.next("17:00", now).atZone(RIYADH)
        assertEquals(DayOfWeek.SUNDAY, pm.dayOfWeek)
        assertEquals(17, pm.hour)
    }

    @Test
    fun `same-day departure is used only with enough lead time`() {
        // Monday 07:30 → 08:00 is too close; next is Tuesday.
        val now = ZonedDateTime.of(2026, 10, 19, 7, 30, 0, 0, RIYADH).toInstant()
        val am = CommuteSchedule().next("08:00", now).atZone(RIYADH)
        assertEquals(DayOfWeek.TUESDAY, am.dayOfWeek)
        // Monday 05:00 → today 08:00 works.
        val early = ZonedDateTime.of(2026, 10, 19, 5, 0, 0, 0, RIYADH).toInstant()
        assertEquals(DayOfWeek.MONDAY, CommuteSchedule().next("08:00", early).atZone(RIYADH).dayOfWeek)
    }

    @Test
    fun `correction keeps short trips short and scales long ones`() {
        // Google says 55 for the 75-minute real trip.
        val c = Correction.learn(actual = 75, forecast = 55)
        assertEquals(8, c.fixedMinutes)
        assertEquals(75, c.apply(55))
        val short = c.apply(15)!!
        assertTrue(short in 20..30, "short trip $short")
        // Forecast already pessimistic: no fixed part, factor ≥ 0.8.
        val d = Correction.learn(actual = 40, forecast = 50)
        assertEquals(0, d.fixedMinutes)
        assertEquals(0.8, d.factor, 1e-9)
        // Extreme gaps are capped.
        assertEquals(1.5, Correction.learn(actual = 200, forecast = 30).factor, 1e-9)
    }

    @Test
    fun `approximate model matches the calibration trip`() {
        val m = ApproximateCommute.minutes(murabba, masif)
        assertTrue(m in 65..80, "Murabba→Masif approx $m")
        val near = ApproximateCommute.minutes(GeoPoint(24.78, 46.66), masif)
        assertTrue(near < 20, "near $near")
    }

    @Test
    fun `parses route matrix stream`() {
        val client = GoogleRoutesClient(Http { error("unused") }, "k")
        val parsed = client.parse(
            """[
              {"originIndex":1,"destinationIndex":0,"status":{},"distanceMeters":8000,"duration":"1260s","condition":"ROUTE_EXISTS"},
              {"originIndex":0,"destinationIndex":0,"status":{},"distanceMeters":3000,"duration":"600s","condition":"ROUTE_EXISTS"},
              {"originIndex":2,"destinationIndex":0,"status":{"code":5,"message":"x"},"condition":"ROUTE_NOT_FOUND"}
            ]"""
        )
        assertEquals(10, parsed[0 to 0]!!.minutes)
        assertEquals(21, parsed[1 to 0]!!.minutes)
        assertEquals(8000, parsed[1 to 0]!!.meters)
        assertTrue((2 to 0) !in parsed)
    }

    @Test
    fun `service uses Google forecasts with correction and caches them`() = runTest {
        var calls = 0
        val http = Http { req ->
            calls++
            val body = req.body!!
            // Two homes → work in the morning; work → two homes in the evening.
            val resp = if (body.contains("\"destinations\":[{\"waypoint\":{\"location\":{\"latLng\":{\"latitude\":24.7655")) {
                """[{"originIndex":0,"destinationIndex":0,"duration":"900s","distanceMeters":5000,"condition":"ROUTE_EXISTS"},
                   {"originIndex":1,"destinationIndex":0,"duration":"1800s","distanceMeters":9000,"condition":"ROUTE_EXISTS"}]"""
            } else {
                """[{"originIndex":0,"destinationIndex":0,"duration":"1200s","distanceMeters":5000,"condition":"ROUTE_EXISTS"},
                   {"originIndex":0,"destinationIndex":1,"duration":"2400s","distanceMeters":9000,"condition":"ROUTE_EXISTS"}]"""
            }
            HttpResponse(200, resp, req.url)
        }
        val now = ZonedDateTime.of(2026, 10, 16, 15, 0, 0, 0, RIYADH).toInstant()
        val svc = CommuteService(GoogleRoutesClient(http, "key"), clock = { now })
        svc.setCorrections(Correction(1.0, 5), Correction(1.0, 5))
        val a = GeoPoint(24.77, 46.67)
        val b = GeoPoint(24.80, 46.64)
        val r = svc.estimate(listOf(a, b), masif)
        assertEquals(CommuteMethod.GOOGLE_TRAFFIC, r[a]!!.method)
        assertEquals(15, r[a]!!.morningRawMinutes)
        assertEquals(20, r[a]!!.morningMinutes)
        assertEquals(25, r[a]!!.eveningMinutes)
        assertEquals(35, r[b]!!.morningMinutes)
        assertEquals(2, calls)
        svc.estimate(listOf(a, b), masif)
        assertEquals(2, calls, "second call served from cache")
    }

    @Test
    fun `service falls back to approximate without a key`() = runTest {
        val svc = CommuteService(null, clock = { Instant.parse("2026-10-16T12:00:00Z") })
        val r = svc.estimate(listOf(GeoPoint(24.78, 46.66)), masif)
        assertEquals(CommuteMethod.APPROXIMATE, r.values.single().method)
    }
}
