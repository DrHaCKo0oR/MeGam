package app.shaqati.core

import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.AssessmentBody
import app.shaqati.core.ai.Verdict
import app.shaqati.core.filter.Check
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Resolver
import app.shaqati.core.model.Source
import app.shaqati.core.sources.AqarSource
import app.shaqati.core.sources.Districts
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.sources.SearchResult
import app.shaqati.core.text.TextSignals
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cases found in the live run against the real sites. */
class AccuracyTest {
    private val work = GeoPoint(24.7655, 46.6802)

    @Test
    fun `room, living room and bathroom means one bathroom`() {
        assertEquals(1, TextSignals.analyze("متاح شقه غرفه وصاله وحمام ومطبخ مكيف اسبيلت").bathrooms)
        assertEquals(2, TextSignals.analyze("غرفتين نوم وصالة وحمامين ومطبخ").bathrooms)
        assertEquals(3, TextSignals.analyze("3 غرف نوم وصالة و3 دورات مياه").bathrooms)
    }

    @Test
    fun `tiny prices are typos, not rents`() {
        val l = Listing(Source.AQAR, "1", "u", "شقة", price = 85.0)
        val r = Resolver.resolve(l)
        assertNull(r.annualRent)
        assertEquals(RentPeriod.UNKNOWN, r.period)
    }

    @Test
    fun `districts written in the text place an ad`() {
        assertEquals("المصيف", Districts.inText("دور ارضي جديد حي المصيف ويتوفر ستديو كبير")?.ar)
        assertEquals("الملك فهد", Districts.inText("شقة للايجار بحي الملك فهد قريبة من الخدمات")?.ar)
        assertNull(Districts.inText("دور ارضي للايجار بحي الرحبة بمحافظة شقراء"))
    }

    @Test
    fun `aqar paging follows the number of ads received`() {
        val page = AqarSource.parseSearch(requireNotNull(javaClass.getResource("/fixtures/aqar-search.json")).readText())
        assertEquals(12, page.received)
        assertEquals(497, page.total)
    }

    @Test
    fun `an unstated bathroom count waits for confirmation, and photos can confirm it`() = runBlocking {
        val ad = Listing(
            source = Source.AQAR, sourceId = "7", url = "u", title = "شقة للايجار", description = "شقة عوائل",
            price = 45_000.0, period = RentPeriod.YEARLY, bedrooms = 2, livingRooms = 1, bathrooms = null,
            city = "الرياض", location = GeoPoint(24.7800, 46.6900), locationPrecision = LocationPrecision.EXACT,
            photos = listOf("a", "b", "c", "d"), postedAt = System.currentTimeMillis(),
        )
        val src = object : ListingSource {
            override val source = Source.AQAR
            override suspend fun search(ctx: SearchContext) = SearchResult(Source.AQAR, listOf(ad), 1)
        }
        val e = Engine()
        e.refresh(listOf(src), { Http { HttpResponse(200, "", it.url) } }, SearchArea(work, 9.0), Criteria())
        val before = e.views(Criteria(), FeedMode.ALL, work = work).single()
        assertEquals(FeedMode.UNCONFIRMED, before.bucket)
        assertTrue(Check.BATHROOMS in before.evaluation.unconfirmed)
        // Still worth analysing: the photos can show the bathrooms.
        assertEquals(listOf("aqar:7"), e.aiQueue(Criteria(), work).map { it.id })
        e.saveAssessment(
            AiAssessment(
                "aqar:7", AssessmentBody(score = 70, verdict = Verdict.RENOVATED, confidence = 0.7, visibleBathrooms = 2),
                "m", listOf("a"), 1, 1, 0.01, System.currentTimeMillis(), true,
            )
        )
        assertEquals(FeedMode.MATCHES, e.views(Criteria(), FeedMode.ALL, work = work).single().bucket)
    }
}
