package app.shaqati.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.core.ListingView
import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.ai.ItemRating
import app.shaqati.core.commute.CommuteMethod
import app.shaqati.core.filter.Check
import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.store.HideReason
import app.shaqati.data.Settings
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.AssessState
import app.shaqati.ui.components.FinishBadge
import app.shaqati.ui.components.Pill
import app.shaqati.ui.components.PriceLine
import app.shaqati.ui.components.ScoreRing
import app.shaqati.ui.components.audienceLabel
import app.shaqati.ui.components.checkLabel
import app.shaqati.ui.components.clock
import app.shaqati.ui.components.districtName
import app.shaqati.ui.components.plausibleArea
import app.shaqati.ui.components.furnishingLabel
import app.shaqati.ui.components.isArabic
import app.shaqati.ui.components.kindLabel
import app.shaqati.ui.components.money
import app.shaqati.ui.components.sourceName
import app.shaqati.ui.components.timeAgo
import app.shaqati.ui.components.verdictColors
import app.shaqati.ui.components.verdictLabel
import app.shaqati.ui.theme.AppTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun DetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, openPhotos: (Int) -> Unit) {
    val flow = remember(id) { vm.detail(id) }
    val view by flow.collectAsStateWithLifecycle(initialValue = null)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assess by vm.assess.collectAsStateWithLifecycle()
    val v = view
    val s = settings
    LaunchedEffect(id) { vm.markSeen(listOf(id)) }
    if (v == null || s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var hideDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize().testTag("detail_list"), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            item { PhotoHeader(v, onBack, openPhotos, onSave = { vm.setSaved(v.id, !v.saved) }) }
            item { Header(v) }
            item { Actions(v, s) }
            item { Section(stringResource(R.string.detail_finish)) { FinishSection(v, s, vm, assess[v.id]) } }
            item { Section(stringResource(R.string.detail_commute)) { CommuteSection(v, s) } }
            item { Section(stringResource(R.string.detail_requirements)) { RequirementsSection(v, s.criteria) } }
            item { Section(stringResource(R.string.detail_details)) { FactsSection(v) } }
            if (v.listing.description.isNotBlank()) {
                item { Section(stringResource(R.string.detail_description)) { Description(v.listing.description) } }
            }
            if (v.alsoOn.size > 1) {
                item { Section(stringResource(R.string.detail_also_on)) { AlsoOn(v) } }
            }
            item { Section(stringResource(R.string.detail_notes)) { Notes(vm, v) } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (v.hidden) {
                        OutlinedButton(onClick = { v.alsoOn.forEach { vm.unhide(it.id) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_unhide))
                        }
                    } else {
                        OutlinedButton(onClick = { hideDialog = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.VisibilityOff, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_hide))
                        }
                    }
                    Button(onClick = { vm.setSaved(v.id, !v.saved) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (v.saved) R.string.action_saved else R.string.action_save))
                    }
                }
            }
        }
        // Once the photos scroll away, keep text from running under the status bar.
        if (listState.firstVisibleItemIndex > 0) {
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background)
            )
        }
    }
    if (hideDialog) {
        HideDialog(onDismiss = { hideDialog = false }) { reason ->
            hideDialog = false
            vm.hide(v.id, reason)
            onBack()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoHeader(v: ListingView, onBack: () -> Unit, openPhotos: (Int) -> Unit, onSave: () -> Unit) {
    val photos = v.listing.photos
    Box(Modifier.fillMaxWidth().height(320.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (photos.isEmpty()) {
            Icon(Icons.Outlined.HomeWork, null, Modifier.size(64.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.outline)
        } else {
            val pager = rememberPagerState { photos.size }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = photos[page],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable { openPhotos(page) },
                )
            }
            Text(
                "${pager.currentPage + 1} / ${photos.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.35f)) {
                SaveIconButtonLight(v.saved, onSave)
            }
        }
        FinishBadge(v, Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

@Composable
private fun SaveIconButtonLight(saved: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(if (saved) R.string.action_saved else R.string.action_save),
            tint = if (saved) Color(0xFFFF6B6B) else Color.White,
        )
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.35f)) {
        IconButton(onClick = onClick) { Icon(icon, desc, tint = Color.White) }
    }
}

@Composable
private fun Header(v: ListingView) {
    val l = v.listing
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PriceLine(v)
                Text(
                    listOfNotNull(districtName(l.district), kindLabel(l.kind)).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScoreRing(v.score.total)
        }
        Spacer(Modifier.height(6.dp))
        Text(l.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
        Spacer(Modifier.height(4.dp))
        Text(
            v.sources.map { sourceName(it) }.joinToString(" · ") + " · " + timeAgo(l.updatedAt ?: l.postedAt ?: v.firstSeenAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (v.gone) {
            Spacer(Modifier.height(6.dp))
            Pill(stringResource(R.string.badge_gone), AppTheme.status.badContainer, AppTheme.status.bad)
        }
    }
}

@Composable
private fun Actions(v: ListingView, s: Settings) {
    val ctx = LocalContext.current
    val l = v.listing
    val phone = v.alsoOn.firstNotNullOfOrNull { it.phone?.takeIf { p -> p.count(Char::isDigit) >= 9 && !p.contains('X') } }
    val whatsapp = v.alsoOn.firstNotNullOfOrNull { it.whatsapp?.takeIf { p -> p.count(Char::isDigit) >= 9 && !p.contains('X') } } ?: phone
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_ad), Modifier.weight(1f)) {
            open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(l.url)))
        }
        if (phone != null) {
            ActionButton(Icons.Outlined.Call, stringResource(R.string.action_call), Modifier.weight(1f)) {
                open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))
            }
        }
        if (whatsapp != null) {
            ActionButton(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.action_whatsapp), Modifier.weight(1f)) {
                open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + intlPhone(whatsapp))))
            }
        }
        val loc = l.location
        if (loc != null) {
            ActionButton(Icons.Outlined.Directions, stringResource(R.string.action_directions), Modifier.weight(1f)) {
                open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(directionsUrl(loc.lat, loc.lng, s.work.lat, s.work.lng))))
            }
        }
    }
}

