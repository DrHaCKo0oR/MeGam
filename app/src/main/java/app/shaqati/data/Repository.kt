package app.shaqati.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.shaqati.core.Engine
import app.shaqati.core.FeedMode
import app.shaqati.core.ListingView
import app.shaqati.core.ai.AiException
import app.shaqati.core.ai.AnthropicAi
import app.shaqati.core.ai.PreparedImage
import app.shaqati.core.ai.Pricing
import app.shaqati.core.commute.CommuteEstimate
import app.shaqati.core.commute.CommuteMethod
import app.shaqati.core.commute.CommuteService
import app.shaqati.core.commute.Correction
import app.shaqati.core.commute.GoogleRoutesClient
import app.shaqati.core.filter.Check
import app.shaqati.core.model.Source
import app.shaqati.core.sources.AqarSource
import app.shaqati.core.sources.BayutSource
import app.shaqati.core.sources.DealSource
import app.shaqati.core.sources.HarajSource
import app.shaqati.core.sources.ListingSource
import app.shaqati.core.sources.OpenSooqSource
import app.shaqati.core.sources.PropertyFinderSource
import app.shaqati.core.sources.SearchArea
import app.shaqati.core.sources.SearchContext
import app.shaqati.core.sources.SearchResult
import app.shaqati.core.sources.WasaltSource
import app.shaqati.core.store.CatalogSnapshot
import app.shaqati.core.store.CatalogStore
import app.shaqati.core.store.HideReason
import app.shaqati.core.store.PendingBatch
import app.shaqati.core.util.FetchMode
import app.shaqati.core.util.Geo
import app.shaqati.core.util.Http
import app.shaqati.core.util.OkHttpHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.min

enum class SourceRun { WAITING, RUNNING, DONE, FAILED }

data class SyncStatus(
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val sources: Map<Source, SourceRun> = emptyMap(),
    val progress: String = "",
    val lastError: String? = null,
    val lastFinishedAt: Long? = null,
    /** Short note about photo analysis (sent, cap reached, key rejected). */
    val aiNote: AiNote? = null,
) {
    enum class Phase { IDLE, SEARCHING, DETAILS, COMMUTE, PHOTOS }
}

enum class AiNote { SUBMITTED, CAP_REACHED, KEY_REJECTED, FAILED, NOTHING_NEW }

/**
 * Runs searches, detail fetches, commute forecasts and photo analysis, and keeps the
 * catalog on disk. All long jobs share one lock so they never overlap.
 */
