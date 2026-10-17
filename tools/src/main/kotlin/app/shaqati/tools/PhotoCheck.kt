package app.shaqati.tools

import app.shaqati.core.Engine
import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.PhotoResults
import app.shaqati.core.ai.Prompt
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Source
import app.shaqati.core.sources.AqarSource
import app.shaqati.core.sources.DealSource
import app.shaqati.core.sources.HarajSource
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.OpenSooqSource
import app.shaqati.core.sources.PropertyFinderSource
import app.shaqati.core.sources.RiyadhNorth
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.util.MOBILE_UA
import app.shaqati.core.util.OkHttpHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.system.exitProcess

/**
 * The photo check that runs on the owner's Claude subscription, in the photo-check workflow:
 *
 * - `queue <dir> <results.json> <max>` searches the sites the workflow can reach, picks the
 *   apartments worth a look that have no assessment yet, and saves each one's photos and ad text
 *   with the question for Claude Code.
 * - `collect <dir> <answers> <results.json>` reads Claude Code's answers and adds them to the
 *   results file the app downloads.
 *
 * Optional settings come from the environment: SHAQATI_WORK ("lat,lng"), SHAQATI_RADIUS_KM and
 * SHAQATI_MAX_RENT; the defaults are the app's.
 */
fun main(args: Array<String>) = runBlocking {
    when (args.firstOrNull()) {
        "queue" -> queue(File(args[1]), File(args[2]), args.getOrNull(3)?.toInt() ?: 10)
        "collect" -> collect(File(args[1]), File(args[2]), File(args[3]))
        else -> {
            System.err.println("usage: queue <dir> <results.json> <max> | collect <dir> <answers> <results.json>")
            exitProcess(2)
        }
    }
}

@Serializable
data class QueuedAd(val id: String, val url: String, val photos: List<String>)

private val json = Json { prettyPrint = true; encodeDefaults = true }

/** The question for one apartment; Claude Code reads the files in the folder it runs in. */
fun question(photoFiles: List<String>): String = Prompt.SYSTEM + "\n\n" + """
The ad is in ad.txt in the current folder and its photos are ${photoFiles.joinToString(", ")}, in that order (photo 1 first).
Read ad.txt, then look at every photo with the Read tool, then answer with the JSON object described above. Use only these files.
""".trim()

