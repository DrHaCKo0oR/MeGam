package app.shaqati

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.UiDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shaqati.ui.MainActivity
import kotlinx.coroutines.runBlocking
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
                UiDevice.getInstance(Qa.instr).pressBack()
            }

            compose.onNodeWithTag("tab_map").performClick()
            compose.waitForIdle()
            // The WebView loads on its own; the pins are pushed once the page reports ready.
            Thread.sleep(6_000)
            shot("$lang-10-map", 3_000)

            compose.onNodeWithTag("tab_settings").performClick()
            shot("$lang-11-settings")
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            shot("$lang-12-settings-ai")
            compose.onNodeWithTag("settings_list").performScrollToIndex(0)
            compose.onNode(hasText(Qa.str(lang, R.string.set_work_change))).performClick()
            compose.waitForIdle()
            Thread.sleep(6_000)
            shot("$lang-13-work-picker", 2_000)
            UiDevice.getInstance(Qa.instr).pressBack()
        }
    }
}
