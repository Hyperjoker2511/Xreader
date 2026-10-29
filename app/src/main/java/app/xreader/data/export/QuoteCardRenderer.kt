package app.xreader.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.xreader.domain.export.QuoteCardLayout
import java.io.File
import java.io.FileOutputStream

/**
 * Draws the quote card PNG (PLAN §8): Ember Ink dark card, the
 * book's cover as a soft backdrop strip, the quote in a serif face,
 * and the attribution underneath. Rendered with plain Canvas/Paint
 * (no Compose offscreen pass) so it works from any screen, and the
 * text layout itself is [QuoteCardLayout]'s pure model — this class
 * only measures with the real typeface and paints.
 */
object QuoteCardRenderer {

    private val BG = android.graphics.Color.rgb(0x12, 0x10, 0x0E)
    private val CARD = android.graphics.Color.rgb(0x1E, 0x1A, 0x16)
    private val INK = android.graphics.Color.rgb(0xF2, 0xEA, 0xD9)
    private val MUTED = android.graphics.Color.rgb(0xA7, 0x9B, 0x85)
    private val EMBER = android.graphics.Color.rgb(0xC7, 0x7D, 0x3A)

    /** Renders into `cacheDir/quote-cards/`; returns the PNG file. */
    fun render(
        context: Context,
        quote: String,
        bookTitle: String,
        chapterLabel: String,
        author: String,
        coverFile: File?
    ): File {
        val width = QuoteCardLayout.CARD_WIDTH
        val height = QuoteCardLayout.CARD_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG)

        val margin = 72f
        val cardRect = RectF(margin, margin, width - margin, height - margin)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
        canvas.drawRoundRect(cardRect, 48f, 48f, cardPaint)

        var y = margin + 96f

        // Cover thumbnail (when the book has one we can decode).
        val cover = coverFile?.takeIf { it.isFile }?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
        if (cover != null) {
            val coverHeight = 300f
            val dest = RectF(
                margin + 48f, y, width - margin - 48f, y + coverHeight
            )
            val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 200 }
            canvas.save()
            canvas.clipRect(dest)
            val scale = maxOf(
                dest.width() / cover.width, dest.height() / cover.height
            )
            val scaledW = cover.width * scale
            val scaledH = cover.height * scale
            canvas.drawBitmap(
                cover,
                dest.left + (dest.width() - scaledW) / 2f,
                dest.top + (dest.height() - scaledH) / 2f,
                coverPaint
            )
            canvas.restore()
            y += coverHeight + 64f
        }

        // Ember rule above the quote.
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EMBER }
        canvas.drawRoundRect(
            RectF(margin + 48f, y, margin + 48f + 96f, y + 10f), 5f, 5f, rulePaint
        )
        y += 72f

        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textSize = 54f
        }
        val textWidth = width - 2 * margin - 96f
        val model = QuoteCardLayout.build(
            quote = quote,
            bookTitle = bookTitle,
            chapterLabel = chapterLabel,
            author = author,
            maxWidth = textWidth,
            measure = { quotePaint.measureText(it) }
        )
        val lineHeight = 76f
        for (line in model.quoteLines) {
            canvas.drawText(line, margin + 48f, y, quotePaint)
            y += lineHeight
        }
        y += 40f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = EMBER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 40f
        }
        canvas.drawText(
            truncate(model.bookTitle, titlePaint, textWidth),
            margin + 48f, y, titlePaint
        )
        y += 52f
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            typeface = Typeface.SANS_SERIF
            textSize = 34f
        }
        val meta = listOf(model.chapterLabel, model.author)
            .filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            canvas.drawText(
                truncate(meta, metaPaint, textWidth), margin + 48f, y, metaPaint
            )
        }
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            typeface = Typeface.SANS_SERIF
            textSize = 30f
        }
        canvas.drawText(
            "Xreader",
            margin + 48f,
            height - margin - 40f,
            brandPaint
        )

        val dir = File(context.cacheDir, "quote-cards").apply { mkdirs() }
        val file = File(dir, "quote-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        cover?.recycle()
        return file
    }

    private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
