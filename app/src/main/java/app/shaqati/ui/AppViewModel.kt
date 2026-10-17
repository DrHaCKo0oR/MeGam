package app.shaqati.ui

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shaqati.core.FeedMode
import app.shaqati.core.ListingView
import app.shaqati.core.Sort
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.store.HideReason
import app.shaqati.data.AskClaude
import app.shaqati.data.LearnedCorrections
import app.shaqati.data.Settings
import app.shaqati.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.shaqati.core.util.MOBILE_UA
import app.shaqati.core.util.MapLinks

data class FeedState(
    val ready: Boolean = false,
    val buckets: Map<FeedMode, List<ListingView>> = emptyMap(),
    val tracked: Int = 0,
) {
    fun list(mode: FeedMode): List<ListingView> =
        if (mode == FeedMode.ALL) buckets.values.flatten() else buckets[mode].orEmpty()

    fun count(mode: FeedMode): Int = list(mode).size
}

sealed interface AssessState {
    data object Running : AssessState
    data class Failed(val message: String) : AssessState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    val repo = graph.repository
    private val askClaude = AskClaude(app, graph.okHttp)

    val settings: StateFlow<Settings?> = repo.settings
    val status = repo.status
    val subscription = repo.subscriptionState
    val catalog = repo.catalog

    private val _sort = MutableStateFlow(Sort.BEST)
    val sort: StateFlow<Sort> = _sort.asStateFlow()

    private val _assess = MutableStateFlow<Map<String, AssessState>>(emptyMap())
    val assess: StateFlow<Map<String, AssessState>> = _assess.asStateFlow()

    val feed: StateFlow<FeedState> = combine(catalog, settings.filterNotNull(), sort) { _, s, sort ->
        val all = repo.engine.views(s.criteria, FeedMode.ALL, sort, work = s.work)
        // Keep the user's order inside each tab; ALL is sorted the same way.
        FeedState(true, all.groupBy { it.bucket }, repo.engine.snapshot.tracked.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedState())

    val saved: StateFlow<List<ListingView>> = combine(catalog, settings.filterNotNull()) { _, s ->
        repo.engine.savedViews(s.criteria, s.work)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hidden: StateFlow<List<ListingView>> = combine(catalog, settings.filterNotNull()) { _, s ->
        repo.engine.views(s.criteria, FeedMode.ALL, work = s.work, includeHidden = true).filter { it.hidden }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun detail(id: String): Flow<ListingView?> = combine(catalog, settings.filterNotNull()) { _, s ->
        repo.engine.view(id, s.criteria, s.work)
    }.flowOn(Dispatchers.Default)

    fun setSort(s: Sort) = _sort.update { s }

    /** The apartment's photos and [question] for the Claude app; null when no photo loaded. */
    suspend fun askClaudeIntent(listing: Listing, question: String, chooserTitle: String): Intent? {
        val uris = askClaude.photos(listing)
        return if (uris.isEmpty()) null else askClaude.intent(uris, question, chooserTitle)
    }

    fun refresh() {
        graph.scope.launch { repo.refresh() }
    }

    fun syncSubscriptionChecks() {
        graph.scope.launch { runCatching { repo.syncSubscriptionChecks() } }
    }

    fun setSaved(id: String, saved: Boolean) = repo.setSaved(id, saved)
    fun hide(id: String, reason: HideReason?) = repo.hide(id, reason)
    fun unhide(id: String) = repo.unhide(id)
    fun setNote(id: String, note: String) = repo.setNote(id, note)
    fun markSeen(ids: Collection<String>) = repo.markSeen(ids)

    fun updateSettings(change: (Settings) -> Settings) {
        viewModelScope.launch { repo.updateSettings(change) }
    }

    fun setWork(p: GeoPoint, label: String) = updateSettings { it.copy(work = p, workLabel = label, workConfirmed = true) }

    fun setCalibrationHome(p: GeoPoint) = updateSettings { it.copy(calibration = it.calibration.copy(home = p)) }

    fun finishOnboarding(start: Boolean) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(onboarded = true) }
            if (start) refresh()
        }
    }

    fun setLanguage(tag: String) {
        updateSettings { it.copy(language = tag) }
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    fun googleKeySet(): Boolean = repo.vault.has(app.shaqati.data.KeyVault.GOOGLE)
    fun anthropicKeySet(): Boolean = repo.vault.has(app.shaqati.data.KeyVault.ANTHROPIC)
    fun googleKey(): String? = repo.vault.get(app.shaqati.data.KeyVault.GOOGLE)

    fun setKey(name: String, value: String?) = repo.vault.set(name, value)

    suspend fun verifyAnthropic(key: String): Boolean = repo.verifyAnthropicKey(key)
    suspend fun verifyGoogle(key: String): Int? = repo.verifyGoogleKey(key)
    suspend fun recalibrate(): LearnedCorrections? = repo.recalibrate()

    fun assessNow(id: String) {
        if (_assess.value[id] == AssessState.Running) return
        _assess.update { it + (id to AssessState.Running) }
        graph.scope.launch {
            val r = repo.assessNow(id)
            _assess.update { m -> if (r.isSuccess) m - id else m + (id to AssessState.Failed(r.exceptionOrNull()?.message ?: "")) }
        }
    }

    fun clearCatalog() {
        graph.scope.launch { repo.clearCatalog() }
    }

    fun aiCostPerListing(s: Settings): Double = repo.aiCostPerListing(s)

    /** Coordinates from a pasted Google Maps link or "lat, lng" text; short links are opened first. */
    suspend fun resolveMapLink(text: String): GeoPoint? = withContext(Dispatchers.IO) {
        MapLinks.parse(text)?.let { return@withContext it }
        val url = MapLinks.firstUrl(text) ?: return@withContext null
        runCatching {
            graph.okHttp.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", MOBILE_UA).build()).execute().use { resp ->
                MapLinks.parse(resp.request.url.toString()) ?: MapLinks.parse(resp.body?.string()?.take(200_000))
            }
        }.getOrNull()
    }
}
