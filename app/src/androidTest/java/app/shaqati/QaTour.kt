package app.shaqati

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shaqati.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Walks every screen in English and Arabic with recorded listings and saves screenshots. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QaTour {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** Lets Compose draw what the last action changed (its test clock only moves on waitForIdle). */
    private fun shot(name: String, settleMs: Long = 1_200) {
        compose.waitForIdle()
        Thread.sleep(settleMs)
        compose.waitForIdle()
        Qa.shot(name)
    }

    /** Waits until the map page has started (the WebView reports back); returns false on timeout. */
    private fun waitForMap(): Boolean =
        runCatching { compose.waitUntilAtLeastOneExists(hasTestTag("map_ready"), 25_000) }.isSuccess

    @Test
    fun a_onboarding() {
        runBlocking { Qa.repo.updateSettings { it.copy(onboarded = false) } }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Qa.setLocale(sc, "en")
            compose.waitUntilAtLeastOneExists(hasText(Qa.str("en", R.string.onb_start)), 15_000)
            shot("en-01-onboarding")
        }
    }

    @Test
    fun b_english() = tour("en")

    /**
     * The map page in a plain WebView (no Compose), started the way the app starts it, to tell a
     * page problem from a problem with how the app hosts the WebView.
     */
    @Test
    fun e_mapPagePlainWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(a))
                    .build()
                val web = WebView(a)
                web.settings.javaScriptEnabled = true
                web.webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(
                            "initMap({lat:24.7655,lng:46.6802,zoom:13,key:null,lang:'en',pick:false});" +
                                "setData({work:{lat:24.7655,lng:46.6802},pins:[{id:'a',lat:24.7712,lng:46.6935,label:'45k',color:'#1B7F4B',z:500}],fit:false})",
                            null,
                        )
                    }
                }
                a.addContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1400))
                web.loadUrl("https://appassets.androidplatform.net/assets/map/map.html")
            }
            Thread.sleep(8_000)
            Qa.js(sc, Qa.MAP_PROBE)
            Qa.shot("zz-map-page-plain")
            Qa.webShot(sc, "zz-map-page-plain-content")
        }
    }

    /**
     * Control for the map screenshots: a plain WebView (no Compose) showing a red square. If the
     * square does not appear either, the emulator itself cannot show WebView content.
     */
    @Test
    fun d_webviewControl() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val web = WebView(a)
                a.addContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 900))
                web.loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/",
                    "<html><body style='margin:0;background:#e9e3d8'><div style='width:320px;height:320px;background:#d00000'></div>" +
                        "<p style='font:40px sans-serif'>WebView control</p></body></html>",
                    "text/html", "utf-8", null,
                )
            }
            Thread.sleep(6_000)
            Qa.shot("zz-webview-control")
            Qa.webShot(sc, "zz-webview-control-content")
        }
    }

    @Test
    fun c_arabic() = tour("ar")

    private fun tour(lang: String) {
        Qa.seed()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Qa.setLocale(sc, lang)
            compose.waitUntilAtLeastOneExists(hasText(Qa.str(lang, R.string.feed_matches), substring = true), 15_000)
            compose.waitUntilAtLeastOneExists(hasTestTag("card"), 15_000)
            shot("$lang-02-feed", 2_500)

            compose.onNodeWithTag("chip_UNCONFIRMED").performClick()
            shot("$lang-03-to-check")
            compose.onNodeWithTag("chip_NEAR_MISSES").performClick()
            shot("$lang-04-close")
            compose.onNodeWithTag("chip_ALL").performClick()
            shot("$lang-05-all")

            compose.onNodeWithTag("tab_saved").performClick()
            compose.waitUntilAtLeastOneExists(hasText(Qa.str(lang, R.string.tab_saved)), 5_000)
            shot("$lang-06-saved")
            if (compose.onAllNodesWithTag("card").fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodesWithTag("card").onFirst().performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag("detail_list"), 5_000)
                shot("$lang-07-detail", 2_500)
                compose.onNodeWithTag("detail_list").performScrollToIndex(3)
                shot("$lang-08-detail-finish")
                compose.onNodeWithTag("detail_list").performScrollToIndex(5)
                shot("$lang-09-detail-requirements")
                compose.onNodeWithTag("detail_list").performScrollToIndex(0)
                compose.onAllNodesWithContentDescription(Qa.str(lang, R.string.action_back)).onFirst().performClick()
            }

            compose.onNodeWithTag("tab_map").performClick()
            Qa.log("map ready=" + waitForMap())
            shot("$lang-10-map", 5_000)
            Qa.jsResult(sc, Qa.MAP_PROBE)
            assertTrue("map page laid out with no height", Qa.mapHeight(sc) > 0)
            Qa.webShot(sc, "$lang-10c-map-content")
            // Tapping a pin goes through the page's bridge back to the app, which shows the card.
            Qa.js(sc, "var p = document.querySelector('.pin'); if (p) p.click(); 'pins=' + document.querySelectorAll('.pin').length")
            val card = runCatching { compose.waitUntilAtLeastOneExists(hasTestTag("card"), 8_000) }.isSuccess
            Qa.log("map pin card=$card")
            shot("$lang-10b-map-pin")

            compose.onNodeWithTag("tab_settings").performClick()
            shot("$lang-11-settings")
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            shot("$lang-12-settings-ai")
            compose.onNodeWithTag("settings_list").performScrollToIndex(0)
            compose.onNode(hasText(Qa.str(lang, R.string.set_work_change))).performClick()
            Qa.log("picker ready=" + waitForMap())
            shot("$lang-13-work-picker", 5_000)
            assertTrue("picker map laid out with no height", Qa.mapHeight(sc) > 0)
            Qa.webShot(sc, "$lang-13c-picker-content")
            compose.onAllNodesWithContentDescription(Qa.str(lang, R.string.action_back)).onFirst().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("settings_list"), 5_000)
        }
    }
}
