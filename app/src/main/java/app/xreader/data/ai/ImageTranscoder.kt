package app.xreader.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Converts a model-returned image to WebP for storage (PLAN §1.4:
 * lore art is stored as WebP). Returns null when the bytes can't be
 * decoded — the caller then stores the original bytes untouched.
 * Android-Bitmap based, so device-bound; the seam keeps the art
 * service itself JVM-testable.
 */
fun interface ImageTranscoder {
    fun toWebP(bytes: ByteArray, mimeType: String): Pair<ByteArray, String>?
}

object AndroidImageTranscoder : ImageTranscoder {
    override fun toWebP(bytes: ByteArray, mimeType: String): Pair<ByteArray, String>? =
        runCatching {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            bitmap.recycle()
            if (ok) out.toByteArray() to "webp" else null
        }.getOrNull()
}

/** No-op transcoder (tests, and a fallback if decoding ever breaks). */
object PassthroughTranscoder : ImageTranscoder {
    override fun toWebP(bytes: ByteArray, mimeType: String): Pair<ByteArray, String>? = null
}
