package app.xreader.data.extension

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Site-batch Phase A (WS1) durable synthetic fixture: a
 * tachiyomix `index.pb` built byte-for-byte in the REAL wire
 * shapes the Keiyoushi capture proved, but with invented
 * names/URLs only — so the format stays pinned in the test
 * tree without the 718 KB capture file:
 *
 * - top-level Index fields 1 (name), 3 (signing key);
 * - field 101 = ONE wrapper message (ExtensionList) whose
 *   repeated field 1 = the extensions — the wrapper shape;
 * - Extension fields 1/2/5/6/7 + repeated field 8 = sources;
 * - Source fields 1/2/3/4 (+ field 5 mirror on one source).
 *
 * Variants: the same bytes gzip-wrapped (the wire shape the
 * capture arrived in), the list split across TWO field-101
 * wrappers (proto3 merge semantics for a repeated singular
 * message field), and each extension written directly as a
 * repeated field-101 entry with no list wrapper.
 */
object PbIndexFixture {

    const val REPO_NAME = "FixtureWire Repo"
    const val SIGNING_KEY = "fixture-signing-key-0123456789abcdef"

    const val EXT_ONE_NAME = "Lantern Library Ext"
    const val EXT_ONE_PKG = "org.example.fixture.lantern"
    const val EXT_TWO_NAME = "Quiet Quill Ext"
    const val EXT_TWO_PKG = "org.example.fixture.quill"

    const val SITE_ONE = "https://lantern-library.example"
    const val SITE_TWO = "https://quiet-quill.example"

    /** The wrapper-shape index, uncompressed. */
    fun rawBytes(): ByteArray = indexBytes(listBytes())

    /** The same index gzip-wrapped, as served on the wire. */
    fun gzippedBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(rawBytes()) }
        return out.toByteArray()
    }

    /** Field 101 split over two ExtensionList wrappers, one
     * extension each — a merged read must still yield both. */
    fun splitWrapperBytes(): ByteArray = ByteArrayOutputStream().apply {
        str(this, 1, REPO_NAME)
        str(this, 3, SIGNING_KEY)
        msg(this, 101, extensionListBytes(extensionOne()))
        msg(this, 101, extensionListBytes(extensionTwo()))
    }.toByteArray()

    /** Each extension as its own field-101 entry, with NO
     * ExtensionList wrapper around it. */
    fun repeatedExtensionBytes(): ByteArray = ByteArrayOutputStream().apply {
        str(this, 1, REPO_NAME)
        str(this, 3, SIGNING_KEY)
        msg(this, 101, extensionOne())
        msg(this, 101, extensionTwo())
    }.toByteArray()

    // ── message bodies ──────────────────────────────────────

    private fun indexBytes(list: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            str(this, 1, REPO_NAME)
            str(this, 3, SIGNING_KEY)
            msg(this, 101, list)
        }.toByteArray()

    private fun listBytes(): ByteArray =
        extensionListBytes(extensionOne(), extensionTwo())

    private fun extensionListBytes(vararg extensions: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            for (extension in extensions) msg(this, 1, extension)
        }.toByteArray()

    private fun extensionOne(): ByteArray = ByteArrayOutputStream().apply {
        str(this, 1, EXT_ONE_NAME)
        str(this, 2, EXT_ONE_PKG)
        num(this, 5, 42L)
        str(this, 6, "4.2")
        num(this, 7, 1L)
        msg(this, 8, source(100000000000000001L, "Lantern Library", "en", SITE_ONE))
        msg(this, 8, source(100000000000000002L, "Lantern Library", "es", SITE_ONE))
    }.toByteArray()

    private fun extensionTwo(): ByteArray = ByteArrayOutputStream().apply {
        str(this, 1, EXT_TWO_NAME)
        str(this, 2, EXT_TWO_PKG)
        num(this, 5, 7L)
        str(this, 6, "0.7")
        num(this, 7, 0L)
        msg(
            this, 8,
            source(
                100000000000000003L, "Quiet Quill", "en", SITE_TWO,
                mirror = "https://quill-mirror.example"
            )
        )
    }.toByteArray()

    private fun source(
        id: Long, name: String, language: String, homeUrl: String,
        mirror: String? = null
    ): ByteArray = ByteArrayOutputStream().apply {
        num(this, 1, id)
        str(this, 2, name)
        str(this, 3, language)
        str(this, 4, homeUrl)
        if (mirror != null) str(this, 5, mirror)
    }.toByteArray()

    // ── wire helpers (mirror the verified schema's encoding) ─

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) {
                out.write(b)
                return
            } else {
                out.write(b or 0x80)
            }
        }
    }

    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        varint(out, ((field shl 3) or wire).toLong())

    private fun str(out: ByteArrayOutputStream, field: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        tag(out, field, 2)
        varint(out, b.size.toLong())
        out.write(b)
    }

    private fun msg(out: ByteArrayOutputStream, field: Int, body: ByteArray) {
        tag(out, field, 2)
        varint(out, body.size.toLong())
        out.write(body)
    }

    private fun num(out: ByteArrayOutputStream, field: Int, v: Long) {
        tag(out, field, 0)
        varint(out, v)
    }
}
