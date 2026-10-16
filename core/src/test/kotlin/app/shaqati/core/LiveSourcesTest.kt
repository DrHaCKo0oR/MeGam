package app.shaqati.core

import app.shaqati.core.filter.Criteria
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
import app.shaqati.core.util.OkHttpHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs every site adapter and the whole engine against the real sites.
 * Enabled only with -Dlive=true (CI "live" job); prints a report for review.
 */
class LiveSourcesTest {
    private val http = OkHttpHttp()
    private val work = RiyadhNorth.DEFAULT_WORK
    private val area = SearchArea(work, 9.0)
    private val criteria = Criteria()

    private fun live() = assumeTrue("live tests disabled", System.getProperty("live") == "true")

    @Test
    fun `every source returns parsable rentals and the engine ranks them`() = runBlocking {
        live()
        val sources: List<ListingSource> = listOf(AqarSource(), HarajSource(), PropertyFinderSource(), DealSource(), OpenSooqSource())
        val engine = Engine()
        val report = StringBuilder()
        val results = engine.refresh(sources, { http }, area, criteria, maxPages = 3, concurrency = 3)
        for (r in results) {
            val withPrice = r.listings.count { it.price != null }
            val withLoc = r.listings.count { it.location != null }
            val withPhotos = r.listings.count { it.photos.size >= 3 }
            report.appendLine(
                "SOURCE ${r.source.key}: ${r.listings.size} ads, pages=${r.pagesFetched}, price=$withPrice, location=$withLoc, " +
                    "3+photos=$withPhotos, blocked=${r.blocked}, errors=${r.errors}"
            )
            r.listings.take(3).forEach { l ->
                report.appendLine(
                    "  ${l.id} | ${l.title.take(50)} | ${l.price} ${l.period} | beds=${l.bedrooms} baths=${l.bathrooms} " +
                        "| ${l.district} | ${l.location} ${l.locationPrecision} | photos=${l.photos.size} | ${l.url.take(90)}"
                )
            }
        }

        // Ad pages for the sources that need them.
        val ctx = SearchContext(http, area, criteria, 3)
        for (src in listOf(PropertyFinderSource(), OpenSooqSource(), DealSource())) {
            val l = engine.snapshot.tracked.values.map { it.listing }.firstOrNull { it.source == src.source } ?: continue
            val full = runCatching { src.details(l, ctx) }
            report.appendLine(
                "DETAILS ${src.source.key}: " + full.fold(
                    { "photos ${l.photos.size}->${it.photos.size}, desc ${l.description.length}->${it.description.length}, loc=${it.locationPrecision}" },
                    { "failed: ${it.message}" },
                )
            )
            full.getOrNull()?.let { engine.updateListing(it) }
        }

        val all = engine.views(criteria, FeedMode.ALL, work = work)
        val byBucket = all.groupBy { it.bucket }.mapValues { it.value.size }
        report.appendLine("BUCKETS $byBucket (apartments after merging duplicates: ${all.size}, ads: ${engine.snapshot.tracked.size})")
        val failReasons = all.filter { it.bucket == FeedMode.ALL }.flatMap { it.evaluation.failed }.groupingBy { it }.eachCount()
        report.appendLine("FAILED CHECKS $failReasons")
        all.filter { it.bucket == FeedMode.MATCHES }.take(15).forEach { v ->
            val r = v.resolved
            report.appendLine(
                "MATCH ${v.score.total} ${v.id} | ${r.annualRent?.toInt()} SAR/yr (${v.listing.price?.toInt()} ${r.period}) | " +
                    "beds=${r.bedrooms}${if (r.hasMajlis) "+majlis" else ""} living=${r.livingRooms} baths=${r.bathrooms} | " +
                    "${r.furnishing} ${r.audience} | ${v.listing.district} | am=${v.commute?.morningMinutes}min | " +
                    "text=${r.text.renovationScore} ${r.text.positive.map { it.phrase }} | sources=${v.sources.map { it.key }} | ${v.listing.url.take(80)}"
            )
        }
        all.filter { it.bucket == FeedMode.UNCONFIRMED }.take(8).forEach { v ->
            report.appendLine("UNCONFIRMED ${v.id} missing=${v.evaluation.unconfirmed} | ${v.listing.title.take(60)}")
        }
        val dupes = all.filter { it.alsoOn.size > 1 }
        report.appendLine("DUPLICATE GROUPS ${dupes.size}: " + dupes.take(5).joinToString { g -> g.alsoOn.joinToString("+") { it.id } })

        println(report)
        File("build/live-report.txt").writeText(report.toString())
        val ok = results.filter { it.listings.isNotEmpty() }.map { it.source }
        assertTrue(Source.AQAR in ok, "Aqar returned nothing")
        assertTrue(ok.size >= 3, "only $ok returned listings")
    }
}
