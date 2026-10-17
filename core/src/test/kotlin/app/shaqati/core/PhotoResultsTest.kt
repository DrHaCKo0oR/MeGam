package app.shaqati.core

import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.AssessmentBody
import app.shaqati.core.ai.PhotoResults
import app.shaqati.core.ai.Verdict
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Source
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.sources.SearchResult
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Photo checks from the Claude subscription workflow: reading Claude Code's answer and merging results. */
class PhotoResultsTest {
    private val answer = """{"score":82,"verdict":"renovated","confidence":0.7,
        "floor":{"rating":8,"note":"large porcelain tiles","note_ar":"بلاط بورسلان كبير"},
        "ceiling_lighting":{"rating":-1,"note":"","note_ar":""},
        "walls_paint":{"rating":7,"note":"fresh paint","note_ar":"دهان جديد"},
        "doors_windows":{"rating":-1,"note":"","note_ar":""},
        "bathroom":{"rating":9,"note":"new vanity","note_ar":"مغسلة جديدة"},
        "kitchen":{"rating":-1,"note":"","note_ar":""},
        "highlights_en":["New floors"],"highlights_ar":["أرضيات جديدة"],"concerns_en":[],"concerns_ar":[],
        "summary_en":"Recently updated.","summary_ar":"محدثة مؤخراً.","interior_photos":5,
        "photo_notes":[{"photo":2,"note":"spotlights"}],"visible_bedrooms":2,"visible_bathrooms":-1,"not_real_photos":false}"""

    @Test
    fun `reads the structured output of claude -p`() {
        val out = """{"type":"result","subtype":"success","is_error":false,"result":"","structured_output":$answer}"""
        val body = PhotoResults.parseClaudeCodeOutput(out)
        assertEquals(82, body.score)
        assertEquals(Verdict.RENOVATED, body.verdict)
        assertEquals(8, body.floor.rating)
        assertNull(body.kitchen.rating)
        assertNull(body.visibleBathrooms)
        assertEquals("أرضيات جديدة", body.highlightsAr.single())
    }

    @Test
    fun `reads the answer from the result text and from verbose output`() {
        val text = kotlinx.serialization.json.JsonPrimitive("Here is the assessment:\n$answer").toString()
        assertEquals(82, PhotoResults.parseClaudeCodeOutput("""{"type":"result","is_error":false,"result":$text}""").score)
        val verbose = """[{"type":"system","subtype":"init"},{"type":"result","is_error":false,"result":$text}]"""
        assertEquals(Verdict.RENOVATED, PhotoResults.parseClaudeCodeOutput(verbose).verdict)
        assertFailsWith<IllegalArgumentException> {
            PhotoResults.parseClaudeCodeOutput("""{"type":"result","is_error":true,"result":"Invalid API key"}""")
        }
    }

    private fun assessment(id: String, at: Long, score: Int = 70) = AiAssessment(
        listingId = id, body = AssessmentBody(score = score, verdict = Verdict.RENOVATED, confidence = 0.6),
        model = PhotoResults.MODEL, photosUsed = emptyList(), inputTokens = 0, outputTokens = 0,
        costUsd = 0.0, assessedAt = at, viaBatch = false,
    )

    @Test
    fun `merging keeps the newest per apartment and drops old ones`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 400 * day
        val old = PhotoResults(updatedAt = 1, assessments = listOf(assessment("aqar:1", now - 2 * day, 60), assessment("aqar:2", now - 200 * day)))
        val merged = old.merge(listOf(assessment("aqar:1", now, 80), assessment("pf:3", now)), now)
        assertEquals(now, merged.updatedAt)
        assertEquals(setOf("aqar:1", "pf:3"), merged.assessments.map { it.listingId }.toSet())
        assertEquals(80, merged.assessments.first { it.listingId == "aqar:1" }.body.score)
        assertEquals(merged, PhotoResults.decode(PhotoResults.encode(merged)))
    }

    @Test
    fun `the engine imports assessments for apartments it knows, newest wins`() = runBlocking {
        val engine = Engine()
        val l = Listing(Source.AQAR, "1", "https://sa.aqar.fm/1", "شقة", price = 45_000.0, period = RentPeriod.YEARLY, location = GeoPoint(24.77, 46.68))
        val src = object : ListingSource {
            override val source = Source.AQAR
            override suspend fun search(ctx: SearchContext) = SearchResult(Source.AQAR, listOf(l), 1)
        }
        engine.refresh(listOf(src), { Http { HttpResponse(200, "", it.url) } }, SearchArea(GeoPoint(24.77, 46.68), 9.0), Criteria())
        assertEquals(1, engine.importAssessments(listOf(assessment("aqar:1", 10), assessment("aqar:999", 10))))
        assertEquals(0, engine.importAssessments(listOf(assessment("aqar:1", 5, 40))))
        assertEquals(1, engine.importAssessments(listOf(assessment("aqar:1", 20, 90))))
        assertEquals(90, engine.snapshot.ai["aqar:1"]!!.body.score)
        // Imported checks are not API spending.
        assertEquals(0.0, engine.spentThisMonth())
    }
}
