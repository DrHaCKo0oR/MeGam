package app.shaqati.ui.map

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import app.shaqati.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

@Serializable
data class MapPin(val id: String, val lat: Double, val lng: Double, val label: String, val color: String, val z: Int = 0)

@Serializable
data class MapPoint(val lat: Double, val lng: Double)

@Serializable
data class MapPayload(val work: MapPoint?, val pins: List<MapPin>, val fit: Boolean)

private val mapJson = Json { encodeDefaults = true }

/**
 * Google Maps (JavaScript API, with the user's key) or OpenStreetMap tiles in a WebView.
 * The page is served from an https origin so a referrer-restricted key works.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapWeb(
    modifier: Modifier,
    googleKey: String?,
    language: String,
    center: GeoPoint,
    zoom: Int,
    payload: MapPayload?,
    pick: Boolean = false,
    moveTo: GeoPoint? = null,
    onSelect: (String?) -> Unit = {},
    onCenter: (GeoPoint) -> Unit = {},
) {
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val select by rememberUpdatedState(onSelect)
    val centerCb by rememberUpdatedState(onCenter)
    val main = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onLoaded(json: String) {
                        main.post {
                            val cfg = String.format(
                                Locale.ROOT,
                                "{\"lat\":%.6f,\"lng\":%.6f,\"zoom\":%d,\"key\":%s,\"lang\":\"%s\",\"pick\":%b}",
                                center.lat, center.lng, zoom,
                                googleKey?.let { mapJson.encodeToString(String.serializer(), it) } ?: "null",
                                language, pick,
                            )
                            evaluateJavascript("initMap($cfg)", null)
                        }
                    }

                    @JavascriptInterface
                    fun onReady(json: String) {
                        main.post { ready = true }
                    }

                    @JavascriptInterface
                    fun onProvider(json: String) = Unit

                    @JavascriptInterface
                    fun onSelect(json: String) {
                        val id = runCatching { mapJson.parseToJsonElement(json).jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        main.post { select(id) }
                    }

                    @JavascriptInterface
                    fun onCenter(json: String) {
                        val o: JsonObject = runCatching { mapJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
                        val p = runCatching { GeoPoint(o["lat"]!!.jsonPrimitive.double, o["lng"]!!.jsonPrimitive.double) }.getOrNull() ?: return
                        main.post { centerCb(p) }
                    }
                }, "Android")
                loadUrl("https://appassets.androidplatform.net/assets/map/map.html")
                web = this
            }
        },
    )

    LaunchedEffect(ready, payload) {
        val w = web
        if (ready && w != null && payload != null) {
            w.evaluateJavascript("setData(${mapJson.encodeToString(MapPayload.serializer(), payload)})", null)
        }
    }
    LaunchedEffect(ready, moveTo) {
        val w = web
        val p = moveTo
        if (ready && w != null && p != null) {
            w.evaluateJavascript(String.format(Locale.ROOT, "moveMap(%.6f,%.6f,15)", p.lat, p.lng), null)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.apply {
                removeJavascriptInterface("Android")
                stopLoading()
                destroy()
            }
            web = null
        }
    }
}
