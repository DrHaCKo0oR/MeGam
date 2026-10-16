package app.shaqati.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.shaqati.core.ai.ImagePreparer
import app.shaqati.core.ai.PreparedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downloads a listing photo and shrinks it to [maxEdge] pixels on the long side as JPEG.
 * About 640 px keeps finish details (lights, joints, tiles) visible while each photo
 * costs roughly 400 input tokens.
 */
class AndroidImagePreparer(
    private val client: OkHttpClient,
    private val maxEdge: Int = 640,
) : ImagePreparer {
    override suspend fun prepare(url: String): PreparedImage? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.bytes()
            } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            // Skip thumbnails and banners too small to judge.
            if (max(bounds.outWidth, bounds.outHeight) < 240) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val scale = maxEdge.toFloat() / max(decoded.width, decoded.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
            } else decoded
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
            PreparedImage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
        }.getOrNull()
    }
}
