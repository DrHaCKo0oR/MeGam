package app.shaqati.ui.check

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.core.model.Source
import app.shaqati.core.sources.AqarSource
import app.shaqati.core.sources.BayutSource
import app.shaqati.core.sources.OpenSooqSource
import app.shaqati.core.sources.PropertyFinderSource
import app.shaqati.core.sources.WasaltSource
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.sourceName
import app.shaqati.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The site's first results page, as the app reads it. */
fun siteCheckUrl(source: Source, maxAnnualRent: Double): String = when (source) {
    Source.BAYUT -> BayutSource.searchUrl(1, maxAnnualRent)
    Source.WASALT -> WasaltSource.searchUrl(1, maxAnnualRent)
    Source.PROPERTY_FINDER -> PropertyFinderSource.searchUrl(maxAnnualRent, 1)
    Source.OPENSOOQ -> OpenSooqSource.listUrl(1)
    Source.AQAR -> AqarSource.BASE + "/"
    Source.HARAJ -> "https://haraj.com.sa/"
    Source.DEAL -> "https://dealapp.sa/ar"
}

/** True once the page carries the listings data the app reads (the check, if any, is passed). */
private const val LISTINGS_PROBE = """(function(){try{
if(window.state&&window.state.algolia&&window.state.algolia.content)return 'ok';
var nd=document.getElementById('__NEXT_DATA__');
if(nd&&nd.textContent.indexOf('"searchResult"')>=0)return 'ok';
for(var i=0;i<document.scripts.length;i++){var t=document.scripts[i].text;
if(t.indexOf('window.state = ')>=0&&t.indexOf('"hits"')>=0)return 'ok';}
return 'no';}catch(e){return 'no';}})()"""

/**
 * Shows a site in a browser inside the app so the person can pass its bot check (a captcha or
 * "checking your browser" page). The cookies it sets are shared with the off-screen browser the
 * search uses, so the next search can read the site.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteCheckScreen(vm: AppViewModel, source: Source, onBack: () -> Unit) {
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val maxRent = appSettings?.criteria?.maxAnnualRent ?: return
    val url = remember(source) { siteCheckUrl(source, maxRent) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var passed by remember { mutableStateOf(false) }
    val name = sourceName(source)

    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect
        while (!passed) {
            delay(1_500)
            passed = suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(LISTINGS_PROBE) { result -> if (cont.isActive) cont.resume(result?.contains("ok") == true) }
            }
        }
        CookieManager.getInstance().flush()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.check_title, name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            if (passed) {
                Surface(color = AppTheme.status.goodContainer, modifier = Modifier.testTag("site_check_passed")) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = AppTheme.status.good)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.check_passed, name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.status.good,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { vm.refresh(); onBack() }) { Text(stringResource(R.string.check_search)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.check_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("site_check_web"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Without explicit params the WebView lays the page out with no height.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                            }
                        }
                        loadUrl(url, mapOf("Accept-Language" to "ar"))
                        web = this
                    }
                },
                onRelease = { view ->
                    CookieManager.getInstance().flush()
                    view.stopLoading()
                    view.destroy()
                },
            )
        }
    }
}