private fun directionsUrl(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String =
    String.format(
        Locale.ROOT,
        "https://www.google.com/maps/dir/?api=1&origin=%.6f,%.6f&destination=%.6f,%.6f&travelmode=driving",
        fromLat, fromLng, toLat, toLng,
    )

/** 05XXXXXXXX → 9665XXXXXXXX for wa.me links. */
private fun intlPhone(p: String): String {
    val d = p.filter(Char::isDigit)
    return when {
        d.startsWith("966") -> d
        d.startsWith("00966") -> d.removePrefix("00")
        d.startsWith("05") -> "966" + d.drop(1)
        d.startsWith("5") && d.length == 9 -> "966$d"
        else -> d
    }
}

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishSection(v: ListingView, s: Settings, vm: AppViewModel, state: AssessState?) {
    val ai = v.ai
    if (ai != null) {
        AiResult(ai)
    } else {
        val pending = vm.catalog.collectAsState().value.batches.any { b -> v.alsoOn.any { it.id in b.listingIds } }
        Text(
            stringResource(if (pending) R.string.ai_pending else R.string.finish_not_assessed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        when {
            state == AssessState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ai_analysing))
            }
            !vm.anthropicKeySet() -> Text(stringResource(R.string.ai_need_key), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warn)
            v.listing.photos.size < 2 -> Unit
            else -> {
                val cost = String.format(Locale.ROOT, "%.3f", vm.aiCostPerListing(s.copy(ai = s.ai.copy(useBatch = false))))
                FilledTonalButton(onClick = { vm.assessNow(v.id) }) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_analyse, cost))
                }
            }
        }
        if (state is AssessState.Failed) {
            Spacer(Modifier.height(6.dp))
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = AppTheme.status.bad)
        }
    }
    val text = v.resolved.text
    if (text.positive.isNotEmpty() || text.negative.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.text_signals), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            text.positive.forEach { Pill("✓ " + signalText(it.phrase), AppTheme.status.goodContainer, AppTheme.status.good) }
            text.negative.forEach { Pill("✕ " + signalText(it.phrase), AppTheme.status.badContainer, AppTheme.status.bad) }
        }
    }
}

