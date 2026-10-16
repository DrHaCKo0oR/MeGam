package app.shaqati.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bathtub
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SquareFoot
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shaqati.R
import app.shaqati.core.Engine
import app.shaqati.core.ListingView
import app.shaqati.core.ai.Verdict
import app.shaqati.core.commute.CommuteMethod
import app.shaqati.core.filter.Check
import app.shaqati.core.rank.FinishSource
import app.shaqati.ui.theme.AppTheme
import coil3.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListingCard(
    v: ListingView,
    morningTime: String,
    onClick: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val l = v.listing
    val r = v.resolved
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (compact) 2.6f else 16f / 10f)) {
            val photo = l.photos.firstOrNull()
            if (photo != null) {
                AsyncImage(
                    model = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.HomeWork, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (v.isNew) Pill(stringResource(R.string.badge_new), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                if (v.gone) Pill(stringResource(R.string.badge_gone), AppTheme.status.bad, Color.White)
            }
            Surface(
                onClick = onToggleSave,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (v.saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(if (v.saved) R.string.action_saved else R.string.action_save),
                        tint = if (v.saved) Color(0xFFFF6B6B) else Color.White,
                    )
                }
            }
            if (l.photos.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(14.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("${l.photos.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            FinishBadge(v, Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PriceLine(v)
                    Text(
                        listOfNotNull(l.district?.takeIf { it.isNotBlank() }, kindLabel(l.kind)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ScoreRing(v.score.total)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.bedrooms?.let { Fact(Icons.Outlined.Bed, if (r.hasMajlis) "$it+1" else "$it") }
                r.livingRooms?.let { Fact(Icons.Outlined.Weekend, "$it") }
                r.bathrooms?.let { Fact(Icons.Outlined.Bathtub, "$it") }
                l.areaSqm?.takeIf { it in 20.0..2000.0 }?.let { Fact(Icons.Outlined.SquareFoot, stringResource(R.string.area_sqm, money(it))) }
            }
            CommuteLine(v, morningTime)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.sources.map { sourceName(it) }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    timeAgo(l.updatedAt ?: l.postedAt ?: v.firstSeenAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IssueLine(v)
        }
    }
}

@Composable
fun PriceLine(v: ListingView) {
    val annual = v.resolved.annualRent
    if (annual != null) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.per_year, money(annual)), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.per_month, money(annual / 12)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    } else {
        Text(
            v.listing.price?.let { stringResource(R.string.price_raw, money(it)) } ?: stringResource(R.string.price_unknown),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
fun CommuteLine(v: ListingView, morningTime: String) {
    val c = v.commute
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.DirectionsCar, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        val minutes = c?.morningMinutes
        val text = when {
            c == null || minutes == null -> stringResource(R.string.commute_unknown)
            c.method == CommuteMethod.GOOGLE_TRAFFIC -> stringResource(R.string.commute_am, minutes, clock(morningTime))
            else -> stringResource(R.string.commute_approx, minutes)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IssueLine(v: ListingView) {
    val failed = v.evaluation.failed
    val missing = v.evaluation.unconfirmed.filter { it in Engine.CRITICAL || it == Check.BATHROOMS }
    if (failed.isEmpty() && missing.isEmpty()) return
    val failedText = failed.map { checkLabel(it) }
    val missingText = missing.map { checkLabel(it) }
    Text(
        if (failedText.isNotEmpty()) failedText.joinToString(" · ") { "✕ $it" }
        else stringResource(R.string.not_stated, missingText.joinToString(" · ")),
        style = MaterialTheme.typography.labelMedium,
        color = if (failed.isNotEmpty()) AppTheme.status.bad else AppTheme.status.warn,
        maxLines = 2,
    )
}

@Composable
fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Finish verdict: from the photo analysis when available, otherwise a hint from the ad text. */
@Composable
fun FinishBadge(v: ListingView, modifier: Modifier = Modifier) {
    val s = AppTheme.status
    val ai = v.ai
    when {
        ai != null -> {
            val (bg, fg) = verdictColors(ai.body.verdict)
            Pill("${verdictLabel(ai.body.verdict)} · ${ai.body.score}", bg, fg, modifier)
        }
        v.score.finishSource == FinishSource.TEXT && v.resolved.text.renovationScore >= 3 ->
            Pill(stringResource(R.string.finish_text_hint), s.neutralContainer.copy(alpha = 0.92f), s.neutral, modifier)
        else -> Unit
    }
}

@Composable
fun verdictColors(verdict: Verdict): Pair<Color, Color> {
    val s = AppTheme.status
    return when (verdict) {
        Verdict.MODERN -> s.good to Color.White
        Verdict.RENOVATED -> s.goodContainer to s.good
        Verdict.DATED -> s.warnContainer to s.warn
        Verdict.OLD -> s.badContainer to s.bad
        Verdict.UNCLEAR -> s.neutralContainer to s.neutral
    }
}

@Composable
fun ScoreRing(score: Int, modifier: Modifier = Modifier) {
    val s = AppTheme.status
    val color = when {
        score >= 75 -> s.good
        score >= 55 -> MaterialTheme.colorScheme.primary
        score >= 40 -> s.warn
        else -> s.bad
    }
    Box(
        modifier.size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$score", color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SaveIconButton(saved: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(if (saved) R.string.action_saved else R.string.action_save),
            tint = if (saved) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurface,
        )
    }
}