suspend fun queue(dir: File, resultsFile: File, max: Int) {
    val env = System.getenv()
    val work = env["SHAQATI_WORK"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        ?.takeIf { it.size == 2 }?.let { GeoPoint(it[0], it[1]) } ?: RiyadhNorth.DEFAULT_WORK
    val radius = env["SHAQATI_RADIUS_KM"]?.toDoubleOrNull() ?: 9.0
    val criteria = env["SHAQATI_MAX_RENT"]?.toDoubleOrNull()?.let { Criteria(maxAnnualRent = it) } ?: Criteria()
    val area = SearchArea(work, radius)
    val http = OkHttpHttp()
    val engine = Engine()

    // Bayut and Wasalt answer servers with a bot check; the app reads them on the phone.
    val pf = PropertyFinderSource()
    val openSooq = OpenSooqSource()
    val deal = DealSource()
    val sources: List<ListingSource> = listOf(AqarSource(), HarajSource(), pf, deal, openSooq)
    val results = engine.refresh(sources, { http }, area, criteria, maxPages = 4, concurrency = 3)
    results.forEach { println("${it.source.key}: ${it.listings.size} ads, blocked=${it.blocked}, errors=${it.errors}") }

    val done = if (resultsFile.exists()) PhotoResults.decode(resultsFile.readText()).assessments else emptyList()
    engine.importAssessments(done)

    // Ad pages add photos and the full text for the first candidates.
    val details: Map<Source, ListingSource> = mapOf(Source.PROPERTY_FINDER to pf, Source.OPENSOOQ to openSooq, Source.DEAL to deal)
    val ctx = SearchContext(http, area, criteria, 4)
    for (v in engine.aiQueue(criteria, work).take(max * 2)) {
        for (l in v.alsoOn) {
            val src = details[l.source] ?: continue
            if (engine.snapshot.tracked[l.id]?.detailsFetchedAt != null) continue
            runCatching { src.details(l, ctx) }.getOrNull()?.let { engine.updateListing(it, detailsFetched = true) }
        }
    }

    val photosClient = OkHttpClient.Builder().followRedirects(true).build()
    dir.mkdirs()
    var queued = 0
    for (v in engine.aiQueue(criteria, work)) {
        if (queued >= max) break
        val folder = File(dir, "%02d".format(queued))
        folder.deleteRecursively()
        folder.mkdirs()
        val saved = mutableListOf<String>()
        val files = mutableListOf<String>()
        for (url in engine.photosForAi(v.listing).take(6)) {
            val name = runCatching { download(photosClient, url, folder, "photo-${files.size + 1}") }.getOrNull() ?: continue
            saved += url
            files += name
        }
        if (files.size < 2) {
            folder.deleteRecursively()
            continue
        }
        File(folder, "ad.txt").writeText(Prompt.listingText(v.listing, v.resolved, files.size))
        // The workflow turns every photo into photo-N.jpg before Claude Code looks at them.
        File(folder, "question.txt").writeText(question(files.indices.map { "photo-${it + 1}.jpg" }))
        File(folder, "meta.json").writeText(json.encodeToString(QueuedAd.serializer(), QueuedAd(v.id, v.listing.url, saved)))
        queued++
        println("queued ${v.id} (${files.size} photos, score ${v.score.total})")
    }
    File(dir, "schema.json").writeText(Prompt.SCHEMA.toString())
    println("queued $queued apartments")
}

/** Saves one photo as `<base>.<ext>`; the workflow converts every photo to a JPEG afterwards. */
private fun download(client: OkHttpClient, url: String, folder: File, base: String): String? {
    val req = Request.Builder().url(url).header("User-Agent", MOBILE_UA).build()
    client.newCall(req).execute().use { resp ->
        val body = resp.body ?: return null
        if (!resp.isSuccessful) return null
        val ext = when (body.contentType()?.subtype) {
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "jpeg", "jpg" -> "jpg"
            else -> return null
        }
        val bytes = body.bytes()
        if (bytes.size < 2_000) return null
        File(folder, "$base.$ext").writeBytes(bytes)
        return "$base.$ext"
    }
}

fun collect(dir: File, answers: File, resultsFile: File) {
    val now = System.currentTimeMillis()
    val fresh = mutableListOf<AiAssessment>()
    val folders = dir.listFiles { f -> f.isDirectory && File(f, "meta.json").exists() }.orEmpty().sortedBy { it.name }
    for (folder in folders) {
        val meta = json.decodeFromString(QueuedAd.serializer(), File(folder, "meta.json").readText())
        val answer = File(answers, "${folder.name}.json")
        if (!answer.exists()) {
            println("${meta.id}: no answer")
            continue
        }
        runCatching { PhotoResults.parseClaudeCodeOutput(answer.readText()) }
            .onSuccess { body ->
                fresh += AiAssessment(
                    listingId = meta.id, body = body, model = PhotoResults.MODEL, photosUsed = meta.photos,
                    inputTokens = 0, outputTokens = 0, costUsd = 0.0, assessedAt = now, viaBatch = false,
                )
                println("${meta.id}: ${body.verdict} ${body.score}")
            }
            .onFailure { println("${meta.id}: unreadable answer (${it.message?.take(160)})") }
    }
    val old = if (resultsFile.exists()) PhotoResults.decode(resultsFile.readText()) else PhotoResults()
    resultsFile.parentFile?.mkdirs()
    resultsFile.writeText(PhotoResults.encode(old.merge(fresh, now)))
    println("assessed ${fresh.size} of ${folders.size} apartments")
}
