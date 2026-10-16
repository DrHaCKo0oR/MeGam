package app.shaqati.core

import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.Prompt
import app.shaqati.core.commute.CommuteEstimate
import app.shaqati.core.commute.CommuteService
import app.shaqati.core.dedupe.Dedupe
import app.shaqati.core.filter.Check
import app.shaqati.core.filter.Criteria
import app.shaqati.core.filter.Evaluation
import app.shaqati.core.filter.Evaluator
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.Resolved
import app.shaqati.core.model.Resolver
import app.shaqati.core.model.Source
import app.shaqati.core.rank.MatchScore
import app.shaqati.core.rank.Ranker
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.sources.SearchResult
import app.shaqati.core.store.AiSpend
import app.shaqati.core.store.CatalogSnapshot
import app.shaqati.core.store.HideReason
import app.shaqati.core.store.PendingBatch
import app.shaqati.core.store.SourceStatus
import app.shaqati.core.store.Tracked
import app.shaqati.core.store.UserState
import app.shaqati.core.util.Geo
import app.shaqati.core.util.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneOffset

/** What the screens show for one apartment (one group of duplicate ads). */
data class ListingView(
    val listing: Listing,
    val resolved: Resolved,
    val evaluation: Evaluation,
    val commute: CommuteEstimate?,
    val ai: AiAssessment?,
    val score: MatchScore,
    /** All ads for this apartment, including [listing]. */
    val alsoOn: List<Listing>,
    val saved: Boolean,
    val hidden: Boolean,
    val firstSeenAt: Long,
    val isNew: Boolean,
    val gone: Boolean,
) {
    val id: String get() = listing.id
    val sources: List<Source> get() = alsoOn.map { it.source }.distinct()
}

enum class Sort { BEST, NEWEST, CHEAPEST, CLOSEST, MODERN }

/** How strict the feed is. */
enum class FeedMode {
    /** Meets every stated requirement; unknown details allowed but labelled. */
    MATCHES,

    /** Fails exactly one soft requirement (commute, rent, rooms) by a small margin. */
    NEAR_MISSES,

    /** Everything in the area, for browsing. */
    ALL,
}

/**
 * The app's brain: merges source results into the catalog, resolves fields,
 * applies the user's requirements, links duplicates and ranks.
 * Thread-safe; all state lives in one immutable snapshot.
 */
