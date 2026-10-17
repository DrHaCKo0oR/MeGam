package app.shaqati.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.shaqati.core.model.Listing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Hands an apartment's photos and a ready question to the Claude app, which runs on the person's
 * own Claude plan. Anthropic lets only its own apps (Claude, Claude Code) sign in with a Claude
 * subscription, so this app hands the photos over instead of calling Claude itself.
 */
class AskClaude(private val context: Context, private val http: OkHttpClient) {

    /** Downloads up to [max] photos into the share cache; returns their content URIs. */
    suspend fun photos(listing: Listing, max: Int = 6): List<Uri> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        listing.photos.take(max).mapIndexedNotNull { i, url ->
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) return@runCatching null
                    val ext = when (body.contentType()?.subtype) {
                        "webp" -> "webp"
                        "png" -> "png"
                        else -> "jpg"
                    }
                    val file = File(dir, "photo-${i + 1}.$ext")
                    file.outputStream().use { out -> body.byteStream().copyTo(out) }
                    FileProvider.getUriForFile(context, context.packageName + ".share", file)
                }
            }.getOrNull()
        }
    }

    /** An intent for the Claude app when it is installed, otherwise the system share sheet. */
    fun intent(uris: List<Uri>, question: String, chooserTitle: String): Intent {
        val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "image/*"
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else putExtra(Intent.EXTRA_STREAM, uris.first())
            putExtra(Intent.EXTRA_TEXT, question)
            clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val direct = Intent(send).setPackage(CLAUDE_PACKAGE)
        return if (direct.resolveActivity(context.packageManager) != null) direct
        else Intent.createChooser(send, chooserTitle)
    }

    companion object {
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
    }
}
