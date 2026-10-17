package app.shaqati.tools

import app.shaqati.core.ai.PhotoResults
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoCheckTest {
    @Test
    fun `collect adds the answers that parse and skips the rest`() {
        val root = Files.createTempDirectory("photocheck").toFile()
        val queue = root.resolve("queue")
        val answers = root.resolve("answers").apply { mkdirs() }
        val results = root.resolve("photo-results/assessments.json")
        listOf("00" to "aqar:1", "01" to "pf:2", "02" to "haraj:3").forEach { (dir, id) ->
            queue.resolve(dir).apply { mkdirs() }.resolve("meta.json")
                .writeText("""{"id":"$id","url":"https://example.com/$id","photos":["https://img/$id/1.jpg","https://img/$id/2.jpg"]}""")
        }
        val body = """{"score":88,"verdict":"modern","confidence":0.8,"interior_photos":4}"""
        answers.resolve("00.json").writeText("""{"type":"result","is_error":false,"structured_output":$body}""")
        answers.resolve("01.json").writeText("""{"type":"result","is_error":true,"result":"usage limit reached"}""")
        // 02 has no answer (timed out).

        collect(queue, answers, results)
        val out = PhotoResults.decode(results.readText())
        assertEquals(listOf("aqar:1"), out.assessments.map { it.listingId })
        val a = out.assessments.single()
        assertEquals(88, a.body.score)
        assertEquals(PhotoResults.MODEL, a.model)
        assertEquals(0.0, a.costUsd)
        assertEquals(listOf("https://img/aqar:1/1.jpg", "https://img/aqar:1/2.jpg"), a.photosUsed)

        // A later run keeps earlier results.
        answers.resolve("00.json").delete()
        answers.resolve("01.json").writeText("""{"type":"result","is_error":false,"structured_output":$body}""")
        collect(queue, answers, results)
        assertEquals(setOf("aqar:1", "pf:2"), PhotoResults.decode(results.readText()).assessments.map { it.listingId }.toSet())
        assertTrue(question(listOf("photo-1.jpg", "photo-2.jpg")).contains("photo-1.jpg, photo-2.jpg"))
    }
}
