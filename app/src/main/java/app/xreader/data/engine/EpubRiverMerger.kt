package app.xreader.data.engine

import android.net.Uri
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/**
 * v1.5.31 (EPUB RIVER R1, plan §2.2): the host merger. One pure
 * pass over the session's [Publication] producing ONE XHTML
 * document from the whole reading order — head, cover, credits,
 * plates and ToC pages are spine items like any other, so they
 * join the flow in reading order (the owner's Moon+ sequence).
 *
 * What the merger does, per plan:
 *  - each resource's `<body>` is wrapped in
 *    `<section id="xr-sec-{i}" data-xr-section="{i}">`;
 *  - book-resource references (`src`/`href`/`url(...)`) are
 *    rewritten to the fake origin ([RIVER_ORIGIN]) the river
 *    WebView's interceptor answers from the Readium container;
 *  - publisher stylesheets are fetched once, their `url(...)`
 *    references rewritten the same way, and inlined into the
 *    merged head. CHOICE (plan §2.2): publisher CSS stays GLOBAL
 *    in v1; the per-section scoping rewrite is the named
 *    contingency, built only if a real book convicts it;
 *  - scripts are stripped (`<script>` elements and `on*`
 *    attributes) — a merged document must be deterministic;
 *  - element ids are namespaced `xr-{i}-{originalId}` against
 *    cross-chapter collisions, and the map is recorded for
 *    link/footnote resolution;
 *  - the CHAR MAP is computed from the same Jsoup parse:
 *    per section, total text chars and per top-level block
 *    cumulative offsets — the anchor arithmetic's ground truth
 *    for R2's position model (plan §3);
 *  - per-section estimated rendered heights feed
 *    `content-visibility` intrinsic sizes (plan §2.5 Class 1).
 *
 * The merged document + map are cached in the book's cache
 * directory keyed by the book fingerprint + [MERGER_VERSION]
 * (the PDF strip's house pattern). The user CSS is NOT baked
 * into the cache: a token stands in its place and the caller
 * substitutes the live CSS, so typography changes never
 * invalidate the merge.
 *
 * v1.5.32 additions:
 *  - IMAGE-LED ("plate") sections — a section whose body is an
 *    image with at most a caption's worth of text — are marked
 *    `xr-plate` and are NEVER `content-visibility`-skipped and
 *    carry no intrinsic-size placeholder. Conviction (owner's
 *    R1 proof video): a plate section reserved 240 px by the
 *    text-only height formula and started fetching its image
 *    only when the skip lifted, so a fast fling reached it
 *    before the image painted — a screen-length black band.
 *    Un-skipped, a plate's image fetches during the document's
 *    initial load pass and its true height is in the layout
 *    from the start;
 *  - every image in the book is indexed ([RiverImage]: section
 *    + container path, in document order) and the plate section
 *    list is recorded — the plate viewer's handoff data
 *    (plan §6, pulled forward from R5 by the owner);
 *  - the bridge JS reports image taps (see BRIDGE_JS).
 */

/** The river's fake origin (§2.3). Never a real host: every
 * request under it is intercepted and answered from the book's
 * own container, and no socket server exists. */
internal const val RIVER_ORIGIN = "https://xreader.river"

private const val USER_CSS_TOKEN = "/*__XR_USER_CSS__*/"
private const val ID_KEY_SEPARATOR = "\u0001"

/** The fake-origin URL a container path is served under:
 * `https://xriver/res/{path}` with each segment encoded. The
 * interceptor decodes symmetrically. [containerPath] is the
 * DECODED container path (e.g. `OEBPS/Images/plate 1.jpg`). */
