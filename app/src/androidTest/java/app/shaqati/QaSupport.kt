package app.shaqati

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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

    /** Taps "Wait" on a system "isn't responding" dialog, which would otherwise cover the app. */
    fun dismissSystemDialogs() {
        val device = UiDevice.getInstance(instr)
        repeat(3) {
            val wait = device.findObject(By.res("android", "aerr_wait")) ?: return
            log("dismissed a system 'not responding' dialog")
            wait.click()
            device.waitForIdle()
        }
    }

    /** Half-size JPEG of the whole screen. */
    fun shot(name: String) {
        dismissSystemDialogs()
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

    /** Runs [script] in the first WebView on screen and logs its result. */
    fun js(scenario: androidx.test.core.app.ActivityScenario<*>, script: String) {
        scenario.onActivity { a ->
            val web = findWebView(a.window.decorView)
            if (web == null) log("js: no WebView") else web.evaluateJavascript(script) { log("js: $it") }
        }
        instr.waitForIdleSync()
    }

    /**
     * Saves what the WebView on screen draws through its software path. The CI emulator's
     * software GPU does not composite WebView content onto the screen, so the regular
     * screenshot shows only the page background there.
     */
    fun webShot(scenario: androidx.test.core.app.ActivityScenario<*>, name: String) {
        var bmp: Bitmap? = null
        scenario.onActivity { a ->
            val web = findWebView(a.window.decorView)
            if (web != null && web.width > 0 && web.height > 0) {
                bmp = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888).also { web.draw(Canvas(it)) }
            }
        }
        val b = bmp ?: return log("webShot $name: no WebView")
        val small = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        File(outDir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        log("webShot $name")
    }

    private fun findWebView(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWebView(v.getChildAt(it)) }
        else -> null
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
                        floor = ItemRating(9, "Large light porcelain tiles", "بلاط بورسلان فاتح كبير"),
                        ceilingLighting = ItemRating(8, "Gypsum ceiling with spotlights", "سقف جبس مع إضاءة سبوت"),
                        wallsPaint = ItemRating(8, "Fresh neutral paint", "دهان جديد بألوان هادئة"),
                        doorsWindows = ItemRating(7, "Modern doors", "أبواب حديثة"),
                        bathroom = ItemRating(8, "Wall-hung toilet, new vanity", "كرسي معلق ومغسلة جديدة"),
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
