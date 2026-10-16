package app.shaqati.ui.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.core.FeedMode
import app.shaqati.core.Sort
import app.shaqati.data.AiNote
import app.shaqati.data.SourceRun
import app.shaqati.data.SyncStatus
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.ListingCard
import app.shaqati.ui.components.sourceName
import app.shaqati.ui.components.timeAgo
import app.shaqati.ui.theme.AppTheme

val FEED_TABS = listOf(FeedMode.MATCHES, FeedMode.UNCONFIRMED, FeedMode.NEAR_MISSES, FeedMode.ALL)

@Composable
fun feedTabLabel(m: FeedMode): String = stringResource(
    when (m) {
        FeedMode.MATCHES -> R.string.feed_matches
        FeedMode.UNCONFIRMED -> R.string.feed_unconfirmed
        FeedMode.NEAR_MISSES -> R.string.feed_near
        FeedMode.ALL -> R.string.feed_all
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: AppViewModel, openDetail: (String) -> Unit) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(FeedMode.MATCHES) }
    val items = feed.list(tab)
    val listState = rememberLazyListState()
    LaunchedEffect(tab, sort) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (status.lastFinishedAt == null && catalog.lastRefreshAt == null) stringResource(R.string.feed_never_updated)
                        else stringResource(R.string.feed_updated, timeAgo(status.lastFinishedAt ?: catalog.lastRefreshAt)) +
                            " · " + stringResource(R.string.feed_count_ads, feed.tracked),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                SortMenu(sort) { vm.setSort(it) }
                if (status.running) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FEED_TABS.forEach { m ->
                FilterChip(
                    selected = tab == m,
                    onClick = { tab = m },
                    label = { Text("${feedTabLabel(m)}  ${feed.count(m)}") },
                    modifier = Modifier.testTag("chip_${m.name}"),
                )
            }
        }
        StatusStrip(status)
        PullToRefreshBox(
            isRefreshing = status.running && status.phase == SyncStatus.Phase.SEARCHING,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (feed.ready && items.isEmpty()) {
                EmptyFeed(tab, onRefresh = { vm.refresh() }, running = status.running)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { v ->
                        ListingCard(
                            v = v,
                            morningTime = settings?.schedule?.morningDeparture ?: "08:00",
                            onClick = { openDetail(v.id) },
                            onToggleSave = { vm.setSaved(v.id, !v.saved) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(current: Sort, onPick: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.action_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Sort.entries.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (s) {
                                    Sort.BEST -> R.string.sort_best
                                    Sort.NEWEST -> R.string.sort_newest
                                    Sort.CHEAPEST -> R.string.sort_cheapest
                                    Sort.CLOSEST -> R.string.sort_closest
                                    Sort.MODERN -> R.string.sort_modern
                                }
                            ),
                            color = if (s == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onPick(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(status: SyncStatus) {
    val s = AppTheme.status
    if (status.running) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            val running = status.sources.filterValues { it == SourceRun.RUNNING || it == SourceRun.WAITING }.keys.map { sourceName(it) }
            val text = when (status.phase) {
                SyncStatus.Phase.SEARCHING ->
                    if (running.isEmpty()) stringResource(R.string.phase_searching)
                    else stringResource(R.string.searching_sites, running.joinToString(" · "))
                SyncStatus.Phase.DETAILS -> stringResource(R.string.phase_details, status.progress)
                SyncStatus.Phase.COMMUTE -> stringResource(R.string.phase_commute)
                SyncStatus.Phase.PHOTOS -> stringResource(R.string.phase_photos, status.progress)
                SyncStatus.Phase.IDLE -> ""
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    val error = status.lastError
    val note = status.aiNote
    val message = when {
        error != null -> stringResource(R.string.refresh_failed, error.take(120))
        note == AiNote.SUBMITTED -> stringResource(R.string.ai_note_submitted)
        note == AiNote.CAP_REACHED -> stringResource(R.string.ai_note_cap)
        note == AiNote.KEY_REJECTED -> stringResource(R.string.ai_note_key)
        note == AiNote.FAILED -> stringResource(R.string.ai_note_failed)
        else -> null
    } ?: return
    Surface(
        color = if (error != null || note == AiNote.KEY_REJECTED) s.badContainer else s.neutralContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null || note == AiNote.KEY_REJECTED) s.bad else s.neutral,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyFeed(tab: FeedMode, onRefresh: () -> Unit, running: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (running) Icons.Outlined.TravelExplore else Icons.Outlined.SearchOff,
                    null,
                    Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(if (running) R.string.empty_searching_title else R.string.empty_matches_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        when {
                            running -> R.string.empty_searching_body
                            tab == FeedMode.UNCONFIRMED -> R.string.empty_unconfirmed_body
                            tab == FeedMode.NEAR_MISSES -> R.string.empty_near_body
                            else -> R.string.empty_matches_body
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                if (!running) TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
            }
        }
    }
}
