package app.xreader.data.parse

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.5.4 (WS-7): an EPUB shaped like a commercial book instead of
 * like the parser's assumptions — the gate fixture for the
 * V154 EPUB-images finding. Layout mirrors the diagnosis harness:
 * OPF in a subfolder (`EPUB/`), chapters under `Text/`, images
 * under `Images/`, and the three real-world shapes that silently
 * killed the owner's book:
 *
 *  - references written in a different CASE from the stored
 *    entries ([Fixture.mangleCase]),
 *  - a cover declared ONLY through the `<guide>` ([CoverMode]),
 *  - a package-root-absolute image src (`/Images/…`).
 *
 * Canonical shapes (properties/meta covers, `../` refs, SVG
 * `<image>`, percent-encoded paths) are the same builder with the
 * knobs off — the regression direction.
 */
object CommercialEpubFixture {

    enum class CoverMode { GUIDE_PAGE, GUIDE_IMAGE, META, PROPERTIES }

    enum class Chapter2Image { NONE, ROOT_ABSOLUTE, PERCENT }

    data class Fixture(
        val coverMode: CoverMode = CoverMode.GUIDE_PAGE,
        /** Chapter/cover-page image references lowercased while
         * the zip entries keep commercial case. */
        val mangleCase: Boolean = true,
        /** The `<guide>` href lowercased (GUIDE_IMAGE mode). */
        val mangleGuide: Boolean = false,
        val chapter2Image: Chapter2Image = Chapter2Image.ROOT_ABSOLUTE,
        /** Chapter 2 also references an image that does not exist
         * anywhere in the file — the honesty-counter case. */
        val missingImage: Boolean = false
    )

    val COVER_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xC0.toByte(), 11, 12, 13)
    val PLATE1_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xA1.toByte(), 21, 22, 23)
    val PLATE2_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 31, 32, 33)
    val PLATE3_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xA3.toByte(), 41, 42, 43)

    fun bytes(fixture: Fixture = Fixture()): ByteArray {
        fun ref(path: String) = if (fixture.mangleCase) path.lowercase() else path
        val guideHref = when (fixture.coverMode) {
            CoverMode.GUIDE_PAGE -> "Text/coverpage.xhtml"
            CoverMode.GUIDE_IMAGE ->
                if (fixture.mangleGuide) "images/coverart.jpg" else "Images/CoverArt.jpg"
            else -> null
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".toByteArray()
            )
            val metaCover = if (fixture.coverMode == CoverMode.META) {
                "<meta name=\"cover\" content=\"coverimg\"/>"
            } else {
                ""
            }
            val coverProps = if (fixture.coverMode == CoverMode.PROPERTIES) {
                " properties=\"cover-image\""
            } else {
                ""
            }
            val guide = guideHref?.let {
                "<guide><reference type=\"cover\" href=\"$it\"/></guide>"
            } ?: ""
            val opf = """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:ws7:commercial</dc:identifier>
                <dc:title>Commercial Fixture</dc:title>
                <dc:creator>Some Author</dc:creator>
                <dc:language>en</dc:language>
                $metaCover
                </metadata>
                <manifest>
                <item id="chap1" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="chap2" href="Text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="coverpage" href="Text/coverpage.xhtml" media-type="application/xhtml+xml"/>
                <item id="coverimg" href="Images/CoverArt.jpg" media-type="image/jpeg"$coverProps/>
                <item id="ill1" href="Images/Plate1.jpg" media-type="image/jpeg"/>
                <item id="ill2" href="Images/Plate2.png" media-type="image/png"/>
                <item id="ill3" href="My Images/Plate 3.jpg" media-type="image/jpeg"/>
                </manifest>
                <spine>
                <itemref idref="chap1"/>
                <itemref idref="chap2"/>
                </spine>
                $guide
                </package>"""
            entry("EPUB/package.opf", opf.toByteArray())
            entry(
                "EPUB/Text/coverpage.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cover</title></head>
                <body><div><img src="${ref("../Images/CoverArt.jpg")}"/></div></body></html>"""
                    .toByteArray()
            )
            entry(
                "EPUB/Text/chapter1.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
                <head><title>Chapter 1</title></head>
                <body><h1>Chapter 1</h1>
                <p>The story opens with a bang in the harbour town.</p>
                <p><img src="${ref("../Images/Plate1.jpg")}" alt="Plate one"/></p>
                <p>Then things got very quiet before the storm.</p>
                <svg xmlns="http://www.w3.org/2000/svg"><image xlink:href="${ref("../Images/Plate2.png")}"/></svg>
                </body></html>""".toByteArray()
            )
            val chapter2Image = when (fixture.chapter2Image) {
                Chapter2Image.NONE -> ""
                Chapter2Image.ROOT_ABSOLUTE ->
                    "<p><img src=\"${ref("/Images/Plate1.jpg")}\"/></p>"
                Chapter2Image.PERCENT ->
                    "<p><img src=\"../My%20Images/Plate%203.jpg\"/></p>"
            }
            val missing = if (fixture.missingImage) {
                "<p><img src=\"../Images/PlateMissing.jpg\"/></p>"
            } else {
                ""
            }
            entry(
                "EPUB/Text/chapter2.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 2</title></head>
                <body><h1>Chapter 2</h1>
                <p>The second chapter crosses the cold river valley.</p>
                $chapter2Image
                $missing
                </body></html>""".toByteArray()
            )
            entry("EPUB/Images/CoverArt.jpg", COVER_BYTES)
            entry("EPUB/Images/Plate1.jpg", PLATE1_BYTES)
            entry("EPUB/Images/Plate2.png", PLATE2_BYTES)
            entry("EPUB/My Images/Plate 3.jpg", PLATE3_BYTES)
        }
        return out.toByteArray()
    }
}
