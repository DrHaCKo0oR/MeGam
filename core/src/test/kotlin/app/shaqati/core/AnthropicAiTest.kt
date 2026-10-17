package app.shaqati.core

import app.shaqati.core.ai.AiException
import app.shaqati.core.ai.AnthropicAi
import app.shaqati.core.ai.PreparedImage
import app.shaqati.core.ai.Pricing
import app.shaqati.core.ai.Verdict
import app.shaqati.core.model.Listing
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Resolver
import app.shaqati.core.model.Source
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Claude request shape and response handling, against a local fake of the Messages API. */
class AnthropicAiTest {
    private lateinit var server: MockWebServer

    private val assessment = """{"score":81,"verdict":"renovated","confidence":0.7,""" +
        """"floor":{"rating":8,"note":"porcelain"},"ceiling_lighting":{"rating":7,"note":"gypsum"},""" +
        """"walls_paint":{"rating":8,"note":"fresh"},"doors_windows":{"rating":-1,"note":""},""" +
        """"bathroom":{"rating":7,"note":"new vanity"},"kitchen":{"rating":-1,"note":""},""" +
        """"highlights_en":["Fresh paint"],"highlights_ar":["دهان جديد"],"concerns_en":[],"concerns_ar":[],""" +
        """"summary_en":"Recently renovated.","summary_ar":"مجددة حديثاً.","interior_photos":3,""" +
        """"photo_notes":[],"visible_bedrooms":2,"visible_bathrooms":2,"not_real_photos":false}"""

    private fun message(text: String, stop: String = "end_turn") = buildString {
        append("""{"id":"msg_01","type":"message","role":"assistant","model":"claude-sonnet-5-5",""")
        append(""""content":[{"type":"text","text":${JsonPrimitive(text)}}],""")
        append(""""stop_reason":"$stop","stop_sequence":null,""")
        append(""""usage":{"input_tokens":1500,"output_tokens":420,"cache_creation_input_tokens":0,"cache_read_input_tokens":2100}}""")
    }

    private val listing = Listing(
        source = Source.AQAR, sourceId = "1", url = "https://sa.aqar.fm/1", title = "شقة مجددة للإيجار",
        description = "شقة مجددة بالكامل، سبوت لايت", price = 45_000.0, period = RentPeriod.YEARLY,
        bedrooms = 2, livingRooms = 1, bathrooms = 2, photos = listOf("https://img/1.jpg", "https://img/2.jpg"),
    )

    @BeforeTest
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun ai() = AnthropicAi("sk-ant-test", baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `sends photos with a cached rubric, adaptive thinking and a JSON schema`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(message(assessment)))
        val images = listOf(PreparedImage("AAAA", "image/jpeg"), PreparedImage("BBBB", "image/jpeg"))
        val a = ai().assess(listing, Resolver.resolve(listing), images, listing.photos)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertTrue(req.getHeader("anthropic-version") != null)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val output = body["output_config"]!!.jsonObject
        assertEquals("low", output["effort"]!!.jsonPrimitive.content)
        val format = output["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertTrue((format["schema"] as JsonObject)["properties"]!!.jsonObject.containsKey("verdict"))
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val imageBlocks = content.filter { it.jsonObject["type"]!!.jsonPrimitive.content == "image" }
        assertEquals(2, imageBlocks.size)
        assertEquals("base64", imageBlocks.first().jsonObject["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("AAAA", imageBlocks.first().jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)

        assertEquals(Verdict.RENOVATED, a.body.verdict)
        assertEquals(81, a.body.score)
        assertEquals("aqar:1", a.listingId)
        assertEquals(1500L + 2100L, a.inputTokens)
        assertEquals(Pricing.cost(1500, 420, 0, 2100, batch = false), a.costUsd, 1e-12)
    }

    @Test
    fun `refusals and bad keys become clear errors`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(message("", stop = "refusal")))
        assertFailsWith<AiException> { ai().assess(listing, Resolver.resolve(listing), listOf(PreparedImage("AAAA", "image/jpeg")), listing.photos) }
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        val e = assertFailsWith<AiException> { ai().assess(listing, Resolver.resolve(listing), listOf(PreparedImage("AAAA", "image/jpeg")), listing.photos) }
        assertTrue(e.unauthorized)
    }

    @Test
    fun `batch requests reuse the same parameters`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msgbatch_01","type":"message_batch","processing_status":"in_progress",""" +
                    """"request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0},""" +
                    """"created_at":"2026-10-16T10:00:00Z","expires_at":"2026-10-17T10:00:00Z","ended_at":null,""" +
                    """"archived_at":null,"cancel_initiated_at":null,"results_url":null}"""
            )
        )
        val ai = ai()
        val p = ai.params(listing, Resolver.resolve(listing), listOf(PreparedImage("AAAA", "image/jpeg")))
        val id = ai.submitBatch(listOf("i0" to p))
        assertEquals("msgbatch_01", id)
        val req = server.takeRequest()
        assertEquals("/v1/messages/batches", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val r = body["requests"]!!.jsonArray.single().jsonObject
        assertEquals("i0", r["custom_id"]!!.jsonPrimitive.content)
        val params = r["params"]!!.jsonObject
        assertEquals("claude-sonnet-5-5", params["model"]!!.jsonPrimitive.content)
        assertTrue(params.containsKey("output_config"))
        assertTrue(params.containsKey("system"))
        assertTrue(params.containsKey("thinking"))
    }
}
