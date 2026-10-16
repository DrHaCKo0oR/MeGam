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

    @Test
    fun a_onboarding() {
        runBlocking { Qa.repo.updateSettings { it.copy(onboarded = false) } }
        Qa.setLocale("en")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasText(Qa.str("en", R.string.onb_start)), 15_000)
            Qa.shot("en-01-onboarding")
        }
    }

    @Test
    fun b_english() = tour("en")

    @Test
    fun c_arabic() = tour("ar")

    private fun tour(lang: String) {
        Qa.seed()
        Qa.setLocale(lang)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasText(Qa.str(lang, R.string.feed_matches), substring = true), 15_000)
            Qa.shot("$lang-02-feed")

            compose.onNodeWithTag("chip_UNCONFIRMED").performClick()
            Qa.shot("$lang-03-to-check")
            compose.onNodeWithTag("chip_NEAR_MISSES").performClick()
            Qa.shot("$lang-04-close")
            compose.onNodeWithTag("chip_ALL").performClick()
            Qa.shot("$lang-05-all")

            compose.onNodeWithTag("tab_saved").performClick()
            compose.waitUntilAtLeastOneExists(hasText(Qa.str(lang, R.string.tab_saved)), 5_000)
            Qa.shot("$lang-06-saved")
            if (compose.onAllNodesWithTag("card").fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodesWithTag("card").onFirst().performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag("detail_list"), 5_000)
                Qa.shot("$lang-07-detail")
                compose.onNodeWithTag("detail_list").performScrollToIndex(3)
                Qa.shot("$lang-08-detail-finish")
                compose.onNodeWithTag("detail_list").performScrollToIndex(5)
                Qa.shot("$lang-09-detail-requirements")
                UiDevice.getInstance(Qa.instr).pressBack()
            }

            compose.onNodeWithTag("tab_map").performClick()
            Thread.sleep(7_000)
            Qa.shot("$lang-10-map")

            compose.onNodeWithTag("tab_settings").performClick()
            Qa.shot("$lang-11-settings")
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            Qa.shot("$lang-12-settings-ai")
        }
    }
}
