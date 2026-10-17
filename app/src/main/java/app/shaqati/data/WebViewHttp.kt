package app.shaqati.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpRequest
import app.shaqati.core.util.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * Opens a page in an off-screen WebView and returns its HTML once the site's own
 * scripts have run. Used for sites that refuse plain HTTP clients but serve browsers.
 * Cookies are shared with [SiteCheckScreen][app.shaqati.ui.check.SiteCheckScreen], so a
 * check the person passed there also covers these pages.
 */
class WebViewHttp(private val context: Context) : Http {
    override suspend fun send(request: HttpRequest): HttpResponse =
        withTimeoutOrNull(TIMEOUT_MS) { load(request) } ?: HttpResponse(408, "", request.url)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(request: HttpRequest): HttpResponse = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            // Only the page's data is needed; photos load later, at the size the app shows them.
            web.settings.blockNetworkImage = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            var status = 200
            var done = false
            var probing = false

            fun finish(html: String) {
                if (done) return
                done = true
                CookieManager.getInstance().flush()
                val finalUrl = web.url ?: request.url
                handler.post { runCatching { web.stopLoading(); web.destroy() } }
                if (cont.isActive) cont.resume(HttpResponse(status, html, finalUrl))
            }

            fun probe(attempt: Int) {
                if (done) return
                web.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { raw ->
                    val html = runCatching { Json.decodeFromString<String>(raw) }.getOrNull().orEmpty()
                    val ready = html.contains("__NEXT_DATA__") || html.contains("self.__next_f") ||
                        html.contains("window.state = ") || html.length > 200_000
                    if (ready || attempt >= MAX_PROBES) finish(html) else handler.postDelayed({ probe(attempt + 1) }, 1_500)
                }
            }

            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // A bot check that passes reloads the page; the new page's status counts.
                    status = 200
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!probing) {
                        probing = true
                        handler.postDelayed({ probe(0) }, 800)
                    }
                }

                override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, response: WebResourceResponse) {
                    if (req.isForMainFrame) status = response.statusCode
                }
            }
            val headers = request.headers.filterKeys { it.equals("Accept-Language", ignoreCase = true) }
            web.loadUrl(request.url, headers)
            cont.invokeOnCancellation {
                handler.post {
                    if (!done) {
                        done = true
                        runCatching { web.stopLoading(); web.destroy() }
                    }
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 45_000L
        private const val MAX_PROBES = 10
    }
}