/** Text-signal labels are English keys; Arabic UI shows Arabic equivalents. */
@Composable
private fun signalText(label: String): String {
    if (!isArabic()) return label
    return when (label) {
        "first tenant" -> "أول ساكن"
        "fully renovated" -> "مجددة بالكامل"
        "brand new" -> "جديدة كلياً"
        "renovated" -> "مجددة"
        "modern style" -> "مودرن"
        "high-end finish" -> "تشطيب فاخر"
        "new building" -> "مبنى جديد"
        "hidden / LED lighting" -> "إضاءة مخفية"
        "spotlights" -> "سبوت لايت"
        "gypsum ceiling" -> "أسقف جبس"
        "porcelain floor" -> "بورسلان"
        "parquet / SPC floor" -> "باركيه"
        "marble" -> "رخام"
        "fresh paint" -> "دهان جديد"
        "new doors" -> "أبواب جديدة"
        "new kitchen" -> "مطبخ جديد"
        "new bathrooms" -> "دورات مياه جديدة"
        "smart home" -> "منزل ذكي"
        "glass" -> "زجاج"
        "very clean" -> "نظيفة جداً"
        "needs renovation" -> "تحتاج ترميم"
        "as is" -> "على حالها"
        "described as old" -> "قديمة"
        "old tiles" -> "بلاط قديم"
        else -> label
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiResult(ai: AiAssessment) {
    val b = ai.body
    val ar = isArabic()
    val (bg, fg) = verdictColors(b.verdict)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(verdictLabel(b.verdict), bg, fg)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.ai_score, b.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.ai_confidence, (b.confidence * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val summary = if (ar) b.summaryAr.ifBlank { b.summaryEn } else b.summaryEn.ifBlank { b.summaryAr }
    if (summary.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(summary, style = MaterialTheme.typography.bodyMedium)
    }
    if (b.notRealPhotos) {
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ai_not_real), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warn)
    }
    Spacer(Modifier.height(10.dp))
    RatingRow(stringResource(R.string.item_floor), b.floor)
    RatingRow(stringResource(R.string.item_ceiling), b.ceilingLighting)
    RatingRow(stringResource(R.string.item_walls), b.wallsPaint)
    RatingRow(stringResource(R.string.item_doors), b.doorsWindows)
    RatingRow(stringResource(R.string.item_bathroom), b.bathroom)
    RatingRow(stringResource(R.string.item_kitchen), b.kitchen)
    val highlights = if (ar) b.highlightsAr.ifEmpty { b.highlightsEn } else b.highlightsEn.ifEmpty { b.highlightsAr }
    val concerns = if (ar) b.concernsAr.ifEmpty { b.concernsEn } else b.concernsEn.ifEmpty { b.concernsAr }
    if (highlights.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.ai_highlights), style = MaterialTheme.typography.labelLarge, color = AppTheme.status.good)
        highlights.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    if (concerns.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.ai_concerns), style = MaterialTheme.typography.labelLarge, color = AppTheme.status.warn)
        concerns.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun RatingRow(label: String, r: ItemRating) {
    val rating = r.rating ?: return
    val bars = (rating + 1) / 2
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val color = when {
            rating >= 8 -> AppTheme.status.good
            rating >= 6 -> MaterialTheme.colorScheme.primary
            rating >= 4 -> AppTheme.status.warn
            else -> AppTheme.status.bad
        }
        Row {
            (1..5).forEach { i ->
                Box(
                    Modifier.padding(horizontal = 2.dp).size(width = 18.dp, height = 8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (i <= bars) color else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
    if (r.note.isNotBlank()) {
        Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommuteSection(v: ListingView, s: Settings) {
    val c = v.commute
    if (c == null) {
        Text(stringResource(R.string.commute_unknown), style = MaterialTheme.typography.bodyMedium)
    } else {
        val am = c.morningMinutes
        val pm = c.eveningMinutes
        Row(Modifier.fillMaxWidth()) {
            CommuteTile(stringResource(R.string.commute_morning, clock(s.schedule.morningDeparture)), am, s.criteria.maxMorningMinutes, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            CommuteTile(stringResource(R.string.commute_evening, clock(s.schedule.eveningDeparture)), pm, s.criteria.maxEveningMinutes ?: (s.criteria.maxMorningMinutes + 10), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (c.method == CommuteMethod.GOOGLE_TRAFFIC) R.string.commute_method_google else R.string.commute_method_approx),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        c.roadKm?.let {
            Text(stringResource(R.string.commute_road_km, String.format(Locale.ROOT, "%.1f", it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            when (v.listing.locationPrecision) {
                LocationPrecision.EXACT -> R.string.location_exact
                LocationPrecision.APPROXIMATE -> R.string.location_approx
                LocationPrecision.DISTRICT -> R.string.location_district
                LocationPrecision.UNKNOWN -> R.string.location_unknown
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (v.listing.locationPrecision == LocationPrecision.EXACT) MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.status.warn,
    )
}

@Composable
private fun CommuteTile(label: String, minutes: Int?, limit: Int, modifier: Modifier) {
    val st = AppTheme.status
    val (bg, fg) = when {
        minutes == null -> st.neutralContainer to st.neutral
        minutes <= limit -> st.goodContainer to st.good
        minutes <= limit + 10 -> st.warnContainer to st.warn
        else -> st.badContainer to st.bad
    }
    Column(modifier.clip(MaterialTheme.shapes.medium).background(bg).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        Text(
            if (minutes == null) "—" else stringResource(R.string.commute_minutes, minutes),
            style = MaterialTheme.typography.titleLarge,
            color = fg,
        )
    }
}

@Composable
private fun RequirementsSection(v: ListingView, c: Criteria) {
    val r = v.resolved
    val e = v.evaluation
    fun state(check: Check): Boolean? = when (check) {
        in e.failed -> false
        in e.unconfirmed -> null
        else -> true
    }
    ReqRow(stringResource(R.string.req_rent, money(c.maxAnnualRent)), r.annualRent?.let { money(it) }, if (Check.PRICE_PERIOD in e.unconfirmed) null else state(Check.RENT))
    val rooms = r.bedrooms?.let { b -> if (r.hasMajlis) "$b + ${stringResource(R.string.majlis)}" else "$b" }
    ReqRow(stringResource(R.string.req_rooms, c.minRooms), rooms, state(Check.ROOMS))
    ReqRow(stringResource(R.string.req_living), r.livingRooms?.toString(), state(Check.LIVING))
    ReqRow(stringResource(R.string.req_bathrooms, c.minBathrooms), r.bathrooms?.toString(), state(Check.BATHROOMS))
    if (c.requireKitchen) ReqRow(stringResource(R.string.req_kitchen), null, if (Check.KITCHEN in e.failed) false else true)
    if (!c.allowFurnished) ReqRow(stringResource(R.string.req_furnishing), furnishingLabel(r.furnishing), state(Check.FURNISHING))
    if (c.excludeSinglesOnly) ReqRow(stringResource(R.string.req_audience), audienceLabel(r.audience), if (Check.AUDIENCE in e.failed) false else true)
    ReqRow(stringResource(R.string.req_commute, c.maxMorningMinutes), v.commute?.morningMinutes?.let { stringResource(R.string.commute_minutes, it) }, state(Check.COMMUTE_AM))
    ReqRow(stringResource(R.string.req_kind), kindLabel(v.listing.kind), state(Check.KIND))
    ReqRow(stringResource(R.string.req_age, c.maxListingAgeDays), null, state(Check.AGE))
    if (Check.CITY in e.failed) ReqRow(stringResource(R.string.req_city), v.listing.city, false)
    if (Check.DISTRICT in e.failed) ReqRow(stringResource(R.string.req_district), v.listing.district, false)
}

@Composable
private fun ReqRow(label: String, value: String?, ok: Boolean?) {
    val st = AppTheme.status
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (ok) {
            true -> Icons.Outlined.CheckCircle to st.good
            false -> Icons.Outlined.Cancel to st.bad
            null -> Icons.AutoMirrored.Outlined.HelpOutline to st.warn
        }
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value ?: if (ok == null) stringResource(R.string.unknown) else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FactsSection(v: ListingView) {
    val l = v.listing
    val r = v.resolved
    Fact(stringResource(R.string.fact_area), plausibleArea(l.areaSqm, l.kind)?.let { stringResource(R.string.area_sqm, money(it)) })
    Fact(stringResource(R.string.fact_floor), l.floor?.let { if (it == 0) stringResource(R.string.floor_ground) else "$it" })
    Fact(stringResource(R.string.fact_age), l.buildingAgeYears?.let { if (it == 0) stringResource(R.string.new_building) else pluralStringResource(R.plurals.years, it, it) })
    Fact(stringResource(R.string.fact_furnishing), furnishingLabel(r.furnishing))
    Fact(stringResource(R.string.fact_audience), audienceLabel(r.audience))
    Fact(stringResource(R.string.fact_kitchen), r.kitchenFitted?.let { stringResource(if (it) R.string.kitchen_fitted else R.string.kitchen_empty) })
    Fact(stringResource(R.string.fact_licence), l.licenseNumber)
    Fact(stringResource(R.string.fact_advertiser), l.advertiserName)
    Fact(stringResource(R.string.fact_posted), l.postedAt?.let { timeAgo(it) })
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text.trim(),
        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
        maxLines = if (expanded) Int.MAX_VALUE else 8,
    )
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
    }
}

@Composable
private fun AlsoOn(v: ListingView) {
    val ctx = LocalContext.current
    v.alsoOn.forEach { l ->
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .clickable { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sourceName(l.source), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(l.price?.let { money(it) } ?: "—", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Notes(vm: AppViewModel, v: ListingView) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val stored = catalog.user.notes[v.id].orEmpty()
    var text by remember(v.id) { mutableStateOf(stored) }
    LaunchedEffect(text) {
        if (text != stored) {
            delay(600)
            vm.setNote(v.id, text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.notes_hint)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
}

@Composable
private fun HideDialog(onDismiss: () -> Unit, onHide: (HideReason?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hide_title)) },
        text = {
            Column {
                listOf(
                    HideReason.TOO_EXPENSIVE to R.string.hide_reason_expensive,
                    HideReason.TOO_FAR to R.string.hide_reason_far,
                    HideReason.OLD_FINISH to R.string.hide_reason_finish,
                    HideReason.LAYOUT to R.string.hide_reason_layout,
                    HideReason.OTHER to R.string.hide_reason_other,
                ).forEach { (reason, label) ->
                    TextButton(onClick = { onHide(reason) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(label), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.navigationBarsPadding(),
    )
}
