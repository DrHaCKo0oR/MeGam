package app.shaqati.ui.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.core.FeedMode
import app.shaqati.core.ListingView
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.ListingCard
import app.shaqati.ui.components.isArabic
import app.shaqati.ui.components.shortMoney
import app.shaqati.ui.feed.feedTabLabel

private val PIN_COLORS = mapOf(
    FeedMode.MATCHES to "#1B7F4B",
    FeedMode.UNCONFIRMED to "#B8893B",
    FeedMode.NEAR_MISSES to "#3A5A8C",
    FeedMode.ALL to "#7D776D",
)

private val PIN_UI_COLORS = mapOf(
    FeedMode.MATCHES to Color(0xFF1B7F4B),
    FeedMode.UNCONFIRMED to Color(0xFFB8893B),
    FeedMode.NEAR_MISSES to Color(0xFF3A5A8C),
    FeedMode.ALL to Color(0xFF7D776D),
)

@Composable
fun MapScreen(vm: AppViewModel, openDetail: (String) -> Unit) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    var shown by rememberSaveable { mutableStateOf(setOf(FeedMode.MATCHES, FeedMode.UNCONFIRMED)) }
    var selected by remember { mutableStateOf<String?>(null) }
    val views: List<ListingView> = feed.buckets.filterKeys { it in shown }.values.flatten()
    val located = views.filter { it.listing.location != null }
    val payload = remember(located, s.work) {
        MapPayload(
            work = MapPoint(s.work.lat, s.work.lng),
            pins = located.map { v ->
                MapPin(
                    id = v.id,
                    lat = v.listing.location!!.lat,
                    lng = v.listing.location!!.lng,
                    label = v.resolved.annualRent?.let { shortMoney(it) } ?: "?",
                    color = PIN_COLORS.getValue(v.bucket),
                    z = if (v.bucket == FeedMode.MATCHES) 500 else 100,
                )
            },
            fit = false,
        )
    }
    val key = remember { vm.googleKey() }
    val lang = if (isArabic()) "ar" else "en"
    Box(Modifier.fillMaxSize()) {
        MapWeb(
            modifier = Modifier.fillMaxSize(),
            googleKey = key,
            language = lang,
            center = s.work,
            zoom = 13,
            payload = payload,
            onSelect = { selected = it },
        )
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(FeedMode.MATCHES, FeedMode.UNCONFIRMED, FeedMode.NEAR_MISSES, FeedMode.ALL).forEach { m ->
                    FilterChip(
                        selected = m in shown,
                        onClick = { shown = if (m in shown) shown - m else shown + m },
                        label = { Text("${feedTabLabel(m)} ${feed.buckets[m].orEmpty().count { it.listing.location != null }}") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            selectedContainerColor = PIN_UI_COLORS.getValue(m),
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
            val unlocated = views.size - located.size
            if (unlocated > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.map_no_location, unlocated),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val v = views.firstOrNull { it.id == selected }
        if (v != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                ListingCard(
                    v = v,
                    morningTime = s.schedule.morningDeparture,
                    onClick = { openDetail(v.id) },
                    onToggleSave = { vm.setSaved(v.id, !v.saved) },
                    compact = true,
                )
            }
        }
    }
}
