package app.shaqati.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * How a request should be sent. [BROWSER] requests run inside a real browser
 * page of the target site (an Android WebView) so cookies and bot checks behave
 * like the user's own browsing.
 */
enum class FetchMode { DIRECT, BROWSER }

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
    val mode: FetchMode = FetchMode.DIRECT,
    /** For BROWSER mode: page to open first so the request runs on the site's origin. */
    val originPage: String? = null,
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val finalUrl: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = status in 200..299
}

class HttpException(message: String, val status: Int = 0) : IOException(message)

fun interface Http {
    suspend fun send(request: HttpRequest): HttpResponse
}

const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"

/** Plain HTTP client. Browser-mode requests are sent directly with browser-like headers. */
class OkHttpHttp(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = MOBILE_UA,
) : Http {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "ar-SA,ar;q=0.9,en;q=0.8")
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = request.body?.toRequestBody((request.contentType ?: "application/json").toMediaType())
        builder.method(request.method, if (request.method == "GET" || request.method == "HEAD") null else body ?: ByteArray(0).toRequestBody())
        client.newCall(builder.build()).execute().use { resp ->
            HttpResponse(
                status = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                headers = resp.headers.toMultimap().mapValues { it.value.joinToString(",") },
            )
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