class Engine(
    initial: CatalogSnapshot = CatalogSnapshot(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    var snapshot: CatalogSnapshot = initial
        private set

    private var resolvedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Listing, Resolved>>()

    fun resolved(l: Listing): Resolved {
        val hit = resolvedCache[l.id]
        if (hit != null && hit.first == l) return hit.second
        return Resolver.resolve(l).also { resolvedCache[l.id] = l to it }
    }

    /** Runs every source (a few at a time) and merges results. Failures never remove listings. */
    suspend fun refresh(
        sources: List<ListingSource>,
        httpFor: (Source) -> Http,
        area: SearchArea,
        criteria: Criteria,
        maxPages: Int = 5,
        concurrency: Int = 3,
        log: (String) -> Unit = {},
    ): List<SearchResult> = coroutineScope {
        val gate = Semaphore(concurrency)
        val results = sources.map { src ->
            async {
                gate.withPermit {
                    runCatching {
                        src.search(SearchContext(httpFor(src.source), area, criteria, maxPages, clock(), log))
                    }.getOrElse { e ->
                        SearchResult(src.source, emptyList(), 0, listOf(e.message ?: e.toString()))
                    }
                }
            }
        }.map { it.await() }
        mutex.withLock { results.forEach { merge(it) } }
        results
    }

    private fun merge(result: SearchResult) {
        val now = clock()
        val s = snapshot
        val tracked = s.tracked.toMutableMap()
        val seenIds = HashSet<String>()
        for (l in result.listings) {
            seenIds += l.id
            val prev = tracked[l.id]
            tracked[l.id] = if (prev == null) {
                Tracked(l, firstSeenAt = now, lastSeenAt = now)
            } else {
                Tracked(mergeListing(prev.listing, l), prev.firstSeenAt, now, goneAt = null, detailsFetchedAt = prev.detailsFetchedAt)
            }
        }
        // A clean run that returned results is a complete view of that source's
        // current inventory (within the pages fetched): ads not seen for 3 days are gone.
        if (result.errors.isEmpty() && !result.blocked && result.listings.isNotEmpty()) {
            for ((id, t) in tracked) {
                if (t.listing.source == result.source && id !in seenIds && t.goneAt == null &&
                    now - t.lastSeenAt > 3 * Evaluator.DAY_MS
                ) tracked[id] = t.copy(goneAt = now)
            }
        }
        val status = SourceStatus(
            source = result.source,
            lastRunAt = now,
            ok = result.errors.isEmpty() && !result.blocked,
            found = result.listings.size,
            pages = result.pagesFetched,
            blocked = result.blocked,
            message = result.errors.firstOrNull()?.take(300),
        )
        snapshot = s.copy(tracked = tracked, sources = s.sources + (result.source.key to status), lastRefreshAt = now)
    }

    /** Keeps richer data from either copy: detail pages carry more than list pages. */
    private fun mergeListing(old: Listing, new: Listing): Listing = new.copy(
        description = new.description.ifBlank { old.description }.let { if (old.description.length > it.length) old.description else it },
        photos = if (new.photos.size >= old.photos.size) new.photos else old.photos,
        videos = new.videos.ifEmpty { old.videos },
        phone = new.phone ?: old.phone,
        whatsapp = new.whatsapp ?: old.whatsapp,
        location = new.location ?: old.location,
        locationPrecision = if (new.location != null) new.locationPrecision else old.locationPrecision,
        bathrooms = new.bathrooms ?: old.bathrooms,
        bedrooms = new.bedrooms ?: old.bedrooms,
        livingRooms = new.livingRooms ?: old.livingRooms,
        areaSqm = new.areaSqm ?: old.areaSqm,
        licenseNumber = new.licenseNumber ?: old.licenseNumber,
        advertiserName = new.advertiserName ?: old.advertiserName,
        buildingAgeYears = new.buildingAgeYears ?: old.buildingAgeYears,
        postedAt = new.postedAt ?: old.postedAt,
        features = old.features + new.features,
    )

    suspend fun updateListing(l: Listing, detailsFetched: Boolean = true) = mutex.withLock {
        val s = snapshot
        val prev = s.tracked[l.id] ?: return@withLock
        val merged = mergeListing(prev.listing, l)
        snapshot = s.copy(tracked = s.tracked + (l.id to prev.copy(listing = merged, detailsFetchedAt = if (detailsFetched) clock() else prev.detailsFetchedAt)))
    }

    /** Listings near work that still need a commute forecast. */
    fun commuteCandidates(work: GeoPoint, criteria: Criteria, maxStraightKm: Double = 25.0): List<GeoPoint> {
        val s = snapshot
        return s.tracked.values.asSequence()
            .filter { it.goneAt == null }
            .mapNotNull { t -> t.listing.location?.takeIf { Geo.inRiyadh(it) } }
            .filter { Geo.distanceKm(it, work) <= maxStraightKm }
            .filter { s.commute[Geo.cellKey(it, 3)] == null }
            .distinct()
            .toList()
    }

    suspend fun computeCommutes(service: CommuteService, work: GeoPoint, criteria: Criteria, limit: Int = 300): Int {
        val homes = commuteCandidates(work, criteria).take(limit)
        if (homes.isEmpty()) return 0
        val est = service.estimate(homes, work)
        mutex.withLock {
            val s = snapshot
            snapshot = s.copy(commute = s.commute + est.mapKeys { Geo.cellKey(it.key, 3) })
        }
        return est.size
    }

    fun commuteFor(l: Listing): CommuteEstimate? = l.location?.let { snapshot.commute[Geo.cellKey(it, 3)] }

    /** Drops cached commute results, e.g. after the work pin or calibration changes. */
    suspend fun clearCommutes() = mutex.withLock { snapshot = snapshot.copy(commute = emptyMap()) }

    fun views(criteria: Criteria, mode: FeedMode = FeedMode.MATCHES, sort: Sort = Sort.BEST, includeHidden: Boolean = false): List<ListingView> {
        val s = snapshot
        val now = clock()
        val live = s.tracked.values.filter { it.goneAt == null }
        val items = live.map { Dedupe.Item(it.listing, resolved(it.listing)) }
        val groups = Dedupe.group(items)
        val byGroup = items.groupBy { groups[it.listing.id] ?: it.listing.id }
        val out = ArrayList<ListingView>(byGroup.size)
        for ((gid, members) in byGroup) {
            val main = members.first { it.listing.id == gid }
            val l = main.listing
            val r = main.resolved
            val commute = commuteFor(l)
            val ai = s.ai[gid] ?: members.firstNotNullOfOrNull { s.ai[it.listing.id] }
            val eval = Evaluator.evaluate(l, r, criteria, commute?.morningMinutes, commute?.eveningMinutes, now)
            val ids = members.map { it.listing.id }
            val hidden = ids.any { it in s.user.hidden }
            if (hidden && !includeHidden) continue
            val keep = when (mode) {
                FeedMode.MATCHES -> eval.passes
                FeedMode.NEAR_MISSES -> !eval.passes && nearMiss(eval, r, commute, criteria)
                FeedMode.ALL -> true
            }
            if (!keep) continue
            val first = members.minOf { s.tracked[it.listing.id]!!.firstSeenAt }
            out += ListingView(
                listing = l,
                resolved = r,
                evaluation = eval,
                commute = commute,
                ai = ai,
                score = Ranker.score(l, r, criteria, commute, ai, now),
                alsoOn = members.map { it.listing },
                saved = ids.any { it in s.user.saved },
                hidden = hidden,
                firstSeenAt = first,
                isNew = ids.none { it in s.user.seen } && now - first < 3 * Evaluator.DAY_MS,
                gone = false,
            )
        }
        return sort(out, sort)
    }

    private fun nearMiss(e: Evaluation, r: Resolved, c: CommuteEstimate?, k: Criteria): Boolean {
        if (e.failed.size != 1) return false
        return when (e.failed.single()) {
            Check.COMMUTE_AM -> (c?.morningMinutes ?: Int.MAX_VALUE) <= k.maxMorningMinutes + 10
            Check.RENT -> (r.annualRent ?: Double.MAX_VALUE) <= k.maxAnnualRent * 1.1
            Check.BATHROOMS -> (r.bathrooms ?: 0) >= k.minBathrooms - 1
            Check.ROOMS -> (r.bedrooms ?: 0) >= k.minRooms - 1
            else -> false
        }
    }

    private fun sort(list: List<ListingView>, sort: Sort): List<ListingView> = when (sort) {
        Sort.BEST -> list.sortedWith(compareByDescending<ListingView> { it.score.total }.thenByDescending { it.firstSeenAt })
        Sort.NEWEST -> list.sortedByDescending { it.listing.postedAt ?: it.firstSeenAt }
        Sort.CHEAPEST -> list.sortedBy { it.resolved.annualRent ?: Double.MAX_VALUE }
        Sort.CLOSEST -> list.sortedBy { it.commute?.morningMinutes ?: Int.MAX_VALUE }
        Sort.MODERN -> list.sortedByDescending { it.score.finish }
    }

    fun savedViews(criteria: Criteria): List<ListingView> {
        val saved = snapshot.user.saved
        return views(criteria, FeedMode.ALL, Sort.BEST, includeHidden = true)
            .filter { v -> v.alsoOn.any { it.id in saved } }
            .sortedByDescending { v -> v.alsoOn.maxOf { saved[it.id] ?: 0L } }
    }

    fun view(id: String, criteria: Criteria): ListingView? =
        views(criteria, FeedMode.ALL, includeHidden = true).firstOrNull { v -> v.alsoOn.any { it.id == id } }

    // ---- user actions ----

    suspend fun setSaved(id: String, saved: Boolean) = editUser {
        it.copy(saved = if (saved) it.saved + (id to clock()) else it.saved - id)
    }

    suspend fun hide(id: String, reason: HideReason?) = editUser { it.copy(hidden = it.hidden + (id to reason), saved = it.saved - id) }

    suspend fun unhide(id: String) = editUser { it.copy(hidden = it.hidden - id) }

    suspend fun markSeen(ids: Collection<String>) = editUser { it.copy(seen = it.seen + ids) }

    suspend fun setNote(id: String, note: String) = editUser {
        it.copy(notes = if (note.isBlank()) it.notes - id else it.notes + (id to note))
    }

    private suspend fun editUser(f: (UserState) -> UserState) = mutex.withLock {
        snapshot = snapshot.copy(user = f(snapshot.user))
    }

    // ---- AI ----

    /**
     * Listings worth assessing: they meet the requirements (unknowns allowed),
     * have enough photos, are not hidden, and were not assessed before.
     */
    fun aiQueue(criteria: Criteria, minPhotos: Int = 3): List<ListingView> {
        val s = snapshot
        val pending = s.batches.flatMap { it.listingIds }.toSet()
        return views(criteria, FeedMode.MATCHES)
            .filter { it.ai == null && it.id !in pending && it.listing.photos.size >= minPhotos }
            .filter { it.resolved.text.renovationScore > -4 }
            .sortedByDescending { it.score.total }
    }

    fun photosForAi(l: Listing): List<String> = l.photos.distinct().take(Prompt.MAX_PHOTOS)

    suspend fun saveAssessment(a: AiAssessment) = mutex.withLock {
        val s = snapshot
        val month = monthKey(a.assessedAt)
        val spend = s.spend.toMutableList()
        val i = spend.indexOfFirst { it.month == month }
        if (i >= 0) spend[i] = spend[i].copy(usd = spend[i].usd + a.costUsd, assessed = spend[i].assessed + 1)
        else spend += AiSpend(month, a.costUsd, 1)
        snapshot = s.copy(ai = s.ai + (a.listingId to a), spend = spend.takeLast(24))
    }

    fun spentThisMonth(): Double = snapshot.spend.firstOrNull { it.month == monthKey(clock()) }?.usd ?: 0.0

    suspend fun addBatch(b: PendingBatch) = mutex.withLock { snapshot = snapshot.copy(batches = snapshot.batches + b) }

    suspend fun removeBatch(id: String) = mutex.withLock { snapshot = snapshot.copy(batches = snapshot.batches.filterNot { it.batchId == id }) }

    suspend fun replace(newSnapshot: CatalogSnapshot) = mutex.withLock {
        snapshot = newSnapshot
        resolvedCache = java.util.concurrent.ConcurrentHashMap()
    }

    private fun monthKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }
}
