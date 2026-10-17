package app.shaqati.ui.components

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import app.shaqati.core.model.GeoPoint
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Shows numbers with Western digits (45,000 · 2+1 · 8:00) in every language, as Saudi listing
 * apps do, so prices, counts and times never mix ٤٥٬٠٠٠ with 45,000 on one screen.
 */
@Composable
fun LatinDigits(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val latin = remember(context, configuration) {
        val locale = configuration.locales[0] ?: return@remember null
        if (DecimalFormatSymbols.getInstance(locale).zeroDigit == '0') return@remember null
        val conf = Configuration(configuration).apply {
            setLocale(Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build())
        }
        val res = context.createConfigurationContext(conf).resources
        conf to object : ContextWrapper(context) {
            override fun getResources(): Resources = res
        }
    }
    if (latin == null) {
        content()
    } else {
        CompositionLocalProvider(LocalConfiguration provides latin.first, LocalContext provides latin.second, content = content)
    }
}

/** "24.76550, 46.68020", kept left to right inside Arabic text. */
fun coordinates(p: GeoPoint): String =
    BidiFormatter.getInstance(true).unicodeWrap(String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lng), TextDirectionHeuristicsCompat.LTR)
