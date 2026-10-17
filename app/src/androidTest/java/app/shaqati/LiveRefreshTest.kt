package app.shaqati

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shaqati.core.FeedMode
import app.shaqati.core.model.Source
import app.shaqati.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real first run on the phone: onboarding, a search of every site, then the results. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LiveRefreshTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun settle(ms: Long = 2_500) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    @Test
    fun searchesTheLiveSites() {
        val repo = Qa.repo
        runBlocking {
            repo.clearCatalog()
            repo.updateSettings { it.copy(onboarded = false) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Qa.setLocale(sc, "en")
            val start = hasText(Qa.str("en", R.string.onb_start))
            compose.waitUntilAtLeastOneExists(start, 15_000)
            val started = System.currentTimeMillis()
            compose.onNode(start).performScrollTo().performClick()
            compose.waitUntil(20_000) { repo.status.value.running }
            settle(4_000)
            Qa.shot("live-00-searching")
            runBlocking { withTimeout(720_000) { repo.status.first { !it.running } } }
            val status = repo.status.value

            val snap = repo.catalog.value
            val bySource = snap.tracked.values.groupBy { it.listing.source.key }.mapValues { it.value.size }
            val s = Qa.settings()
            val views = repo.engine.views(s.criteria, FeedMode.ALL, work = s.work)
            val buckets = views.groupBy { it.bucket }.mapValues { it.value.size }
            val details = snap.tracked.values.count { it.detailsFetchedAt != null }
            Qa.log("live error=${status.lastError} in ${(System.currentTimeMillis() - started) / 1000}s ads=$bySource details=$details buckets=$buckets")
            snap.sources.values.forEach { Qa.log("source ${it.source.key}: ok=${it.ok} found=${it.found} pages=${it.pages} blocked=${it.blocked} ${it.message.orEmpty()}") }
            views.filter { it.bucket == FeedMode.MATCHES }.take(10).forEach { v ->
                Qa.log("match ${v.score.total} ${v.id} ${v.resolved.annualRent} am=${v.commute?.morningMinutes} ${v.listing.district} photos=${v.listing.photos.size}")
            }

            compose.waitUntilAtLeastOneExists(hasTestTag("card"), 20_000)
            settle()
            Qa.shot("live-01-feed")
            compose.onAllNodesWithTag("card").onFirst().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("detail_list"), 10_000)
            settle()
            Qa.shot("live-02-detail")
            compose.onAllNodesWithContentDescription(Qa.str("en", R.string.action_back)).onFirst().performClick()

            // Sites read in the browser: when one asked for a check, show what the check screen opens.
            val checked = snap.sources.values.firstOrNull { it.blocked && it.source in setOf(Source.BAYUT, Source.WASALT) }
            if (checked != null) {
                compose.onAllNodesWithTag("site_check_banner").onFirst().assertExists()
                compose.onAllNodesWithText(Qa.str("en", R.string.check_title, checked.source.nameEn)).onFirst().performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag("site_check_web"), 10_000)
                Thread.sleep(20_000)
                val passed = compose.onAllNodesWithTag("site_check_passed").fetchSemanticsNodes().isNotEmpty()
                Qa.log("live site check ${checked.source.key} passed=$passed")
                Qa.shot("live-02b-site-check-${checked.source.key}")
                compose.onAllNodesWithContentDescription(Qa.str("en", R.string.action_back)).onFirst().performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag("tab_map"), 10_000)
            }

            compose.onNodeWithTag("tab_map").performClick()
            val mapReady = runCatching { compose.waitUntilAtLeastOneExists(hasTestTag("map_ready"), 25_000) }.isSuccess
            Qa.log("live map ready=$mapReady")
            settle(6_000)
            Qa.shot("live-03-map")
            Qa.jsResult(sc, Qa.MAP_PROBE)
            assertTrue("map page laid out with no height", Qa.mapHeight(sc) > 0)
            Qa.webShot(sc, "live-03c-map-content")

            assertTrue("only $bySource", bySource.size >= 3)
            assertTrue("no matches: $buckets", (buckets[FeedMode.MATCHES] ?: 0) > 0)
        }
    }
}
