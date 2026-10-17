package app.shaqati.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.shaqati.R
import app.shaqati.core.ai.Verdict
import app.shaqati.core.filter.Check
import app.shaqati.core.model.Audience
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.Source
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

@Composable
@ReadOnlyComposable
fun isArabic(): Boolean = currentLocale().language == "ar"

@Composable
@ReadOnlyComposable
fun money(v: Double): String = NumberFormat.getIntegerInstance(currentLocale()).format(v.roundToLong())

/** 45000 → "45k" style short labels for map pins. */
fun shortMoney(v: Double): String = if (v >= 1000) "${(v / 1000).roundToLong()}k" else v.roundToLong().toString()

@Composable
fun timeAgo(ms: Long?, now: Long = System.currentTimeMillis()): String {
    if (ms == null) return stringResource(R.string.unknown)
    val minutes = ((now - ms) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 2 -> stringResource(R.string.time_now)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(R.string.time_hours, (minutes / 60).toInt())
        else -> (minutes / (60 * 24)).toInt().let { pluralStringResource(R.plurals.time_days, it, it) }
    }
}

@Composable
fun sourceName(s: Source): String = if (isArabic()) s.nameAr else s.nameEn

/** District names come in Arabic from the sites; the English UI shows the English name when known. */
@Composable
fun districtName(name: String?): String? {
    val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (isArabic()) return n
    return app.shaqati.core.sources.Districts.find(n)?.en ?: n
}

/** Floor area worth showing: sites often put the plot or building size in the same field. */
fun plausibleArea(sqm: Double?, kind: PropertyKind): Double? {
    val max = when (kind) {
        PropertyKind.APARTMENT, PropertyKind.STUDIO, PropertyKind.ROOM -> 400.0
        else -> 1_000.0
    }
    return sqm?.takeIf { it in 25.0..max }
}

@Composable
fun verdictLabel(v: Verdict): String = stringResource(
    when (v) {
        Verdict.MODERN -> R.string.verdict_modern
        Verdict.RENOVATED -> R.string.verdict_renovated
        Verdict.DATED -> R.string.verdict_dated
        Verdict.OLD -> R.string.verdict_old
        Verdict.UNCLEAR -> R.string.verdict_unclear
    }
)

@Composable
fun checkLabel(c: Check): String = stringResource(
    when (c) {
        Check.RENT -> R.string.check_rent
        Check.PRICE_PERIOD -> R.string.check_price_period
        Check.CITY -> R.string.check_city
        Check.ROOMS -> R.string.check_rooms
        Check.LIVING -> R.string.check_living
        Check.BATHROOMS -> R.string.check_bathrooms
        Check.KITCHEN -> R.string.check_kitchen
        Check.FURNISHING -> R.string.check_furnishing
        Check.AUDIENCE -> R.string.check_audience
        Check.COMMUTE_AM -> R.string.check_commute_am
        Check.COMMUTE_PM -> R.string.check_commute_pm
        Check.AGE -> R.string.check_age
        Check.KIND -> R.string.check_kind
        Check.DISTRICT -> R.string.check_district
    }
)

@Composable
fun kindLabel(k: PropertyKind): String = stringResource(
    when (k) {
        PropertyKind.APARTMENT -> R.string.kind_apartment
        PropertyKind.FLOOR -> R.string.kind_floor
        PropertyKind.ANNEX -> R.string.kind_annex
        PropertyKind.VILLA -> R.string.kind_villa
        PropertyKind.STUDIO -> R.string.kind_studio
        PropertyKind.ROOM -> R.string.kind_room
        PropertyKind.OTHER -> R.string.kind_other
    }
)

@Composable
fun furnishingLabel(f: Furnishing): String = stringResource(
    when (f) {
        Furnishing.UNFURNISHED -> R.string.furnishing_unfurnished
        Furnishing.SEMI_FURNISHED -> R.string.furnishing_semi
        Furnishing.FURNISHED -> R.string.furnishing_furnished
        Furnishing.UNKNOWN -> R.string.unknown
    }
)

@Composable
fun audienceLabel(a: Audience): String = stringResource(
    when (a) {
        Audience.FAMILIES -> R.string.audience_families
        Audience.SINGLES -> R.string.audience_singles
        Audience.ANY -> R.string.audience_any
        Audience.UNKNOWN -> R.string.unknown
    }
)

/** "HH:mm" → localized short time, e.g. 8:00 ص. */
@Composable
fun clock(hhmm: String): String {
    val t = runCatching { java.time.LocalTime.parse(hhmm) }.getOrNull() ?: return hhmm
    return t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", currentLocale()))
}
