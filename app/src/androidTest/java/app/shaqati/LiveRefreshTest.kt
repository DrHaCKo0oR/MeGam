package app.shaqati

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shaqati.core.FeedMode
import app.shaqati.ui.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real search from the phone: every site, ad pages and commute, then the screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LiveRefreshTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun searchesTheLiveSites() {
        val repo = Qa.repo
        runBlocking {
            repo.clearCatalog()
            repo.updateSettings { it.copy(onboarded = true) }
        }
        Qa.setLocale("en")
        ActivityScenario.launch(MainActivity::class.java).use {
            val started = System.currentTimeMillis()
            val ok = runBlocking { withTimeout(420_000) { repo.refresh() } }
            val snap = repo.catalog.value
            val bySource = snap.tracked.values.groupBy { it.listing.source.key }.mapValues { it.value.size }
            val s = Qa.settings()
            val views = repo.engine.views(s.criteria, FeedMode.ALL, work = s.work)
            val buckets = views.groupBy { it.bucket }.mapValues { it.value.size }
            val details = snap.tracked.values.count { it.detailsFetchedAt != null }
            Qa.log("live ok=$ok in ${(System.currentTimeMillis() - started) / 1000}s ads=$bySource details=$details buckets=$buckets")
            snap.sources.values.forEach { Qa.log("source ${it.source.key}: ok=${it.ok} found=${it.found} pages=${it.pages} blocked=${it.blocked} ${it.message.orEmpty()}") }
            views.filter { it.bucket == FeedMode.MATCHES }.take(10).forEach { v ->
                Qa.log("match ${v.score.total} ${v.id} ${v.resolved.annualRent} am=${v.commute?.morningMinutes} ${v.listing.district} photos=${v.listing.photos.size}")
            }
            compose.waitUntilAtLeastOneExists(hasText(Qa.str("en", R.string.feed_matches), substring = true), 10_000)
            Qa.shot("live-01-feed")
            if (compose.onAllNodesWithTag("card").fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodesWithTag("card").onFirst().performClick()
                Thread.sleep(2_500)
                Qa.shot("live-02-detail")
            }
            assertTrue("only $bySource", bySource.size >= 3)
        }
    }
}