class Repository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
    private val settingsStore: SettingsStore,
    val vault: KeyVault,
) {
    private val catalogStore = CatalogStore(File(context.filesDir, "catalog.json"))
    private val commuteCacheFile = File(context.filesDir, "commute_cache.json")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val commuteCacheSerializer = MapSerializer(String.serializer(), CommuteEstimate.serializer())

    val engine = Engine(catalogStore.load())

    private val direct: Http = OkHttpHttp(okHttp)
    private val browser: Http by lazy { WebViewHttp(context) }
    private val http = Http { req -> if (req.mode == FetchMode.BROWSER) browser.send(req) else direct.send(req) }
    private val images = AndroidImagePreparer(okHttp)

    private val _catalog = MutableStateFlow(engine.snapshot)
    val catalog: StateFlow<CatalogSnapshot> = _catalog.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus(lastFinishedAt = engine.snapshot.lastRefreshAt))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Null until the stored settings have been read. */
    val settings: StateFlow<Settings?> = settingsStore.flow.stateIn(scope, SharingStarted.Eagerly, null)

    private val jobLock = Mutex()
    private val saveLock = Mutex()
    private var saveJob: Job? = null

    private val deal = DealSource()
    private val pf = PropertyFinderSource()
    private val openSooq = OpenSooqSource()
    private val bayut = BayutSource()
    private val wasalt = WasaltSource()
    private val detailSources: Map<Source, ListingSource> = mapOf(
        Source.PROPERTY_FINDER to pf,
        Source.OPENSOOQ to openSooq,
        Source.DEAL to deal,
        Source.BAYUT to bayut,
        Source.WASALT to wasalt,
    )

    private var service: CommuteService? = null
    private var serviceSignature: String? = null

    fun start() {
        scope.launch {
            settingsStore.flow.map { it.autoRefreshHours }.distinctUntilChanged().collect { scheduleRefresh(it) }
        }
        // Commute results depend on the work pin, schedule, calibration and the Google key.
        scope.launch {
            combine(settingsStore.flow, vault.version) { s, _ -> commuteSignature(s) }
                .distinctUntilChanged()
                .collect { sig ->
                    val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
                    if (prefs.getString("commute_sig", null) != sig) {
                        jobLock.withLock {
                            val s = settingsStore.current()
                            engine.clearCommutes()
                            runCatching { ensureCalibration(s) }
                            runCatching { updateCommutes(s) }
                            prefs.edit().putString("commute_sig", sig).apply()
                            publish()
                            saveSoon()
                        }
                    }
                }
        }
        if (engine.snapshot.batches.isNotEmpty()) scheduleBatchPoll(1)
    }

    // ---------------------------------------------------------------- search

    private fun sources(s: Settings): List<ListingSource> = buildList {
        if (Source.AQAR in s.enabledSources) add(AqarSource())
        // Read page by page in the browser, so they start early.
        if (Source.BAYUT in s.enabledSources) add(bayut)
        if (Source.WASALT in s.enabledSources) add(wasalt)
        if (Source.HARAJ in s.enabledSources) add(HarajSource())
        if (Source.PROPERTY_FINDER in s.enabledSources) add(pf)
        if (Source.DEAL in s.enabledSources) add(deal)
        if (Source.OPENSOOQ in s.enabledSources) add(openSooq)
    }

    /** Reports each site's progress to [status]. */
    private inner class Reporting(private val inner: ListingSource) : ListingSource {
        override val source: Source = inner.source

        override suspend fun search(ctx: SearchContext): SearchResult {
            setSource(source, SourceRun.RUNNING)
            val r = try {
                inner.search(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchResult(source, emptyList(), 0, listOf(e.message ?: e.toString()))
            }
            setSource(source, if (r.errors.isEmpty() && !r.blocked) SourceRun.DONE else SourceRun.FAILED)
            return r
        }
    }

    private fun setSource(src: Source, run: SourceRun) = _status.update { it.copy(sources = it.sources + (src to run)) }

    /** Full refresh. Returns false when a refresh is already running. */
    suspend fun refresh(): Boolean {
        if (_status.value.running) return false
        _status.update { it.copy(running = true, phase = SyncStatus.Phase.SEARCHING, lastError = null, aiNote = null, progress = "") }
        return try {
            jobLock.withLock { refreshLocked() }
            _status.update { it.copy(lastFinishedAt = System.currentTimeMillis()) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.update { it.copy(lastError = e.message ?: e.toString()) }
            false
        } finally {
            _status.update { it.copy(running = false, phase = SyncStatus.Phase.IDLE, progress = "") }
        }
    }

    private suspend fun refreshLocked() {
        val s = settingsStore.current()
        val srcs = sources(s)
        _status.update { it.copy(sources = srcs.associate { src -> src.source to SourceRun.WAITING }) }
        val area = SearchArea(s.work, s.searchRadiusKm)
        engine.refresh(srcs.map { Reporting(it) }, { http }, area, s.criteria, s.maxPages, concurrency = 3)
        prune()
        publish()

        _status.update { it.copy(phase = SyncStatus.Phase.DETAILS, progress = "") }
        enrichDetails(s)
        publish()

        _status.update { it.copy(phase = SyncStatus.Phase.COMMUTE, progress = "") }
        runCatching { ensureCalibration(s) }
        runCatching { updateCommutes(settingsStore.current()) }
        publish()

        if (s.ai.enabled && vault.has(KeyVault.ANTHROPIC)) {
            _status.update { it.copy(phase = SyncStatus.Phase.PHOTOS, progress = "") }
            runCatching { pollBatchesLocked() }
            val note = try {
                runAi(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is AiException && e.unauthorized) AiNote.KEY_REJECTED else AiNote.FAILED
            }
            _status.update { it.copy(aiNote = note) }
        }
        publish()
        saveNow()
    }

    /** Opens ad pages for promising listings to get every photo, the full text and exact pins. */
    private suspend fun enrichDetails(s: Settings) {
        val snapshot = engine.snapshot
        val todo = engine.views(s.criteria, FeedMode.ALL, work = s.work)
            .filter { it.bucket != FeedMode.ALL }
            .sortedByDescending { it.score.total }
            .asSequence()
            .flatMap { it.alsoOn.asSequence() }
            .filter { it.source in detailSources && snapshot.tracked[it.id]?.detailsFetchedAt == null }
            .distinctBy { it.id }
            .take(MAX_DETAILS_PER_RUN)
            .toList()
        val ctx = SearchContext(http, SearchArea(s.work, s.searchRadiusKm), s.criteria, s.maxPages)
        todo.forEachIndexed { i, l ->
            _status.update { it.copy(progress = "${i + 1}/${todo.size}") }
            val full = try {
                detailSources.getValue(l.source).details(l, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (full != null) engine.updateListing(full, detailsFetched = true)
            if (i % 5 == 4) publish()
        }
    }

    /** Drops ads that disappeared a month ago or are very old, unless saved or noted. */
    private suspend fun prune() {
        val s = engine.snapshot
        val now = System.currentTimeMillis()
        val keep = s.tracked.filter { (id, t) ->
            id in s.user.saved || id in s.user.notes ||
                ((t.goneAt == null || now - t.goneAt!! < 30 * DAY) && now - t.lastSeenAt < 120 * DAY)
        }
        if (keep.size == s.tracked.size) return
        val ids = keep.keys
        engine.replace(
            s.copy(
                tracked = keep,
                ai = s.ai.filterKeys { it in ids },
                user = s.user.copy(hidden = s.user.hidden.filterKeys { it in ids }, seen = s.user.seen.filter { it in ids }.toSet()),
            )
        )
    }

    // ---------------------------------------------------------------- commute

    private fun calibrationSignature(s: Settings): String =
        "${s.work}|${s.calibration.home}|${s.calibration.actualMorningMinutes}|${s.calibration.actualEveningMinutes}|" +
            "${s.calibration.enabled}|${s.schedule}"

    private fun commuteSignature(s: Settings): String =
        "${calibrationSignature(s)}|${vault.get(KeyVault.GOOGLE)?.hashCode()}"

    private fun commuteService(s: Settings): CommuteService {
        val key = vault.get(KeyVault.GOOGLE)
        val sig = "${key?.hashCode()}|${s.schedule}"
        val existing = service
        if (existing != null && serviceSignature == sig) return existing
        val fresh = CommuteService(key?.let { GoogleRoutesClient(direct, it) }, s.schedule)
        (existing?.cache ?: loadCommuteCache()).let { fresh.cache.putAll(it) }
        service = fresh
        serviceSignature = sig
        return fresh
    }

    /** Learns the door-to-door correction from the user's real trip when a Google key is set. */
    private suspend fun ensureCalibration(s: Settings) {
        val svc = commuteService(s)
        val sig = calibrationSignature(s)
        val learned = s.corrections
        val fresh = learned != null && learned.signature == sig && System.currentTimeMillis() - learned.learnedAt < 30 * DAY
        if (!vault.has(KeyVault.GOOGLE) || !s.calibration.enabled) {
            svc.setCorrections(Correction(), Correction())
            return
        }
        if (fresh) {
            svc.setCorrections(learned!!.morning, learned.evening)
            return
        }
        val forecast = svc.calibrate(s.calibration, s.work) ?: return
        val (am, pm) = svc.corrections
        settingsStore.update {
            it.copy(corrections = LearnedCorrections(am, pm, forecast.first, forecast.second, System.currentTimeMillis(), sig))
        }
    }

    private suspend fun updateCommutes(s: Settings) {
        val svc = commuteService(s)
        val google = vault.has(KeyVault.GOOGLE)
        val c = s.criteria
        val homes = engine.views(c, FeedMode.ALL, work = s.work)
            .asSequence()
            .filter { v -> v.listing.location != null }
            .filter { v ->
                val stored = engine.commuteFor(v.listing)
                stored == null || (google && stored.method != CommuteMethod.GOOGLE_TRAFFIC)
            }
            // Only where the commute decides whether it matches.
            .filter { v -> v.bucket != FeedMode.ALL || v.evaluation.failed.all { it == Check.COMMUTE_AM || it == Check.COMMUTE_PM } }
            .filter { v -> (v.commute?.morningMinutes ?: 0) <= c.maxMorningMinutes + 35 }
            .mapNotNull { it.listing.location }
            .distinctBy { Geo.cellKey(it, 3) }
            .take(MAX_COMMUTES_PER_RUN)
            .toList()
        if (homes.isEmpty()) return
        _status.update { it.copy(progress = "${homes.size}") }
        engine.putCommutes(svc.estimate(homes, s.work))
        saveCommuteCache(svc)
    }

    private fun loadCommuteCache(): Map<String, CommuteEstimate> = runCatching {
        if (!commuteCacheFile.exists()) emptyMap() else json.decodeFromString(commuteCacheSerializer, commuteCacheFile.readText())
    }.getOrDefault(emptyMap())

    private fun saveCommuteCache(svc: CommuteService) = runCatching {
        val tmp = File(commuteCacheFile.parentFile, commuteCacheFile.name + ".tmp")
        tmp.writeText(json.encodeToString(commuteCacheSerializer, svc.cache.toMap()))
        tmp.renameTo(commuteCacheFile)
    }

    /** Re-learns the calibration now (after the user edits the trip or adds a key). */
    suspend fun recalibrate(): LearnedCorrections? = jobLock.withLock {
        settingsStore.update { it.copy(corrections = null) }
        val s = settingsStore.current()
        runCatching { ensureCalibration(s) }
        engine.clearCommutes()
        runCatching { updateCommutes(settingsStore.current()) }
        publish()
        saveSoon()
        settingsStore.current().corrections
    }

    // ---------------------------------------------------------------- photo analysis

    private suspend fun prepareImages(urls: List<String>): List<Pair<String, PreparedImage>> = coroutineScope {
        val gate = Semaphore(4)
        urls.map { u -> async { gate.withPermit { images.prepare(u)?.let { u to it } } } }.awaitAll().filterNotNull()
    }

    /** Estimated cost per listing with the current settings. */
    fun aiCostPerListing(s: Settings): Double = Pricing.estimate(s.ai.photosPerListing, s.ai.useBatch)

    private fun budgetLeft(s: Settings): Double {
        val pending = engine.snapshot.batches.sumOf { it.listingIds.size } * Pricing.estimate(s.ai.photosPerListing, true)
        return s.ai.monthlyCapUsd - engine.spentThisMonth() - pending
    }

    private suspend fun runAi(s: Settings): AiNote {
        val key = vault.get(KeyVault.ANTHROPIC) ?: return AiNote.FAILED
        val queue = engine.aiQueue(s.criteria, s.work).take(s.ai.maxPerRefresh)
        if (queue.isEmpty()) return AiNote.NOTHING_NEW
        val per = aiCostPerListing(s)
        val n = min(queue.size, floor(budgetLeft(s) / per).toInt())
        if (n <= 0) return AiNote.CAP_REACHED
        val ai = AnthropicAi(key)
        val prepared = mutableListOf<Triple<ListingView, List<String>, List<PreparedImage>>>()
        for ((i, v) in queue.take(n).withIndex()) {
            _status.update { it.copy(progress = "${i + 1}/$n") }
            val imgs = prepareImages(engine.photosForAi(v.listing).take(s.ai.photosPerListing))
            if (imgs.size >= 2) prepared += Triple(v, imgs.map { it.first }, imgs.map { it.second })
        }
        if (prepared.isEmpty()) return AiNote.NOTHING_NEW
        if (s.ai.useBatch) {
            val requests = prepared.mapIndexed { i, (v, _, imgs) -> "i$i" to ai.params(v.listing, v.resolved, imgs) }
            val id = ai.submitBatch(requests)
            engine.addBatch(
                PendingBatch(id, prepared.map { it.first.id }, prepared.associate { it.first.id to it.second }, System.currentTimeMillis())
            )
            scheduleBatchPoll(15)
        } else {
            for ((v, urls, imgs) in prepared) {
                engine.saveAssessment(ai.assess(v.listing, v.resolved, imgs, urls))
                publish()
            }
        }
        return AiNote.SUBMITTED
    }

    /** Collects finished batch results. Returns how many listings got an assessment. */
    suspend fun pollBatches(): Int = jobLock.withLock { pollBatchesLocked() }

    private suspend fun pollBatchesLocked(): Int {
        val key = vault.get(KeyVault.ANTHROPIC) ?: return 0
        val batches = engine.snapshot.batches
        if (batches.isEmpty()) return 0
        val ai = AnthropicAi(key)
        var saved = 0
        for (b in batches) {
            val state = try {
                ai.batchState(b.batchId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (System.currentTimeMillis() - b.submittedAt > 3 * DAY) engine.removeBatch(b.batchId)
                continue
            }
            if (!state.ended) continue
            val results = ai.batchResults(b.batchId)
            b.listingIds.forEachIndexed { i, id ->
                results["i$i"]?.getOrNull()?.let { msg ->
                    runCatching { ai.toAssessment(id, msg, b.photos[id].orEmpty(), batch = true) }
                        .onSuccess { engine.saveAssessment(it); saved++ }
                }
            }
            engine.removeBatch(b.batchId)
        }
        publish()
        saveSoon()
        return saved
    }

    fun hasPendingBatches(): Boolean = engine.snapshot.batches.isNotEmpty()

    /** Analyses one listing's photos right away (full price). */
    suspend fun assessNow(id: String): Result<Unit> = runCatching {
        val s = settingsStore.current()
        val key = vault.get(KeyVault.ANTHROPIC) ?: throw AiException("No Anthropic API key.")
        val v = engine.view(id, s.criteria, s.work) ?: throw AiException("Listing not found.")
        val instant = s.copy(ai = s.ai.copy(useBatch = false))
        if (budgetLeft(instant) < aiCostPerListing(instant)) throw AiException("Monthly limit reached.")
        val imgs = prepareImages(engine.photosForAi(v.listing).take(s.ai.photosPerListing))
        if (imgs.size < 2) throw AiException("Could not download enough photos.")
        val a = AnthropicAi(key).assess(v.listing, v.resolved, imgs.map { it.second }, imgs.map { it.first })
        engine.saveAssessment(a)
        publish()
        saveSoon()
    }

    suspend fun verifyAnthropicKey(key: String): Boolean = runCatching { AnthropicAi(key).verifyKey() }.getOrDefault(false)

    /** Checks a Google key with one route request; returns the minutes or null. */
    suspend fun verifyGoogleKey(key: String): Int? = runCatching {
        val s = settingsStore.current()
        val svc = CommuteService(GoogleRoutesClient(direct, key), s.schedule)
        svc.calibrate(s.calibration.copy(enabled = true), s.work)?.first
    }.getOrNull()

    // ---------------------------------------------------------------- user actions

    fun setSaved(id: String, saved: Boolean) = edit { engine.setSaved(id, saved) }
    fun hide(id: String, reason: HideReason?) = edit { engine.hide(id, reason) }
    fun unhide(id: String) = edit { engine.unhide(id) }
    fun setNote(id: String, note: String) = edit { engine.setNote(id, note) }
    fun markSeen(ids: Collection<String>) = edit { engine.markSeen(ids) }

    private fun edit(block: suspend () -> Unit) {
        scope.launch {
            block()
            publish()
            saveSoon()
        }
    }

    suspend fun updateSettings(change: (Settings) -> Settings): Settings = settingsStore.update(change)

    /** Replaces the catalog (restores and on-device tests). */
    suspend fun importSnapshot(snapshot: CatalogSnapshot) = jobLock.withLock {
        engine.replace(snapshot)
        publish()
        saveNow()
    }

    suspend fun clearCatalog() = jobLock.withLock {
        engine.replace(CatalogSnapshot(user = engine.snapshot.user.copy(seen = emptySet())))
        publish()
        saveNow()
    }

    // ---------------------------------------------------------------- persistence and scheduling

    private fun publish() {
        _catalog.value = engine.snapshot
    }

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_500)
            saveNow()
        }
    }

    suspend fun saveNow() = withContext(Dispatchers.IO) {
        saveLock.withLock {
            runCatching { catalogStore.save(engine.snapshot) }
            service?.let { saveCommuteCache(it) }
        }
    }

    private fun scheduleRefresh(hours: Int) {
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(REFRESH_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun scheduleBatchPoll(minutes: Long) {
        val request = OneTimeWorkRequestBuilder<BatchPollWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BATCH_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val MAX_DETAILS_PER_RUN = 30
        private const val MAX_COMMUTES_PER_RUN = 150
        const val REFRESH_WORK = "refresh"
        const val BATCH_WORK = "batch-poll"
    }
}
