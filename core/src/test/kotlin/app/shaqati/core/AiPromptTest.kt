package app.shaqati.core

import app.shaqati.core.ai.Pricing
import app.shaqati.core.ai.Prompt
import app.shaqati.core.ai.Verdict
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPromptTest {
    private val sample = """
    {"score":88,"verdict":"modern","confidence":0.82,
     "floor":{"rating":9,"note":"large light porcelain tiles"},
     "ceiling_lighting":{"rating":9,"note":"gypsum ceiling with spotlights"},
     "walls_paint":{"rating":8,"note":"fresh white paint"},
     "doors_windows":{"rating":-1,"note":""},
     "bathroom":{"rating":8,"note":"modern vanity"},
     "kitchen":{"rating":-1,"note":""},
     "highlights_en":["Spotlights","New porcelain"],"highlights_ar":["سبوت لايت","بورسلان جديد"],
     "concerns_en":[],"concerns_ar":[],
     "summary_en":"Looks newly renovated.","summary_ar":"تبدو مجددة حديثاً.",
     "interior_photos":6,"photo_notes":[{"photo":2,"note":"spotlights"}],
     "visible_bedrooms":2,"visible_bathrooms":-1,"not_real_photos":false}
    """.trimIndent()

    @Test
    fun `parses structured output and maps -1 to not visible`() {
        val b = Prompt.parse(sample)
        assertEquals(88, b.score)
        assertEquals(Verdict.MODERN, b.verdict)
        assertEquals(9, b.floor.rating)
        assertNull(b.doorsWindows.rating)
        assertNull(b.kitchen.rating)
        assertEquals(2, b.visibleBedrooms)
        assertNull(b.visibleBathrooms)
        assertEquals("spotlights", b.photoNotes.single().note)
    }

    @Test
    fun `clamps out-of-range values and caps unclear confidence`() {
        val b = Prompt.parse(sample.replace("\"score\":88", "\"score\":140").replace("\"modern\"", "\"unclear\"").replace("0.82", "0.9"))
        assertEquals(100, b.score)
        assertEquals(Verdict.UNCLEAR, b.verdict)
        assertTrue(b.confidence <= 0.3)
    }

    @Test
    fun `tolerates text around the json`() {
        val b = Prompt.parse("Here is the result:\n$sample\nDone")
        assertEquals(88, b.score)
    }

    @Test
    fun `schema lists every property as required`() {
        val props = Prompt.SCHEMA["properties"]!!.jsonObject.keys
        val required = Prompt.SCHEMA["required"]!!.jsonArray.map { it.toString().trim('"') }.toSet()
        assertEquals(props, required)
        assertTrue("ceiling_lighting" in props)
    }

    @Test
    fun `cost estimate is a few cents at most`() {
        val sync = Pricing.estimate(photos = 8, batch = false)
        val batch = Pricing.estimate(photos = 8, batch = true)
        assertTrue(sync in 0.005..0.03, "sync $sync")
        assertEquals(sync / 2, batch, 1e-12)
    }
}
