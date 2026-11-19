package app.xreader.data.prepare

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.7 Patch 3 (Item 6): the prepare pipeline's EPUB fixtures
 * — minimal, parser-valid books, each shaped for one pinned
 * behaviour. Plain JVM (the parser is plain JVM; no Readium
 * session is constructed anywhere in these tests — the
 * detect session's contribution is modelled as plain href
 * lists, exactly what EpubDetect carries).
 */
object Patch3EpubFixtures {

    /** One spine item: [href] is OPF-relative; [body] is the
     * XHTML body content. */
    data class Item(
        val id: String,
        val href: String,
        val body: String,
        val linear: Boolean = true,
        val mediaOverlay: String? = null
    )

    fun book(
        items: List<Item>,
        extraManifest: String = "",
        extraEntries: Map<String, ByteArray> = emptyMap(),
        encryptionXml: String? = null
    ): ByteArray {
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
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".toByteArray()
            )
            if (encryptionXml != null) {
                entry("META-INF/encryption.xml", encryptionXml.toByteArray())
            }
            val manifest = items.joinToString("\n") { item ->
                val overlay = item.mediaOverlay?.let {
                    " media-overlay=\"$it\""
                } ?: ""
                "<item id=\"${item.id}\" href=\"${item.href}\" " +
                    "media-type=\"application/xhtml+xml\"$overlay/>"
            }
            val spine = items.joinToString("\n") { item ->
                val linear = if (item.linear) "" else " linear=\"no\""
                "<itemref idref=\"${item.id}\"$linear/>"
            }
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:patch3:fixture</dc:identifier>
                <dc:title>Patch 3 Fixture</dc:title>
                <dc:language>en</dc:language>
                </metadata>
                <manifest>
                $manifest
                $extraManifest
                </manifest>
                <spine>
                $spine
                </spine>
                </package>""".toByteArray()
            )
            for (item in items) {
                entry(
                    "OEBPS/${item.href}",
                    """<?xml version="1.0"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>${item.id}</title></head>
                    <body>${item.body}</body></html>""".toByteArray()
                )
            }
            for ((name, bytes) in extraEntries) {
                entry(name, bytes)
            }
        }
        return out.toByteArray()
    }

    private fun paragraphs(vararg texts: String): String =
        texts.joinToString("\n") { "<p>$it</p>" }

    /** Three plain text chapters with known, distinct text. */
    fun simpleBook(): ByteArray = book(
        listOf(
            Item(
                "ch1", "Text/ch1.xhtml",
                "<h1>One</h1>" + paragraphs(
                    "Alpha opens the tale beside the grey harbour.",
                    "A bell rings twice over the water."
                )
            ),
            Item(
                "ch2", "Text/ch2.xhtml",
                "<h1>Two</h1>" + paragraphs(
                    "Bravo crosses the river valley under a low sky.",
                    "The ferry lantern sways in the wind."
                )
            ),
            Item(
                "ch3", "Text/ch3.xhtml",
                "<h1>Three</h1>" + paragraphs(
                    "Charlie climbs the hill road toward the beacon.",
                    "At the top, the whole coast is visible."
                )
            )
        )
    )

    /** Session-space hrefs for [simpleBook] — the form a
     * Readium session reports (resolved against the OPF dir). */
    val SIMPLE_SESSION_HREFS = listOf(
        "OEBPS/Text/ch1.xhtml",
        "OEBPS/Text/ch2.xhtml",
        "OEBPS/Text/ch3.xhtml"
    )

    /** A content-encrypted book (Item 1b): refused honestly. */
    fun drmBook(): ByteArray = book(
        listOf(
            Item("ch1", "Text/ch1.xhtml", paragraphs("Locked words."))
        ),
        encryptionXml = """<?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:CipherData><enc:CipherReference URI="Text/ch1.xhtml"/></enc:CipherData></enc:EncryptedData>
            </encryption>"""
    )

    /** Font-only encryption: obfuscated fonts are NOT content
     * DRM — the book prepares normally. */
    fun fontDrmBook(): ByteArray = book(
        listOf(
            Item(
                "ch1", "Text/ch1.xhtml",
                paragraphs("Readable words under an obfuscated font.")
            )
        ),
        encryptionXml = """<?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:CipherData><enc:CipherReference URI="Fonts/main.ttf"/></enc:CipherData></enc:EncryptedData>
            </encryption>"""
    )

    /** A chapter with a SMIL media overlay (Item 4h). */
    fun smilBook(): ByteArray = book(
        listOf(
            Item(
                "ch1", "Text/ch1.xhtml",
                "<h1>Spoken</h1>" +
                    "<p id=\"s1\">The narrator begins the tale aloud.</p>" +
                    "<p id=\"s2\">And carries on to the second line.</p>",
                mediaOverlay = "smil1"
            )
        ),
        extraManifest = """
            <item id="smil1" href="Text/ch1.smil" media-type="application/smil+xml"/>
            <item id="audio1" href="Audio/narration.mp3" media-type="audio/mpeg"/>
        """.trimIndent(),
        extraEntries = mapOf(
            "OEBPS/Text/ch1.smil" to """<?xml version="1.0"?>
                <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
                <body>
                <par><text src="ch1.xhtml#s1"/><audio src="../Audio/narration.mp3" clipBegin="0s" clipEnd="4s"/></par>
                <par><text src="ch1.xhtml#s2"/><audio src="../Audio/narration.mp3" clipBegin="4s" clipEnd="9s"/></par>
                </body>
                </smil>""".toByteArray(),
            "OEBPS/Audio/narration.mp3" to byteArrayOf(0x49, 0x44, 0x33, 7, 8, 9)
        )
    )

    /**
     * Goldfish B1's divergence, as a book: spine order is
     * [first, blank, second, appendix] where BLANK converts
     * empty (the parser's F7 skip drops it) and APPENDIX is
     * `linear="no"` (the parser includes it; a Readium
     * reading order does not). Parser space: [first, second,
     * appendix] (0, 1, 2). Session space: [first, blank,
     * second] (0, 1, 2) — the same index names DIFFERENT
     * chapters from index 1 on, which is the whole point.
     */
    fun divergentBook(): ByteArray = book(
        listOf(
            Item(
                "first", "Text/first.xhtml",
                "<h1>First</h1>" + paragraphs("The first real chapter.")
            ),
            Item("blank", "Text/blank.xhtml", "   "),
            Item(
                "second", "Text/second.xhtml",
                "<h1>Second</h1>" + paragraphs("The second real chapter.")
            ),
            Item(
                "appendix", "Text/appendix.xhtml",
                "<h1>Appendix</h1>" + paragraphs("Back matter notes."),
                linear = false
            )
        )
    )

    /** The session-space hrefs a Readium session reports for
     * [divergentBook] (linear="no" excluded, blank included). */
    val DIVERGENT_SESSION_HREFS = listOf(
        "OEBPS/Text/first.xhtml",
        "OEBPS/Text/blank.xhtml",
        "OEBPS/Text/second.xhtml"
    )

    /**
     * The goliath shape (the owner's Patch 2 failure, modelled):
     * chapter two carries a full-page illustration BETWEEN two
     * stretches of text. A place saved past the picture must
     * reopen past the picture — on this road the place is a
     * char offset in the chapter's text, and the picture is a
     * zero-width block inside that text.
     */
    fun goliathBook(): ByteArray = book(
        listOf(
            Item(
                "ch1", "Text/ch1.xhtml",
                "<h1>Prologue</h1>" + paragraphs(
                    "Before the picture, the hero climbs the long stair."
                )
            ),
            Item(
                "ch2", "Text/ch2.xhtml",
                "<h1>The Gate</h1>" +
                    "<p>Before the picture, the hero climbs the long stair.</p>" +
                    "<p><img src=\"../Images/goliath.jpg\" alt=\"The great gate\"/></p>" +
                    "<p>After the picture, the gate opens onto the plain.</p>",
                mediaOverlay = null
            )
        ),
        extraManifest = """
            <item id="goliath" href="Images/goliath.jpg" media-type="image/jpeg"/>
        """.trimIndent(),
        extraEntries = mapOf(
            "OEBPS/Images/goliath.jpg" to
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x66, 1, 2, 3)
        )
    )

    /** A locator JSON in the Readium shape the local road
     * saves: href + progression inside locations. */
    fun locatorJson(href: String, progression: Float): String =
        """{"href":"$href","type":"application/xhtml+xml",""" +
            """"locations":{"progression":$progression}}"""
}