internal fun riverResourceUrl(containerPath: String): String {
    val encoded = containerPath.split('/').joinToString("/") { segment ->
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
    return "$RIVER_ORIGIN/res/$encoded"
}

/**
 * v1.5.35 (restore §C.5): an image's pixel size read from its
 * own header bytes — PNG IHDR, GIF logical screen, a JPEG
 * marker walk to SOF0/1/2, WebP VP8X/VP8L/VP8, BMP info
 * header. Null for anything else (or a truncated header):
 * the caller leaves the <img> unstamped, exactly the
 * pre-v1.5.35 behaviour for that image.
 */
internal fun sniffImageSize(bytes: ByteArray): Pair<Int, Int>? {
    fun be16(offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    fun be32(offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    fun le16(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    fun le24(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    fun le32(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    fun ascii(offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)
    fun size(width: Int, height: Int): Pair<Int, Int>? =
        if (width > 0 && height > 0) width to height else null
    if (bytes.size < 24) return null
    return runCatching {
        when {
            // PNG: 8-byte signature, IHDR first — width and
            // height are big-endian at 16 / 20.
            bytes[0] == 0x89.toByte() && ascii(1, 3) == "PNG" ->
                size(be32(16), be32(20))
            // GIF: logical screen size, little-endian at 6 / 8.
            ascii(0, 3) == "GIF" -> size(le16(6), le16(8))
            // BMP: width / height little-endian at 18 / 22.
            ascii(0, 2) == "BM" -> size(le32(18), le32(22))
            // JPEG: walk the marker segments to a start-of-
            // frame (SOF0 / SOF1 / SOF2 carry the size).
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> {
                var offset = 2
                var found: Pair<Int, Int>? = null
                while (found == null && offset + 9 < bytes.size) {
                    if (bytes[offset] != 0xFF.toByte()) break
                    val marker = bytes[offset + 1].toInt() and 0xFF
                    when {
                        marker == 0xC0 || marker == 0xC1 ||
                            marker == 0xC2 ->
                            found = size(
                                be16(offset + 7), be16(offset + 5)
                            )
                        // Standalone markers carry no length.
                        marker == 0x01 || marker in 0xD0..0xD9 ->
                            offset += 2
                        else -> offset += 2 + be16(offset + 2)
                    }
                }
                found
            }
            // WebP: RIFF container, VP8X / VP8L / VP8 chunk.
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" ->
                when (ascii(12, 4)) {
                    "VP8X" -> size(le24(24) + 1, le24(27) + 1)
                    "VP8L" -> {
                        val bits = le32(21)
                        size(
                            (bits and 0x3FFF) + 1,
                            ((bits shr 14) and 0x3FFF) + 1
                        )
                    }
                    "VP8 " -> size(
                        le16(26) and 0x3FFF, le16(28) and 0x3FFF
                    )
                    else -> null
                }
            else -> null
        }
    }.getOrNull()
}

/** One section's entry in the merger's char map (§2.2). */
data class RiverSectionMap(
    val charCount: Int,
    /** Cumulative char offset of each top-level block element
     * (the elements carry `data-xr-block="{k}"` in the merged
     * document, in this order). */
    val blockStarts: List<Int>,
    val blockCharCounts: List<Int>,
    val estimatedHeightPx: Int
)

/** One image in the merged document (v1.5.32, plan §6): the
 * section it sits in and the DECODED container path of the
 * book's original image bytes — the plate viewer's handoff. */
data class RiverImage(
    val section: Int,
    val containerPath: String
)

/** The merger's product: the merged document plus everything
 * the river's later steps (positions, plates) will consume. */
data class RiverDocument(
    val html: String,
    val sections: List<RiverSectionMap>,
    val totalChars: Int,
    /** `(section, originalId)` → namespaced id, key flattened
     * with an in-string separator. */
    val idTargets: Map<String, String>,
    /** Every image in the book, in document order (§6). */
    val images: List<RiverImage>,
    /** Indices of image-led sections — the book's plate
     * sequence, for the viewer's "Plate N of M" line (§6). */
    val plateSections: List<Int>,
    /** Canonical container key → the manifest [Link], for the
     * interceptor's byte road. Not persisted; rebuilt on a
     * cache hit from the publication alone. */
    val linksByKey: Map<String, Link>,
    val failedSections: Int,
    val fromCache: Boolean,
    /** v1.5.34 (R7, plan §2.5/§9): true when the book crossed
     * the windowing threshold — the shell's sections are empty
     * height-held gaps and each section's inner HTML lives in
     * its own file (`sec-{i}.html`) in the book's cache dir,
     * served by the interceptor's /sec/ route and filled
     * around the reading position at runtime. Below the
     * threshold the full merge ships exactly as before. */
    val windowed: Boolean = false,
    /** Per-section inner-HTML byte sizes (windowed books
     * only): the window manager's neighbourhood budget
     * arithmetic. */
    val sectionSizes: List<Int> = emptyList(),
    /** The book's river cache directory (windowed books
     * only): where the per-section files and the view's
     * measured-heights file live. */
    val cacheDirPath: String? = null
)

object EpubRiverMerger {

    /** v1.5.34: v3 — the bridge JS is rebuilt (host-pulled
     * `__xrAnchor`, selection/marks/search/windowing entries)
     * and the base CSS gains the mark + gap styles, so every
     * v2 cache is stale by construction. */
    const val MERGER_VERSION = 4

    private const val HTML_FILE = "river.html"
    private const val MAP_FILE = "river.map.json"
    private const val META_FILE = "river.meta"

    /** A section with at most this much body text whose
     * content includes an image counts as image-led (a plate):
     * enough room for a short caption, far too little to be a
     * text section that merely contains an illustration. */
    private const val PLATE_TEXT_MAX = 16

    /** v1.5.34 (R7, plan §2.5/§9): the windowing thresholds —
     * a book with more sections than this, or a merged XHTML
     * larger than this, ships as a windowed shell + per-section
     * files instead of one whole document. */
    private const val WINDOW_SECTION_THRESHOLD = 150
    private const val WINDOW_MERGED_MAX_CHARS = 8_000_000

    private val REF_ATTRS = listOf("src", "href", "poster", "data", "xlink:href")
    private val CSS_URL = Regex("""url\(\s*(['"]?)([^'\")]+)\1\s*\)""")

    /**
     * Prepare the river document for [session], or null when
     * the book cannot be merged at all (every section failed) —
     * the caller then keeps the legacy reader (plan §9; the
     * honest on-screen word for that road is R8's).
     */
    suspend fun prepare(
        session: ReadiumEpubSession,
        cacheDir: File,
        fingerprint: String,
        userCss: String
    ): RiverDocument? = withContext(Dispatchers.IO) {
        runCatching {
            val meta = "$fingerprint|v$MERGER_VERSION"
            readCache(session.publication, cacheDir, meta, userCss)
                ?: merge(session, cacheDir, meta, userCss)
        }.getOrNull()
    }

    // ── Cache ────────────────────────────────────────────────

    private fun readCache(
        publication: Publication,
        cacheDir: File,
        meta: String,
        userCss: String
    ): RiverDocument? {
        val metaFile = File(cacheDir, META_FILE)
        val htmlFile = File(cacheDir, HTML_FILE)
        val mapFile = File(cacheDir, MAP_FILE)
        if (!metaFile.isFile || !htmlFile.isFile || !mapFile.isFile) return null
        if (runCatching { metaFile.readText() }.getOrNull() != meta) return null
        val root = runCatching { JSONObject(mapFile.readText()) }.getOrNull()
            ?: return null
        val html = runCatching { htmlFile.readText() }.getOrNull()
            ?: return null
        val sections = ArrayList<RiverSectionMap>()
        val sectionArray = root.optJSONArray("sections") ?: return null
        for (i in 0 until sectionArray.length()) {
            val entry = sectionArray.optJSONObject(i) ?: return null
            val starts = ArrayList<Int>()
            val counts = ArrayList<Int>()
            val blocks = entry.optJSONArray("b")
            if (blocks != null) {
                for (k in 0 until blocks.length()) {
                    val pair = blocks.optJSONArray(k) ?: continue
                    starts += pair.optInt(0)
                    counts += pair.optInt(1)
                }
            }
            sections += RiverSectionMap(
                charCount = entry.optInt("c"),
                blockStarts = starts,
                blockCharCounts = counts,
                estimatedHeightPx = entry.optInt("h")
            )
        }
        val ids = LinkedHashMap<String, String>()
        val idObject = root.optJSONObject("ids")
        if (idObject != null) {
            val keys = idObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                ids[key] = idObject.optString(key)
            }
        }
        val images = ArrayList<RiverImage>()
        val imageArray = root.optJSONArray("img")
        if (imageArray != null) {
            for (i in 0 until imageArray.length()) {
                val pair = imageArray.optJSONArray(i) ?: continue
                images += RiverImage(
                    section = pair.optInt(0),
                    containerPath = pair.optString(1)
                )
            }
        }
        val plateSections = ArrayList<Int>()
        val plateArray = root.optJSONArray("plateSecs")
        if (plateArray != null) {
            for (i in 0 until plateArray.length()) {
                plateSections += plateArray.optInt(i)
            }
        }
        // v1.5.34 (R7): a windowed cache is valid only while
        // every per-section file is still on disk — a pruned
        // cache dir re-merges instead of serving gaps that can
        // never fill.
        val windowed = root.optInt("win") == 1
        val sectionSizes = ArrayList<Int>()
        val sizesArray = root.optJSONArray("sizes")
        if (sizesArray != null) {
            for (i in 0 until sizesArray.length()) {
                sectionSizes += sizesArray.optInt(i)
            }
        }
        if (windowed) {
            if (sectionSizes.size != sections.size) return null
            for (i in sections.indices) {
                if (!File(cacheDir, "sec-$i.html").isFile) return null
            }
        }
        return RiverDocument(
            html = html.replace(USER_CSS_TOKEN, userCss),
            sections = sections,
            totalChars = root.optInt("total"),
            idTargets = ids,
            images = images,
            plateSections = plateSections,
            linksByKey = buildLinkMap(publication),
            failedSections = root.optInt("failed"),
            fromCache = true,
            windowed = windowed,
            sectionSizes = sectionSizes,
            cacheDirPath = if (windowed) cacheDir.absolutePath else null
        )
    }

    private fun writeCache(
        cacheDir: File,
        meta: String,
        tokenHtml: String,
        sections: List<RiverSectionMap>,
        totalChars: Int,
        idTargets: Map<String, String>,
        images: List<RiverImage>,
        plateSections: List<Int>,
        failedSections: Int,
        windowed: Boolean = false,
        sectionSizes: List<Int> = emptyList()
    ) {
        runCatching {
            cacheDir.mkdirs()
            val root = JSONObject()
            root.put("total", totalChars)
            root.put("failed", failedSections)
            // v1.5.34 (R7): the windowing stamp + per-section
            // sizes ride the same map file.
            root.put("win", if (windowed) 1 else 0)
            val sizesArray = JSONArray()
            for (size in sectionSizes) sizesArray.put(size)
            root.put("sizes", sizesArray)
            val sectionArray = JSONArray()
            for (section in sections) {
                val entry = JSONObject()
                entry.put("c", section.charCount)
                entry.put("h", section.estimatedHeightPx)
                val blocks = JSONArray()
                section.blockStarts.forEachIndexed { index, start ->
                    blocks.put(
                        JSONArray().put(start)
                            .put(section.blockCharCounts.getOrElse(index) { 0 })
                    )
                }
                entry.put("b", blocks)
                sectionArray.put(entry)
            }
            root.put("sections", sectionArray)
            val idObject = JSONObject()
            for ((key, value) in idTargets) idObject.put(key, value)
            root.put("ids", idObject)
            val imageArray = JSONArray()
            for (image in images) {
                imageArray.put(
                    JSONArray().put(image.section)
                        .put(image.containerPath)
                )
            }
            root.put("img", imageArray)
            val plateArray = JSONArray()
            for (index in plateSections) plateArray.put(index)
            root.put("plateSecs", plateArray)
            File(cacheDir, MAP_FILE).writeText(root.toString())
            File(cacheDir, HTML_FILE).writeText(tokenHtml)
            // Meta lands last: it is the validity stamp.
            File(cacheDir, META_FILE).writeText(meta)
        }
    }

    // ── The merge ────────────────────────────────────────────

    private class SectionWork(
        val index: Int,
        val key: String,
        val doc: org.jsoup.nodes.Document?
    )

    private suspend fun merge(
        session: ReadiumEpubSession,
        cacheDir: File,
        meta: String,
        userCss: String
    ): RiverDocument? {
        val publication = session.publication
        val readingOrder = publication.readingOrder
        if (readingOrder.isEmpty()) return null
        val baseString = publication.baseUrl.toString()
        val linksByKey = buildLinkMap(publication)
        val sectionKeys = readingOrder.map { link ->
            canonicalHrefKey(
                runCatching { link.href.resolve(publication.baseUrl).toString() }
                    .getOrDefault(link.href.toString())
            )
        }
        val sectionByKey = LinkedHashMap<String, Int>()
        sectionKeys.forEachIndexed { index, key ->
            sectionByKey.putIfAbsent(key, index)
        }

        // Pass 1: parse every section; collect stylesheet keys,
        // strip scripts, namespace ids, build the char map.
        val works = ArrayList<SectionWork>()
        val idTargets = LinkedHashMap<String, String>()
        val sections = ArrayList<RiverSectionMap>()
        val plateFlags = ArrayList<Boolean>()
        val cssKeys = LinkedHashSet<String>()
        var failedSections = 0
        for ((index, link) in readingOrder.withIndex()) {
            val key = sectionKeys[index]
            val bytes = runCatching {
                publication.get(link)?.read()?.getOrNull()
            }.getOrNull()
            val doc = if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                runCatching { Jsoup.parse(bytes.decodeToString()) }.getOrNull()
            }
            if (doc == null) {
                failedSections++
                works += SectionWork(index, key, null)
                sections += RiverSectionMap(0, emptyList(), emptyList(), 240)
                plateFlags += false
                continue
            }
            doc.select("link[rel]").forEach { element ->
                if (element.attr("rel").contains("stylesheet", ignoreCase = true)) {
                    val href = element.attr("href").trim()
                    if (href.isNotEmpty() && !isExternalRef(href)) {
                        cssKeys += canonicalHrefKey(
                            resolveContainerPath(key, href)
                        )
                    }
                    element.remove()
                }
            }
            doc.select("script").remove()
            val body = doc.body()
            if (body == null) {
                failedSections++
                works += SectionWork(index, key, null)
                sections += RiverSectionMap(0, emptyList(), emptyList(), 240)
                plateFlags += false
                continue
            }
            for (element in body.allElements) {
                val doomed = element.attributes().asList()
                    .filter { it.key.startsWith("on", ignoreCase = true) }
                    .map { it.key }
                doomed.forEach { element.removeAttr(it) }
                val id = element.attr("id")
                if (id.isNotEmpty()) {
                    val renamed = "xr-$index-$id"
                    element.attr("id", renamed)
                    idTargets[idKey(index, id)] = renamed
                }
                if (element.tagName() == "a") {
                    val name = element.attr("name")
                    if (name.isNotEmpty()) {
                        val renamed = "xr-$index-$name"
                        element.attr("name", renamed)
                        idTargets[idKey(index, name)] = renamed
                    }
                }
            }
            val text = body.text()
            val starts = ArrayList<Int>()
            val counts = ArrayList<Int>()
            var cursor = 0
            body.children().forEachIndexed { blockIndex, child ->
                child.attr("data-xr-block", blockIndex.toString())
                val count = child.text().length
                starts += cursor
                counts += count
                cursor += count + 1
            }
            sections += RiverSectionMap(
                charCount = text.length,
                blockStarts = starts,
                blockCharCounts = counts,
                estimatedHeightPx = estimateHeightPx(text.length)
            )
            // Image-led ("plate") section: an image with at
            // most a caption's worth of text. Such a section's
            // height is its image's height, which the text-only
            // formula above cannot see (v1.5.32 conviction) —
            // pass 2 exempts these from skipping + placeholder.
            plateFlags += text.length <= PLATE_TEXT_MAX &&
                body.select("img, image").isNotEmpty()
            works += SectionWork(index, key, doc)
        }
        if (failedSections == readingOrder.size) return null
        val totalChars = sections.sumOf { it.charCount }

        // Publisher stylesheets: fetched once each, inlined.
        val publisherStyles = StringBuilder()
        for ((styleIndex, cssKey) in cssKeys.withIndex()) {
            val link = linksByKey[cssKey] ?: continue
            val bytes = runCatching {
                publication.get(link)?.read()?.getOrNull()
            }.getOrNull() ?: continue
            publisherStyles.append("<style data-xr-pub=\"").append(styleIndex)
                .append("\">")
                .append(rewriteCssUrls(bytes.decodeToString(), cssKey, linksByKey))
                .append("</style>")
        }

        // Pass 2: rewrite references in place, then serialize.
        // Images are indexed as their references are rewritten
        // (the viewer's handoff, §6).
        val images = ArrayList<RiverImage>()
        val plateSectionIndices = plateFlags.indices
            .filter { plateFlags[it] }
        // v1.5.34 (R7): pass 2 assembles each section as its
        // OWN chunk (open tag + inner + close) so the windowing
        // decision below can split the book into per-section
        // files without a second serialisation. Joined in
        // order, the chunks are byte-for-byte the document the
        // single-StringBuilder assembly produced.
        val sectionChunks = ArrayList<String>()
        val sectionInners = ArrayList<String>()
        for (work in works) {
            val isPlate = plateFlags[work.index]
            val openTag = StringBuilder()
            openTag.append("<section class=\"xr-sec")
            if (isPlate) openTag.append(" xr-plate")
            openTag.append("\" id=\"xr-sec-")
                .append(work.index)
                .append("\" data-xr-section=\"").append(work.index)
                .append("\"")
            if (!isPlate) {
                openTag.append(" style=\"contain-intrinsic-size: auto ")
                    .append(sections[work.index].estimatedHeightPx)
                    .append("px\"")
            }
            openTag.append(">")
            val inner = StringBuilder()
            val doc = work.doc
            if (doc != null) {
                val body = doc.body()
                if (body != null) {
                    for (element in body.allElements) {
                        val tagName = element.tagName()
                        if (tagName == "img" || tagName == "image") {
                            // Index the image BEFORE its src is
                            // rewritten below (the index holds
                            // the container path, §6.3), and let
                            // its decode run off the critical
                            // path so a heavy plate can never
                            // block the text around it painting.
                            val rawRef = if (tagName == "img") {
                                element.attr("src")
                            } else {
                                element.attr("xlink:href")
                                    .ifEmpty { element.attr("href") }
                            }
                            val refPath = rawRef.trim()
                                .substringBefore('#')
                            if (refPath.isNotEmpty() &&
                                !isExternalRef(refPath)
                            ) {
                                val resolved = resolveContainerPath(
                                    work.key, refPath
                                )
                                val imageLink = linksByKey[
                                    canonicalHrefKey(resolved)
                                ]
                                if (imageLink != null) {
                                    images += RiverImage(
                                        section = work.index,
                                        containerPath = runCatching {
                                            Uri.decode(resolved)
                                        }.getOrDefault(resolved)
                                    )
                                    // v1.5.35 (restore §C.5):
                                    // stamp the image's own
                                    // pixel size on the <img>
                                    // so Chromium reserves its
                                    // final box BEFORE the
                                    // bytes load — a plate
                                    // landing late can no
                                    // longer push the reader's
                                    // place down the page. A
                                    // publisher-set size is
                                    // respected; an
                                    // unsniffable image is
                                    // left exactly as today.
                                    if (tagName == "img" &&
                                        !element.hasAttr("width") &&
                                        !element.hasAttr("height")
                                    ) {
                                        val size = runCatching {
                                            publication.get(imageLink)
                                                ?.read()?.getOrNull()
                                        }.getOrNull()
                                            ?.let { sniffImageSize(it) }
                                        if (size != null) {
                                            element.attr(
                                                "width",
                                                size.first.toString()
                                            )
                                            element.attr(
                                                "height",
                                                size.second.toString()
                                            )
                                        }
                                    }
                                }
                            }
                            if (tagName == "img") {
                                element.attr("decoding", "async")
                            }
                        }
                        val isAnchor = element.tagName() == "a"
                        for (attrKey in REF_ATTRS) {
                            if (!element.hasAttr(attrKey)) continue
                            val rewritten = rewriteReference(
                                raw = element.attr(attrKey),
                                fromSection = work.index,
                                fromKey = work.key,
                                isAnchor = isAnchor,
                                baseString = baseString,
                                sectionByKey = sectionByKey,
                                idTargets = idTargets,
                                linksByKey = linksByKey
                            )
                            if (rewritten != null) {
                                element.attr(attrKey, rewritten)
                            }
                        }
                        if (isAnchor) {
                            val type = element.attr("epub:type")
                            if (type.contains("noteref", ignoreCase = true)) {
                                element.attr("data-xr-noteref", "1")
                            }
                        }
                    }
                    for (styleElement in doc.select("style")) {
                        val cssText = styleElement.data()
                        if (cssText.contains("url(")) {
                            val rewritten =
                                rewriteCssUrls(cssText, work.key, linksByKey)
                            if (rewritten != cssText) {
                                styleElement.empty()
                                styleElement.appendChild(DataNode(rewritten))
                            }
                        }
                    }
                    // Head <style> blocks ride at the section's
                    // head (publisher CSS is global in v1, so
                    // placement inside the section is equivalent
                    // and keeps one assembly road).
                    for (headStyle in doc.head().select("style")) {
                        inner.append("<style>")
                            .append(headStyle.data())
                            .append("</style>")
                    }
                    inner.append(body.html())
                }
            }
            sectionInners += inner.toString()
            sectionChunks += openTag.toString() +
                sectionInners.last() + "</section>"
        }

        // v1.5.34 (R7, plan §2.5 Class 3 / §9): the windowing
        // decision. Above the threshold the merged document is
        // too heavy to hold whole in one WebView document, so
        // the shell ships every section as an empty height-held
        // gap and each section's inner HTML is written to its
        // own file, filled around the reading position at
        // runtime (the view's window manager + the /sec/
        // interceptor route). Below the threshold NOTHING
        // changes: the full merge, byte-for-byte.
        val fullSectionsHtml = sectionChunks.joinToString("")
        val overThreshold = works.size > WINDOW_SECTION_THRESHOLD ||
            fullSectionsHtml.length > WINDOW_MERGED_MAX_CHARS
        var windowed = false
        var sectionSizes: List<Int> = emptyList()
        val sectionsHtml: String
        if (overThreshold) {
            val filesWritten = runCatching {
                cacheDir.mkdirs()
                sectionInners.forEachIndexed { index, html ->
                    File(cacheDir, "sec-$index.html").writeText(html)
                }
            }.isSuccess
            if (filesWritten) {
                windowed = true
                sectionSizes = sectionInners.map {
                    it.toByteArray(Charsets.UTF_8).size
                }
                val shell = StringBuilder()
                for (work in works) {
                    val isPlate = plateFlags[work.index]
                    shell.append("<section class=\"xr-sec xr-gap")
                    if (isPlate) shell.append(" xr-plate")
                    shell.append("\" id=\"xr-sec-")
                        .append(work.index)
                        .append("\" data-xr-section=\"")
                        .append(work.index)
                        .append("\" style=\"min-height:")
                        .append(sections[work.index].estimatedHeightPx)
                        .append("px\"></section>")
                }
                sectionsHtml = shell.toString()
            } else {
                // A cache dir that cannot take the section
                // files keeps the full merge — degraded, never
                // a book that cannot open.
                sectionsHtml = fullSectionsHtml
            }
        } else {
            sectionsHtml = fullSectionsHtml
        }

        val language = publication.metadata.languages.firstOrNull()
            ?.filter { it.isLetterOrDigit() || it == '-' }
            ?.take(35)
            ?.ifEmpty { null } ?: "en"
        val tokenHtml = buildString {
            append("<!DOCTYPE html>\n<html lang=\"").append(language)
                .append("\" xmlns=\"http://www.w3.org/1999/xhtml\"><head>")
            append("<meta charset=\"utf-8\"/>")
            append(
                "<meta name=\"viewport\" " +
                    "content=\"width=device-width, initial-scale=1.0\"/>"
            )
            append(publisherStyles)
            append("<style id=\"xr-base\">").append(BASE_CSS).append("</style>")
            append("<style id=\"xr-user\">").append(USER_CSS_TOKEN)
                .append("</style>")
            append("</head><body><div id=\"xr-flow\">")
            append(sectionsHtml)
            append("</div><script>").append(BRIDGE_JS).append("</script>")
            append("</body></html>")
        }
        writeCache(
            cacheDir, meta, tokenHtml, sections, totalChars,
            idTargets, images, plateSectionIndices, failedSections,
            windowed = windowed, sectionSizes = sectionSizes
        )
        return RiverDocument(
            html = tokenHtml.replace(USER_CSS_TOKEN, userCss),
            sections = sections,
            totalChars = totalChars,
            idTargets = idTargets,
            images = images,
            plateSections = plateSectionIndices,
            linksByKey = linksByKey,
            failedSections = failedSections,
            fromCache = false,
            windowed = windowed,
            sectionSizes = sectionSizes,
            cacheDirPath = if (windowed) cacheDir.absolutePath else null
        )
    }

    // ── Reference machinery ──────────────────────────────────

    private fun idKey(section: Int, originalId: String): String =
        "$section$ID_KEY_SEPARATOR$originalId"

    private fun buildLinkMap(publication: Publication): Map<String, Link> {
        val map = LinkedHashMap<String, Link>()
        for (link in publication.readingOrder + publication.resources) {
            val resolved = runCatching {
                link.href.resolve(publication.baseUrl).toString()
            }.getOrNull() ?: continue
            map.putIfAbsent(canonicalHrefKey(resolved), link)
        }
        return map
    }

    private fun isExternalRef(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:") || lower.startsWith("tel:") ||
            lower.startsWith("data:") || lower.startsWith("javascript:") ||
            lower.startsWith("file:")
    }

    /** Resolve [ref] (a possibly relative, possibly encoded
     * container reference) against the container path of the
     * file that contains it. The result is a raw container path
     * (canonicalise with [canonicalHrefKey] for map lookups). */
    private fun resolveContainerPath(fromKey: String, ref: String): String {
        val clean = ref.substringBefore('#').substringBefore('?')
        val joined = when {
            clean.startsWith("/") -> clean.substring(1)
            else -> {
                val dir = fromKey.substringBeforeLast('/', "")
                if (dir.isEmpty()) clean else "$dir/$clean"
            }
        }
        val out = ArrayList<String>()
        for (segment in joined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += segment
            }
        }
        return out.joinToString("/")
    }

    private fun decodeFragment(fragment: String): String =
        runCatching { Uri.decode(fragment) }.getOrDefault(fragment)

    /**
     * The rewritten value for one reference attribute, or null
     * to leave the source value untouched (external URLs,
     * unresolvable references — degraded, never rewritten into
     * something wrong; plan §5.1).
     */
    private fun rewriteReference(
        raw: String,
        fromSection: Int,
        fromKey: String,
        isAnchor: Boolean,
        baseString: String,
        sectionByKey: Map<String, Int>,
        idTargets: Map<String, String>,
        linksByKey: Map<String, Link>
    ): String? {
        val value = raw.trim()
        if (value.isEmpty() || value == "#") return null
        if (value.startsWith("#")) {
            if (!isAnchor) return null
            val target = idTargets[idKey(fromSection, decodeFragment(value.substring(1)))]
            return if (target != null) "#$target" else "#xr-sec-$fromSection"
        }
        val fragment = if (value.contains('#')) value.substringAfter('#') else null
        val pathPart = when {
            value.startsWith(baseString) ->
                value.substringBefore('#').removePrefix(baseString)
            isExternalRef(value) -> return null
            else -> value.substringBefore('#')
        }
        if (pathPart.isBlank()) {
            if (!isAnchor) return null
            val target = fragment?.let {
                idTargets[idKey(fromSection, decodeFragment(it))]
            }
            return if (target != null) "#$target" else "#xr-sec-$fromSection"
        }
        val resolved = resolveContainerPath(fromKey, pathPart)
        val key = canonicalHrefKey(resolved)
        val targetSection = sectionByKey[key]
        if (isAnchor && targetSection != null) {
            val target = fragment?.let {
                idTargets[idKey(targetSection, decodeFragment(it))]
            }
            return if (target != null) "#$target" else "#xr-sec-$targetSection"
        }
        if (linksByKey.containsKey(key)) {
            val decoded = runCatching { Uri.decode(resolved) }
                .getOrDefault(resolved)
            return riverResourceUrl(decoded)
        }
        return null
    }

    private fun rewriteCssUrls(
        css: String,
        fromKey: String,
        linksByKey: Map<String, Link>
    ): String = CSS_URL.replace(css) { match ->
        val target = match.groupValues[2].trim()
        if (target.isEmpty() || target.startsWith("#") || isExternalRef(target)) {
            match.value
        } else {
            val resolved = resolveContainerPath(fromKey, target)
            if (linksByKey.containsKey(canonicalHrefKey(resolved))) {
                val decoded = runCatching { Uri.decode(resolved) }
                    .getOrDefault(resolved)
                "url(\"${riverResourceUrl(decoded)}\")"
            } else {
                match.value
            }
        }
    }

    /** Rough rendered height from char count alone; refined
     * from real measurements in R2/R7 (§2.5). */
    private fun estimateHeightPx(charCount: Int): Int =
        (240.0 + charCount * 0.62).toInt().coerceAtLeast(240)

    // ── Shell assets ─────────────────────────────────────────

    /** Structural CSS only — everything the owner controls
     * lives in `#xr-user` (§2.4) and swaps live. Bottom padding
     * is generous in R1 so the last line can rest clear of the
     * chrome; R3 sets it from the capsule's measured height
     * (§7.1). */
    private const val BASE_CSS: String =
        ":root{--xr-fg:#F2F2F2;--xr-bg:#000000;--xr-margin-x:20px;}" +
            "html{background:var(--xr-bg);}" +
            "body{margin:0;padding:0;background:var(--xr-bg);" +
            "color:var(--xr-fg);}" +
            "#xr-flow{padding:12px var(--xr-margin-x) 34vh " +
            "var(--xr-margin-x);box-sizing:border-box;}" +
            "section.xr-sec{content-visibility:auto;" +
            "overflow-wrap:break-word;}" +
            // Plate sections are never skipped (v1.5.32
            // conviction, see the class KDoc): their images
            // load with the document, not on arrival.
            "section.xr-sec.xr-plate{content-visibility:visible;}" +
            "#xr-flow img{max-width:100%;height:auto;}" +
            "#xr-flow svg{max-width:100%;height:auto;}" +
            "#xr-flow table{max-width:100%;}" +
            "#xr-flow a{color:inherit;}" +
            // v1.5.34 (R4): the reader's own highlights — a
            // violet wash matching the stored tint, text colour
            // untouched so the words stay the theme's.
            "mark.xr-hl{background:rgba(139,92,246,0.45);" +
            "color:inherit;border-radius:2px;}" +
            // v1.5.34 (R6): the transient search-hit mark —
            // amber with black text, readable on every theme.
            "mark.xr-search{background:#F5C542;color:#000;}" +
            // v1.5.34 (R7): a windowed book's unfilled section
            // is an explicit-height gap, never skip-estimated.
            "section.xr-sec.xr-gap{content-visibility:visible;}"

    /**
     * The page script (v1.5.34, REA §5 rebuild). The model is
     * Readium's navigator's own: the HOST observes and pulls,
     * the page answers — the script posts nothing unsolicited
     * except discrete tap events (image, footnote, highlight)
     * and the opening ready signal, all String/no-arg payloads.
     *
     * Host-pulled entries (evaluateJavascript WITH result
     * callbacks, the WebViewRenderedFetcher pattern):
     *  - `__xrAnchor()` — the position of record: the
     *    (section, block) at the viewport top as a
     *    "section:block" string. v1.5.32's in-page scroll
     *    reporter POSTED this through the bridge and died with
     *    the bridge both times; the geometry code is unchanged,
     *    only its direction — the host now ASKS.
     *  - `__xrOffsets()` / `__xrSectionHeight(n)` — measured
     *    section geometry for the window manager (R7).
     *  - `__xrSelection()` — ONE on-demand read per selection
     *    action (R4): the selection's text, its section, its
     *    normalised char offsets and ±40 chars of context, as
     *    JSON. Nothing polls it.
     *
     * Host-called entries: `__xrGoto` / `__xrStart` (jump +
     * opening restore, quote-preferring), `__xrFollowSection`
     * (read-aloud follow, R6), `__xrApplyMarks` (stored
     * highlights reapplied as <mark class="xr-hl">, R4),
     * `__xrMarkSearch` / `__xrClearSearch` (the transient
     * search-hit mark, R6), `__xrFillSection` /
     * `__xrEmptySection` (windowing, R7 — the section HTML is
     * FETCHED from the fake origin's /sec/ route, never pushed
     * through evaluateJavascript strings).
     *
     * Text arithmetic: offsets are into the section's
     * WHITESPACE-NORMALISED DOM text, built by one TreeWalker
     * pass with a normalised→raw index map — the same text at
     * selection-capture time and at mark-reapply time, so
     * quotes, contexts and offsets are self-consistent. The
     * merger's char map stays the ground truth for block /
     * progression anchors (a separate ladder, §4.3).
     */
    private const val BRIDGE_JS: String =
        "(function(){" +
            "var RIVER='https://xreader.river';" +
            "function secEl(n){return document.getElementById(" +
            "'xr-sec-'+n);}" +
            "function norm(s){return s.replace(/\\s+/g,' ');}" +
            "function anchorGeom(){" +
            "var secs=document.querySelectorAll('section.xr-sec');" +
            "for(var i=0;i<secs.length;i++){" +
            "if(secs[i].getBoundingClientRect().bottom>0){" +
            "var blocks=secs[i]" +
            ".querySelectorAll('[data-xr-block]');" +
            "var b=0;" +
            "for(var k=0;k<blocks.length;k++){" +
            "if(blocks[k].getBoundingClientRect().bottom>0){" +
            "b=parseInt(blocks[k].getAttribute('data-xr-block')," +
            "10)||0;break;}}" +
            "return i+':'+b;}}" +
            "return '';}" +
            "window.__xrAnchor=function(){return anchorGeom();};" +
            "window.__xrGoto=function(n,b,q){" +
            "var sec=secEl(n);" +
            "if(!sec){return;}" +
            "var target=null;" +
            "if(q){" +
            "var needle=q.replace(/\\s+/g,' ')" +
            ".replace(/^\\s+|\\s+$/g,'').substring(0,60);" +
            "if(needle){" +
            "var blocks=sec.querySelectorAll('[data-xr-block]');" +
            "for(var i=0;i<blocks.length;i++){" +
            "if(blocks[i].textContent.replace(/\\s+/g,' ')" +
            ".indexOf(needle)>=0){target=blocks[i];break;}}}}" +
            "if(!target&&b>0){" +
            "target=sec.querySelector(" +
            "'[data-xr-block=\"'+b+'\"]');}" +
            "if(!target){target=sec;}" +
            "target.scrollIntoView();};" +
            // v1.5.35 (restore §C.2): __xrStart RETURNS the
            // post-scroll anchor — the host's acknowledgement
            // read (suspendEvaluate) is how a restore
            // application proves it landed, never a
            // fire-and-forget.
            "window.__xrStart=function(n,b,q){" +
            "window.__xrGoto(n,b,q);" +
            "if(window.XRiverBridge&&XRiverBridge.onReady){" +
            "try{XRiverBridge.onReady();}catch(e){}}" +
            "return anchorGeom();};" +
            "window.__xrFollowSection=function(n){" +
            "var sec=secEl(n);if(!sec){return;}" +
            "var r=sec.getBoundingClientRect();" +
            "if(r.bottom<0||r.top>window.innerHeight){" +
            "sec.scrollIntoView();}};" +
            "window.__xrOffsets=function(){" +
            "var secs=document.querySelectorAll('section.xr-sec');" +
            "var out=[];" +
            "for(var i=0;i<secs.length;i++){" +
            "out.push(Math.round(secs[i].offsetTop));}" +
            "return out.join(',');};" +
            "window.__xrSectionHeight=function(n){" +
            "var sec=secEl(n);" +
            "return sec?''+Math.round(" +
            "sec.getBoundingClientRect().height):'0';};" +
            // ── normalised-text machinery (R4/R6) ──
            "function normMap(sec){" +
            "var nodes=[];" +
            "var w=document.createTreeWalker(sec,NodeFilter.SHOW_TEXT);" +
            "while(w.nextNode()){nodes.push(w.currentNode);}" +
            "var raw='';" +
            "for(var j=0;j<nodes.length;j++){raw+=nodes[j].nodeValue;}" +
            "var ntext='';var map=[];" +
            "for(var i=0;i<raw.length;i++){" +
            "var c=raw.charAt(i);" +
            "if(/\\s/.test(c)){" +
            "if(ntext.length>0&&" +
            "ntext.charAt(ntext.length-1)!==' '){" +
            "ntext+=' ';map.push(i);}}" +
            "else{ntext+=c;map.push(i);}}" +
            "return{raw:raw,ntext:ntext,map:map,nodes:nodes};}" +
            "function rawToNode(nodes,rawIdx){" +
            "var acc=0;" +
            "for(var j=0;j<nodes.length;j++){" +
            "var len=nodes[j].nodeValue.length;" +
            "if(rawIdx<acc+len){return{node:nodes[j],off:rawIdx-acc};}" +
            "acc+=len;}" +
            "var last=nodes[nodes.length-1];" +
            "return{node:last,off:last?last.nodeValue.length:0};}" +
            "function wrapNormRange(built,nStart,nEnd,cls,hlId){" +
            "var rStart=built.map[nStart];" +
            "if(rStart===undefined){return false;}" +
            "var rEnd=(nEnd<built.map.length)?built.map[nEnd]" +
            ":built.raw.length;" +
            "var a=rawToNode(built.nodes,rStart);" +
            "var b=rawToNode(built.nodes,rEnd);" +
            "if(!a.node||!b.node){return false;}" +
            "var range=document.createRange();" +
            "range.setStart(a.node,a.off);" +
            "range.setEnd(b.node,b.off);" +
            "var mark=document.createElement('mark');" +
            "mark.className=cls;" +
            "if(hlId){mark.setAttribute('data-hl-id',hlId);}" +
            "try{mark.appendChild(range.extractContents());" +
            "range.insertNode(mark);return true;}" +
            "catch(e){return false;}}" +
            "function unwrapMark(el){" +
            "var p=el.parentNode;if(!p){return;}" +
            "while(el.firstChild){p.insertBefore(el.firstChild,el);}" +
            "p.removeChild(el);}" +
            "window.__xrSelection=function(){" +
            "var sel=window.getSelection();" +
            "if(!sel||sel.isCollapsed||sel.rangeCount===0){return '';}" +
            "var range=sel.getRangeAt(0);" +
            "var text=sel.toString();if(!text){return '';}" +
            "var el=range.startContainer;" +
            "if(el.nodeType!==1){el=el.parentElement;}" +
            "var sec=null;" +
            "while(el){" +
            "if(el.tagName&&" +
            "el.tagName.toLowerCase()==='section'&&" +
            "el.getAttribute('data-xr-section')!==null){" +
            "sec=el;break;}" +
            "el=el.parentElement;}" +
            "if(!sec){return '';}" +
            "var built=normMap(sec);" +
            "var nq=norm(text);" +
            "var idx=built.ntext.indexOf(nq);" +
            "if(idx<0){return '';}" +
            "var out={s:parseInt(sec.getAttribute('data-xr-section'),10)||0," +
            "start:idx,end:idx+nq.length,text:text," +
            "before:built.ntext.substring(Math.max(0,idx-40),idx)," +
            "after:built.ntext.substring(idx+nq.length,idx+nq.length+40)};" +
            "return JSON.stringify(out);};" +
            "window.__xrApplyMarks=function(jsonStr){" +
            "var specs;" +
            "try{specs=JSON.parse(jsonStr);}catch(e){return '0';}" +
            "var applied=0;" +
            "for(var s=0;s<specs.length;s++){" +
            "var spec=specs[s];" +
            "var sec=secEl(spec.s);if(!sec){continue;}" +
            "if(sec.querySelector(" +
            "'mark[data-hl-id=\"'+spec.id+'\"]')){continue;}" +
            "var built=normMap(sec);" +
            "var q=norm(spec.q);if(!q){continue;}" +
            "var idx=built.ntext.indexOf(q);var best=-1;" +
            "while(idx>=0){" +
            "var pre=built.ntext.substring(Math.max(0,idx-40),idx);" +
            "var post=built.ntext.substring(idx+q.length,idx+q.length+40);" +
            "if((!spec.b||pre===spec.b||pre.slice(-spec.b.length)===spec.b)&&" +
            "(!spec.a||post.indexOf(spec.a)===0)){best=idx;break;}" +
            "if(best<0){best=idx;}" +
            "idx=built.ntext.indexOf(q,idx+1);}" +
            "if(best<0){continue;}" +
            "if(wrapNormRange(built,best,best+q.length,'xr-hl',spec.id)){" +
            "applied++;}}" +
            "return ''+applied;};" +
            "window.__xrClearSearch=function(){" +
            "var marks=document.querySelectorAll('mark.xr-search');" +
            "for(var i=marks.length-1;i>=0;i--){unwrapMark(marks[i]);}};" +
            "window.__xrMarkSearch=function(n,off,q){" +
            "window.__xrClearSearch();" +
            "var sec=secEl(n);if(!sec){return '0';}" +
            "var built=normMap(sec);" +
            "var nq=norm(q);if(!nq){return '0';}" +
            "var idx=built.ntext.indexOf(nq);" +
            "var best=-1;var bestDist=1e18;" +
            "while(idx>=0){" +
            "var d=Math.abs(idx-off);" +
            "if(d<bestDist){bestDist=d;best=idx;}" +
            "idx=built.ntext.indexOf(nq,idx+1);}" +
            "if(best<0){return '0';}" +
            "if(!wrapNormRange(built,best,best+nq.length,'xr-search',null)){" +
            "return '0';}" +
            "var m=sec.querySelector('mark.xr-search');" +
            "if(m){m.scrollIntoView({block:'center'});}" +
            "return '1';};" +
            // ── windowing (R7) ──
            "window.__xrFilledMap={};" +
            "function anchorTop(){" +
            "var secs=document.querySelectorAll('section.xr-sec');" +
            "for(var i=0;i<secs.length;i++){" +
            "if(secs[i].getBoundingClientRect().bottom>0){" +
            "return{el:secs[i],top:secs[i].getBoundingClientRect().top};}}" +
            "return null;}" +
            "function compensate(a){" +
            "if(!a){return;}" +
            "var t2=a.el.getBoundingClientRect().top;" +
            "if(t2!==a.top){window.scrollBy(0,t2-a.top);}}" +
            "window.__xrFillSection=function(n){" +
            "var sec=secEl(n);" +
            "if(!sec||window.__xrFilledMap[n]){return;}" +
            "window.__xrFilledMap[n]=1;" +
            "fetch(RIVER+'/sec/'+n).then(function(r){return r.text();})" +
            ".then(function(html){" +
            "var a=anchorTop();" +
            "sec.innerHTML=html;" +
            "sec.classList.remove('xr-gap');" +
            "sec.style.minHeight='';" +
            "compensate(a);})" +
            ".catch(function(){window.__xrFilledMap[n]=0;});};" +
            "window.__xrEmptySection=function(n,h){" +
            "var sec=secEl(n);" +
            "if(!sec||!window.__xrFilledMap[n]){return;}" +
            "var a=anchorTop();" +
            "sec.innerHTML='';" +
            "sec.classList.add('xr-gap');" +
            "sec.style.minHeight=h+'px';" +
            "window.__xrFilledMap[n]=0;" +
            "compensate(a);};" +
            // ── taps: marks, images, footnotes, anchors ──
            "document.addEventListener('click',function(ev){" +
            "var m=ev.target;" +
            "while(m&&!(m.tagName&&" +
            "m.tagName.toLowerCase()==='mark'&&" +
            "m.getAttribute('data-hl-id'))){" +
            "m=m.parentElement;}" +
            "if(m){" +
            "ev.preventDefault();" +
            "if(window.XRiverBridge&&XRiverBridge.onHighlightTap){" +
            "try{XRiverBridge.onHighlightTap(" +
            "m.getAttribute('data-hl-id'));}catch(e){}}" +
            "return;}" +
            "var n=ev.target&&ev.target.nodeName?" +
            "ev.target.nodeName.toLowerCase():'';" +
            "if(n==='img'||n==='image'){" +
            "var s=ev.target.getAttribute('src')||" +
            "ev.target.getAttribute('xlink:href')||" +
            "ev.target.getAttribute('href')||'';" +
            "if(s.indexOf('https://xreader.river/res/')===0){" +
            "ev.preventDefault();" +
            "if(window.XRiverBridge&&XRiverBridge.onImageTap){" +
            "try{XRiverBridge.onImageTap(s);}catch(e){}}" +
            "return;}}" +
            "var t=ev.target;" +
            "while(t&&!(t.tagName&&t.tagName.toLowerCase()==='a')){" +
            "t=t.parentElement;}" +
            "if(!t){return;}" +
            "var href=t.getAttribute('href');" +
            "if(!href||href.charAt(0)!=='#'){return;}" +
            "if(t.getAttribute('data-xr-noteref')==='1'){" +
            "ev.preventDefault();" +
            "if(window.XRiverBridge&&XRiverBridge.onFootnoteTap){" +
            "try{XRiverBridge.onFootnoteTap(href.substring(1));}" +
            "catch(e){}}" +
            "return;}" +
            "var el=document.getElementById(href.substring(1));" +
            "if(!el){" +
            "var mm=/^xr-(\\d+)-/.exec(href.substring(1));" +
            "if(mm){el=secEl(parseInt(mm[1],10));}}" +
            "if(el){ev.preventDefault();" +
            "el.scrollIntoView({behavior:'smooth'});}},false);" +
            "})();"
}
