package app.xreader.ui.reader

import app.xreader.data.extension.PlaceholderImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.4 (WS-5, item 21): the failed-image slot's pure proofs —
 * the generated placeholder is a real decodable image carrying
 * the designed graphic; the bundled asset carries the SAME
 * graphic (the drawing program is mirrored, so probe pixels
 * agree); the reader's slot decision recognises exactly the
 * placeholder's stored file; and the pinned copy matches the
 * spec's words character for character. The stored-but-corrupt
 * acceptance (a real image file whose load fails still gets the
 * labelled slot) is the `loadFailed` row of
 * [failedImageLabelVisible]'s truth table — the render site
 * feeds it from Coil's error callbacks.
 *
 * PNGs are decoded by hand (IHDR + Inflater): this harness's
 * classpath carries no java.desktop/ImageIO.
 */
class V154PlaceholderSlotTest {

    private class PngImage(
        val width: Int,
        val height: Int,
        private val rgb: ByteArray
    ) {
        fun pixel(x: Int, y: Int): Int {
            val i = (y * width + x) * 3
            return ((rgb[i].toInt() and 0xFF) shl 16) or
                ((rgb[i + 1].toInt() and 0xFF) shl 8) or
                (rgb[i + 2].toInt() and 0xFF)
        }
    }

    private fun decodePng(bytes: ByteArray): PngImage {
        require(
            bytes.size > 8 && bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
        ) { "not a PNG" }
        fun intAt(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        var offset = 8
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        while (offset + 8 <= bytes.size) {
            val length = intAt(offset)
            when (val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)) {
                "IHDR" -> {
                    width = intAt(offset + 8)
                    height = intAt(offset + 12)
                }
                "IDAT" -> idat.write(bytes, offset + 8, length)
                "IEND" -> break
                else -> throw AssertionError("unexpected chunk $type")
            }
            offset += 12 + length
        }
        require(width > 0 && height > 0) { "no IHDR" }
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray((width * 3 + 1) * height)
        var produced = 0
        while (produced < raw.size) {
            val n = inflater.inflate(raw, produced, raw.size - produced)
            if (n <= 0) break
            produced += n
        }
        inflater.end()
        require(produced == raw.size) { "short IDAT" }
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) {
            val rowStart = y * (width * 3 + 1)
            require(raw[rowStart].toInt() == 0) { "filter-0 rows only" }
            System.arraycopy(raw, rowStart + 1, rgb, y * width * 3, width * 3)
        }
        return PngImage(width, height, rgb)
    }

    private fun assertDesignedGraphic(image: PngImage, what: String) {
        assertEquals("$what width", 1200, image.width)
        assertEquals("$what height", 800, image.height)
        // Canvas corner: the dark card colour.
        assertEquals("$what background", 0x262233, image.pixel(0, 0))
        // The violet sun at its centre.
        assertEquals("$what sun", 0x8B5CF6, image.pixel(800, 290))
        // The frame's left bar.
        assertEquals("$what frame", 0x57506F, image.pixel(54, 400))
        // The far mountain's slope.
        assertEquals("$what far mountain", 0x453F5E, image.pixel(560, 400))
    }

    @Test
    fun `generated placeholder is a valid designed png`() {
        val bytes = PlaceholderImage.bytes()
        val image = decodePng(bytes)
        assertDesignedGraphic(image, "generated")
        // The render is deterministic (cached bytes).
        assertTrue(bytes.contentEquals(PlaceholderImage.bytes()))
    }

    @Test
    fun `bundled asset carries the same designed graphic`() {
        val candidates = listOf(
            File("src/main/assets/picture-unavailable.png"),
            File("app/src/main/assets/picture-unavailable.png"),
            File("../app/src/main/assets/picture-unavailable.png")
        )
        val asset = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "bundled placeholder asset not found from " +
                    File("").absolutePath
            )
        val image = decodePng(asset.readBytes())
        assertDesignedGraphic(image, "asset")
    }

    @Test
    fun `slot decision recognises exactly the placeholder file`() {
        assertTrue(
            isFailedImageSlot(File("/books/1/img-picture-unavailable.png"))
        )
        assertFalse(isFailedImageSlot(File("/books/1/img-14966-02.jpg")))
        assertFalse(isFailedImageSlot(File("/books/1/picture-unavailable.png")))
        assertFalse(isFailedImageSlot(null))
        assertFalse(isFailedImageSlot("img-picture-unavailable.png"))
        // The reader's name and the fetch layer's names agree.
        assertEquals(
            PlaceholderImage.STORED_FILE_NAME,
            FAILED_IMAGE_STORED_FILE_NAME
        )
        assertEquals(
            PlaceholderImage.STORED_FILE_NAME,
            "img-" + PlaceholderImage.FILE_NAME
        )
    }

    @Test
    fun `label shows for placeholder and for failed loads, never while loading`() {
        assertTrue(failedImageLabelVisible(isPlaceholderSlot = true, loadFailed = false))
        assertTrue(failedImageLabelVisible(isPlaceholderSlot = true, loadFailed = true))
        // Stored-but-corrupt: a real file, a failed load — the
        // labelled slot still renders (never a blank).
        assertTrue(failedImageLabelVisible(isPlaceholderSlot = false, loadFailed = true))
        assertFalse(failedImageLabelVisible(isPlaceholderSlot = false, loadFailed = false))
    }

    @Test
    fun `pinned copy matches the spec words exactly`() {
        assertEquals(
            "Picture unavailable — this source's image server didn't answer.",
            FAILED_IMAGE_COPY
        )
    }
}
