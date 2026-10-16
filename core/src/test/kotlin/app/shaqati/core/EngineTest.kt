package app.shaqati.core

import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.AssessmentBody
import app.shaqati.core.ai.Verdict
import app.shaqati.core.commute.CommuteMethod
import app.shaqati.core.filter.Check
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.sources.SearchResult
import app.shaqati.core.store.CatalogStore
import app.shaqati.core.store.HideReason
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpResponse
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineTest {
    private val work = GeoPoint(24.7655, 46.6802)
    private val near = GeoPoint(24.7800, 46.6900) // ~2 km from work
    private val far = GeoPoint(24.6300, 46.8500) // ~22 km
    private var now = 1_792_000_000_000L
    private val http = Http { HttpResponse(200, "", it.url) }
    private val area = SearchArea(work, 8.0)

    private fun ad(
        id: String,
        source: Source = Source.AQAR,
        price: Double? = 45_000.0,
        beds: Int? = 2,
        baths: Int? = 2,
        location: GeoPoint? = near,
        phone: String? = null,
        description: String = "شقة عوائل للايجار",
        photos: Int = 5,
        furnishing: Furnishing = Furnishing.UNFURNISHED,
    ) = Listing(
        source = source, sourceId = id, url = "https://example/$id", title = "شقة للإيجار", description = description,
        price = price, period = if (price == null) RentPeriod.UNKNOWN else RentPeriod.YEARLY,
        bedrooms = beds, livingRooms = 1, bathrooms = baths, furnishing = furnishing,
        city = "الرياض", district = "المصيف", location = location,
        locationPrecision = if (location != null) LocationPrecision.EXACT else LocationPrecision.UNKNOWN,
        photos = (1..photos).map { "https://img/$id/$it.jpg" }, phone = phone, postedAt = now - 86_400_000L,
    )

    private class FakeSource(override val source: Source, var items: List<Listing>, var fail: Boolean = false) : ListingSource {
        override suspend fun search(ctx: SearchContext): SearchResult =
            if (fail) SearchResult(source, emptyList(), 1, listOf("HTTP 500")) else SearchResult(source, items, 1)
    }

    private fun engine() = Engine(clock = { now })

    @Test
    fun `matches need confirmed rent, rooms and location`() = runBlocking {
        val e = engine()
        val src = FakeSource(
            Source.AQAR,
            listOf(
                ad("good"),
                ad("noprice", price = null),
                ad("norooms", beds = null, description = "للايجار"),
                ad("nolocation", location = null),
                ad("far", location = far),
                ad("expensive", price = 52_000.0),
                ad("furnished", furnishing = Furnishing.FURNISHED),
            ),
        )
        e.refresh(listOf(src), { http }, area, Criteria())
        val c = Criteria()
        assertEquals(listOf("aqar:good"), e.views(c, FeedMode.MATCHES, work = work).map { it.id })
        assertEquals(
            setOf("aqar:noprice", "aqar:norooms", "aqar:nolocation"),
            e.views(c, FeedMode.UNCONFIRMED, work = work).map { it.id }.toSet(),
        )
        // 52k is within 10% of the budget: a near miss. The far one fails on commute by a lot.
        val near = e.views(c, FeedMode.NEAR_MISSES, work = work).map { it.id }
        assertTrue("aqar:expensive" in near)
        assertFalse("aqar:far" in near)
        assertEquals(7, e.views(c, FeedMode.ALL, work = work).size)
        // Without a work pin the commute is unknown, so nothing is confirmed.
        assertTrue(e.views(c, FeedMode.MATCHES).isEmpty())
        val good = e.views(c, FeedMode.MATCHES, work = work).single()
        assertEquals(CommuteMethod.APPROXIMATE, good.commute!!.method)
        assertTrue(good.isNew)
    }

    @Test
    fun `duplicates across sites are one apartment`() = runBlocking {
        val e = engine()
        val a = FakeSource(Source.AQAR, listOf(ad("1", phone = "0551234567")))
        val h = FakeSource(Source.HARAJ, listOf(ad("2", source = Source.HARAJ, phone = "+966 55 123 4567", location = GeoPoint(24.7810, 46.6905))))
        e.refresh(listOf(a, h), { http }, area, Criteria())
        val v = e.views(Criteria(), FeedMode.MATCHES, work = work)
        assertEquals(1, v.size)
        assertEquals(setOf(Source.AQAR, Source.HARAJ), v.single().sources.toSet())
    }

    @Test
    fun `failed source keeps listings and ads vanish only after a clean run`() = runBlocking {
        val e = engine()
        val src = FakeSource(Source.AQAR, listOf(ad("1"), ad("2")))
        e.refresh(listOf(src), { http }, area, Criteria())
        src.fail = true
        now += 5 * 86_400_000L
        e.refresh(listOf(src), { http }, area, Criteria())
        assertEquals(2, e.views(Criteria(), FeedMode.ALL).size)
        assertFalse(e.snapshot.sources["aqar"]!!.ok)
        src.fail = false
        src.items = listOf(ad("1"))
        e.refresh(listOf(src), { http }, area, Criteria())
        assertEquals(listOf("aqar:1"), e.views(Criteria(), FeedMode.ALL).map { it.id })
        // Saved ads stay visible after they disappear, marked gone.
        e.setSaved("aqar:2", true)
        val saved = e.savedViews(Criteria(), work)
        assertEquals(1, saved.size)
        assertTrue(saved.single().gone)
    }

    @Test
    fun `hidden ads leave the feed and the AI queue skips assessed ones`() = runBlocking {
        val e = engine()
        e.refresh(listOf(FakeSource(Source.AQAR, listOf(ad("1"), ad("2"), ad("3", photos = 1)))), { http }, area, Criteria())
        e.hide("aqar:2", HideReason.OLD_FINISH)
        assertEquals(listOf("aqar:1"), e.aiQueue(Criteria(), work).map { it.id })
        e.saveAssessment(
            AiAssessment(
                listingId = "aqar:1",
                body = AssessmentBody(score = 82, verdict = Verdict.MODERN, confidence = 0.8, summaryEn = "Modern", summaryAr = "حديثة"),
                model = "m", photosUsed = listOf("a"), inputTokens = 9000, outputTokens = 600, costUsd = 0.024,
                assessedAt = now, viaBatch = true,
            )
        )
        assertTrue(e.aiQueue(Criteria(), work).isEmpty())
        assertEquals(0.024, e.spentThisMonth(), 1e-9)
        val v = assertNotNull(e.view("aqar:1", Criteria(), work))
        assertEquals(Verdict.MODERN, v.ai!!.body.verdict)
        assertTrue(v.score.finish >= 80)
    }

    @Test
    fun `catalog survives a save and load`() = runBlocking {
        val e = engine()
        e.refresh(listOf(FakeSource(Source.AQAR, listOf(ad("1")))), { http }, area, Criteria())
        e.setNote("aqar:1", "call after Maghrib")
        val file = File.createTempFile("catalog", ".json").apply { deleteOnExit() }
        val store = CatalogStore(file)
        store.save(e.snapshot)
        val loaded = store.load()
        assertEquals(e.snapshot, loaded)
        assertEquals("call after Maghrib", loaded.user.notes["aqar:1"])
    }

    @Test
    fun `rent failure is reported with the other checks`() = runBlocking {
        val e = engine()
        e.refresh(listOf(FakeSource(Source.AQAR, listOf(ad("1", price = 60_000.0, baths = 1)))), { http }, area, Criteria())
        val v = e.views(Criteria(), FeedMode.ALL, work = work).single()
        assertEquals(setOf(Check.RENT, Check.BATHROOMS), v.evaluation.failed.toSet())
    }
}
