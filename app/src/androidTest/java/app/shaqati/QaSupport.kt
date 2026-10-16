package app.shaqati

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.shaqati.core.Engine
import app.shaqati.core.FeedMode
import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.AssessmentBody
import app.shaqati.core.ai.ItemRating
import app.shaqati.core.ai.Verdict
import app.shaqati.core.model.Source
import app.shaqati.core.sources.AqarSource
import app.shaqati.core.sources.HarajSource
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.OpenSooqSource
import app.shaqati.core.sources.PropertyFinderSource
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.sources.SearchResult
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpResponse
import app.shaqati.data.Settings
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

/** Shared helpers for the on-device QA tests: seeding, locale switching and screenshots. */
object Qa {
    val instr get() = InstrumentationRegistry.getInstrumentation()
    val app: ShaqatiApp get() = instr.targetContext.applicationContext as ShaqatiApp
    val repo get() = app.graph.repository

    val outDir: File by lazy { File(app.getExternalFilesDir(null), "qa").apply { mkdirs() } }

    fun log(line: String) {
        File(outDir, "log.txt").appendText(line + "\n")
        android.util.Log.i("ShaqatiQA", line)
    }

    /** Half-size JPEG of the whole screen. */
    fun shot(name: String) {
        instr.waitForIdleSync()
        Thread.sleep(700)
        val bmp = instr.uiAutomation.takeScreenshot() ?: return
        val small = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        File(outDir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        log("shot $name")
    }

    /**
     * Switches the app language while [scenario]'s activity is on screen. Android restarts the
     * process when the language changes with no activity open, which would end the test run.
     */
    fun setLocale(scenario: androidx.test.core.app.ActivityScenario<*>, tag: String) {
        var current = ""
        scenario.onActivity { current = AppCompatDelegate.getApplicationLocales().toLanguageTags() }
        if (current == tag) return
        scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        instr.waitForIdleSync()
        Thread.sleep(2_000)
        log("locale $current -> $tag")
    }

    fun str(tag: String, id: Int, vararg args: Any): String {
        val conf = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return app.createConfigurationContext(conf).getString(id, *args)
    }

    fun settings(): Settings = runBlocking { repo.settings.filterNotNull().first() }

    private fun fixture(name: String): String = instr.context.assets.open("fixtures/$name").bufferedReader().use { it.readText() }

    /** Fills the catalog with recorded listings from four sites, plus one analysed apartment. */
    fun seed() = runBlocking {
        val s = settings()
        val data = mapOf(
            Source.AQAR to AqarSource.parseSearch(fixture("aqar-search.json")).listings,
            Source.HARAJ to HarajSource.parse(fixture("haraj-posts.json")),
            Source.PROPERTY_FINDER to PropertyFinderSource.parseSearch(fixture("pf-search.html")).first,
            Source.OPENSOOQ to OpenSooqSource.parseSearch(fixture("opensooq-search.html")).first,
        )
        val sources = data.map { (src, items) ->
            object : ListingSource {
                override val source = src
                override suspend fun search(ctx: SearchContext) = SearchResult(src, items, 1)
            }
        }
        val engine = Engine()
        engine.refresh(sources, { Http { HttpResponse(200, "", it.url) } }, SearchArea(s.work, 25.0), s.criteria)
        val all = engine.views(s.criteria, FeedMode.ALL, work = s.work)
        val pick = all.firstOrNull { it.bucket == FeedMode.MATCHES && it.listing.photos.size >= 3 }
            ?: all.firstOrNull { it.listing.photos.size >= 3 }
        if (pick != null) {
            engine.saveAssessment(
                AiAssessment(
                    listingId = pick.id,
                    body = AssessmentBody(
                        score = 84, verdict = Verdict.MODERN, confidence = 0.78,
                        floor = ItemRating(9, "Large light porcelain tiles"),
                        ceilingLighting = ItemRating(8, "Gypsum ceiling with spotlights"),
                        wallsPaint = ItemRating(8, "Fresh neutral paint"),
                        doorsWindows = ItemRating(7, "Modern doors"),
                        bathroom = ItemRating(8, "Wall-hung toilet, new vanity"),
                        kitchen = ItemRating(null, ""),
                        highlightsEn = listOf("Spotlights and hidden lighting", "New porcelain floors"),
                        highlightsAr = listOf("إضاءة سبوت ومخفية", "أرضيات بورسلان جديدة"),
                        concernsEn = listOf("Kitchen not shown"),
                        concernsAr = listOf("المطبخ غير ظاهر في الصور"),
                        summaryEn = "Recently renovated with a modern finish.",
                        summaryAr = "مجددة حديثاً بتشطيب عصري.",
                        interiorPhotos = 5,
                    ),
                    model = "test", photosUsed = pick.listing.photos.take(5), inputTokens = 4000, outputTokens = 500,
                    costUsd = 0.012, assessedAt = System.currentTimeMillis(), viaBatch = true,
                )
            )
            engine.setSaved(pick.id, true)
        }
        repo.importSnapshot(engine.snapshot)
        repo.updateSettings { it.copy(onboarded = true) }
        log("seeded ${engine.snapshot.tracked.size} ads; buckets=" + all.groupBy { it.bucket }.mapValues { it.value.size })
    }
}
