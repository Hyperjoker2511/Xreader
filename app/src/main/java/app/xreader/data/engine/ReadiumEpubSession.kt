package app.xreader.data.engine

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.prefs.settingsDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.InMemoryResource
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * v1.5.6 (§3.1 row 3): the EPUB session on Readium Kotlin 3.1.2
 * (BSD-3) — the REAL 3.1.2 API, read from the published artifacts:
 *
 *  - open: `AssetRetriever(contentResolver, DefaultHttpClient())`
 *    → `retrieve(File)` / `retrieve(AbsoluteUrl)` →
 *    `PublicationOpener(DefaultPublicationParser(context,
 *    httpClient, assetRetriever)).open(asset, allowUserInteraction
 *    = false)` returning `Try<Publication, OpenError>` (suspend).
 *  - outline: `publication.tableOfContents` (hierarchical [Link]s,
 *    flattened here) with targets in the fixed `{"section":i}`
 *    form, where i is the READING-ORDER index of the link's
 *    resource; when a book carries no TOC the reading order itself
 *    is the outline.
 *  - locators: [Locator] serializes itself (`toJSON()` /
 *    `Locator.fromJSON`) — the stored form is the toolkit's own.
 *  - text: the reading-order resource's bytes via
 *    `publication.get(link)` → `Resource.read()`, HTML-stripped
 *    with jsoup (already shipped). The shared ContentService
 *    (`publication.content()`) is the fallback when a resource
 *    read yields nothing.
 */
/** Href equality tolerant of the forms Readium emits: a locator's
 * href and a reading-order href may differ by fragment, by a
 * leading base path, or by percent-encoding of the same path. */
/** The path portion of an href: for an absolute URL the scheme
 * and host are dropped; a relative href IS its path (stripping
 * its first segment was the v1.5.6 harness find — a relative
 * "OEBPS/ch1.xhtml" must not collapse to "ch1.xhtml"). */
private fun String.pathPortion(): String {
    val noHost = if (contains("://")) {
        substringAfter("://").substringAfter('/', "")
    } else {
        this
    }
    return noHost.trimStart('/')
}

internal fun hrefMatches(a: String, b: String): Boolean {
    if (a == b) return true
    val bareA = a.substringBefore('#')
    val bareB = b.substringBefore('#')
    if (bareA == bareB) return true
    val pathA = bareA.pathPortion()
    val pathB = bareB.pathPortion()
    if (pathA == pathB) return true
    val decodedA = runCatching { java.net.URLDecoder.decode(pathA, "UTF-8") }
        .getOrDefault(pathA)
    val decodedB = runCatching { java.net.URLDecoder.decode(pathB, "UTF-8") }
        .getOrDefault(pathB)
    return decodedA == decodedB
}

/**
 * v1.5.9 (F-3a): the canonical map key [hrefMatches] equivalence
 * collapses to — decoded path portion of the fragment-free
 * href. Two hrefs match under [hrefMatches] iff their canonical
 * keys are equal, so a map on this key reproduces the scan's
 * verdicts exactly (first reading-order index wins, as with
 * `indexOfFirst`).
 */
internal fun canonicalHrefKey(href: String): String {
    val path = href.substringBefore('#').pathPortion()
    return runCatching { java.net.URLDecoder.decode(path, "UTF-8") }
        .getOrDefault(path)
}

/** v1.5.9 (F-3a): the anchor title per section, precomputed in
 * ONE pass over the outline at build time — the title of the
 * last outline entry whose target is at or before the section. */
internal fun buildAnchorBySection(
    outline: List<OutlineEntry>,
    sectionCount: Int
): List<String?> {
    if (sectionCount <= 0) return emptyList()
    val pairs = outline.mapNotNull { entry ->
        LocatorJson.targetIndex(entry.targetJson)?.let { it to entry.title }
    }.sortedBy { it.first }
    val result = MutableList<String?>(sectionCount) { null }
    var current: String? = null
    var next = 0
    for (section in 0 until sectionCount) {
        while (next < pairs.size && pairs[next].first <= section) {
            current = pairs[next].second
            next++
        }
        result[section] = current
    }
    return result
}

/**
 * v1.5.10 (G3): a user-supplied font resolved for one book —
 * the picked file in the app-private fonts dir, served to the
 * navigator at the synthetic publication-relative [href] and
 * declared under [familyName]. The reader builds the
 * toolkit's FontFamilyDeclaration from these two strings;
 * the BYTES never leave this engine (they ride the wrapped
 * container, below).
 */
data class EpubUserFont(
    val fileName: String,
    val href: String,
    val familyName: String
)

/**
 * v1.5.10 (G3): the publication Container with ONE extra
 * entry — the user's font file at its synthetic href. Every
 * other lookup, the entry set (plus the font), the source
 * URL and close semantics delegate to the book's own
 * container untouched: the parser and the navigator's
 * WebView server read the book exactly as before, and the
 * font href resolves to an in-memory resource over the
 * picked file's bytes. This is the "fetcher registered at
 * publication open" of the plan, in 3.1.2's actual shape
 * (3.1.2 containers replaced fetchers — verified against the
 * shipped AARs: `Container` / `ContainerAsset` /
 * `InMemoryResource` are the public seams, and
 * `CompositeContainer`'s own sourceUrl semantics are why
 * this delegates explicitly instead).
 */
private class UserFontContainer(
    private val delegate: Container<Resource>,
    private val fontUrl: Url,
    private val resolvedFontUrl: AbsoluteUrl,
    private val fontResource: Resource
) : Container<Resource> {

    override val sourceUrl: AbsoluteUrl?
        get() = delegate.sourceUrl

    override val entries: Set<Url>
        get() = delegate.entries + fontUrl

    override fun get(url: Url): Resource? =
        if (matches(url)) fontResource else delegate[url]

    override fun iterator(): Iterator<Url> = entries.iterator()

    override fun close() {
        runCatching { fontResource.close() }
        delegate.close()
    }

    /** The font may be requested as the bare relative href,
     * resolved against the publication base, or as the
     * absolute URL the WebView server builds — all three
     * forms name the same synthetic entry. */
    private fun matches(url: Url): Boolean =
        url == fontUrl || url == resolvedFontUrl ||
            url.toString().endsWith("/" + fontUrl.toString())
}

class ReadiumEpubSession private constructor(
    val publication: Publication,
    override val outline: List<OutlineEntry>,
    /** Reading-order href (string form) per section index. */
    private val readingOrderHrefs: List<String>,
    /** v1.5.10 (G3): the user font this session serves, or
     * null when the book uses the bundled font road. */
    val userFont: EpubUserFont? = null
) : DocSession {

    override val format: BookFormat = BookFormat.EPUB
    override val sectionCount: Int = publication.readingOrder.size

    /**
     * v1.5.9 (F-3a): reading-order section per canonical href
     * key, precomputed ONCE here (keys normalised by the same
     * rules [hrefMatches] encodes — see [canonicalHrefKey]).
     * The locator pipeline's hot path is a map lookup; the old
     * linear scan survives only as a fallback. Public so the
     * budget test can assert the precomputation directly.
     */
    val hrefToSection: Map<String, Int> = run {
        val map = LinkedHashMap<String, Int>()
        readingOrderHrefs.forEachIndexed { index, href ->
            map.putIfAbsent(canonicalHrefKey(href), index)
        }
        map
    }

    /** v1.5.9 (F-3a): anchor title per section, precomputed
     * once (see [buildAnchorBySection]) — [anchorTitleFor] is
     * a list index and parses no JSON per call. */
    val anchorBySection: List<String?> =
        buildAnchorBySection(outline, sectionCount)

    /**
     * v1.5.8 (G10): fixed-layout detection from the publication's
     * own metadata — `rendition:layout = pre-paginated` surfaces
     * in Readium as the presentation layout FIXED. A fixed-layout
     * book's pages are fixed like a document's: it is LABELLED
     * fixed-layout and its per-book default is paginated (G10's
     * DECIDED shape — it still opens in THIS reader; the PDF
     * reader cannot open an EPUB). Reflowable books are
     * untouched.
     */
    val isFixedLayout: Boolean
        get() = publication.metadata.presentation.layout == EpubLayout.FIXED

    /** The section (reading-order) index a stored Readium locator
     * JSON points at, or null when it names no known resource. */
    fun sectionIndexOfLocator(locatorJson: String?): Int? {
        val href = LocatorJson.epubLocatorHref(locatorJson) ?: return null
        return sectionIndexOfHref(href)
    }

    /** v1.5.9 (F-3a): the section a live locator [href] names —
     * map lookup first; the existing [hrefMatches] scan runs
     * only as a fallback when the map misses. */
    fun sectionIndexOfHref(href: String): Int? {
        hrefToSection[canonicalHrefKey(href)]?.let { return it }
        return readingOrderHrefs.indexOfFirst { hrefMatches(it, href) }
            .takeIf { it >= 0 }
    }

    /** A Readium [Locator] for a reading-order section — the jump
     * target the navigator consumes. */
    fun locatorForSection(section: Int): Locator? {
        val link = publication.readingOrder.getOrNull(section) ?: return null
        return publication.locatorFromLink(link)
    }

    /**
     * v1.5.7 (Scope C — EPUB book reader): a [Locator] for a
     * whole-book progression fraction (0..1), via the toolkit's
     * LocatorService — in 3.1.2 that is the `Publication`
     * extension `locateProgression(totalProgression)`
     * (`LocatorServiceKt`, readium-shared), which resolves the
     * publication's registered [org.readium.r2.shared.publication.services.LocatorService]
     * through `findService` and returns null when none is
     * registered. The percent slider's seek target. Null when
     * the publication cannot resolve the progression (the reader
     * then falls back to a section jump).
     */
    suspend fun locatorForProgression(fraction: Double): Locator? =
        runCatching {
            publication.locateProgression(fraction.coerceIn(0.0, 1.0))
        }.getOrNull()

    /** A Readium [Locator] from stored JSON, or null. */
    fun locatorFromJson(locatorJson: String?): Locator? {
        if (locatorJson.isNullOrBlank()) return null
        return runCatching {
            Locator.fromJSON(org.json.JSONObject(locatorJson))
        }.getOrNull()
    }

    /** The [Link] an outline entry jumps to. */
    fun linkForOutline(entry: OutlineEntry): Link? {
        val section = LocatorJson.targetIndex(entry.targetJson) ?: return null
        return publication.readingOrder.getOrNull(section)
    }

    /** The outline title nearest the position in [section] — the
     * anchor label progress rows carry. v1.5.9 (F-3a): a list
     * index into the precomputed [anchorBySection]; zero JSON
     * parses per call. */
    fun anchorTitleFor(section: Int): String? =
        anchorBySection.getOrNull(section)

    override suspend fun textAt(index: Int): String = withContext(Dispatchers.IO) {
        val link = publication.readingOrder.getOrNull(index)
            ?: return@withContext ""
        val fromResource = runCatching {
            val bytes = publication.get(link)?.read()?.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                ""
            } else {
                Jsoup.parse(bytes.decodeToString()).text()
            }
        }.getOrDefault("")
        if (fromResource.isNotBlank()) return@withContext fromResource
        // Fallback: the toolkit's own content extraction.
        runCatching {
            val locator = publication.locatorFromLink(link)
            val content = publication.content(locator)
            content?.text("\n") ?: ""
        }.getOrDefault("")
    }

    override fun close() {
        runCatching { publication.close() }
    }

    companion object {

        /** v1.5.10 (G3): the suffix of the per-book DataStore
         * key holding the picked font's file name
         * (`localreader.<id>` + this). `LocalReaderPrefs`
         * builds its key from this constant — one spelling,
         * two readers. */
        const val USER_FONT_FILE_KEY_SUFFIX = ".epub.user_font_file"

        /** The CSS family every user font is declared under
         * (one user font per book at a time; each publication
         * carries its own declaration, so the name need not
         * encode the file). */
        const val USER_FONT_FAMILY_NAME = "XReaderUserFont"

        /** The synthetic publication-relative directory user
         * fonts are served under — never a real book path. */
        const val USER_FONT_HREF_PREFIX = "xreader-fonts"

        fun userFontHref(fileName: String): String =
            "$USER_FONT_HREF_PREFIX/$fileName"

        /** The app-private fonts dir. Fonts are NOT books:
         * the no-second-copy doctrine governs book files; a
         * picked font is copied here precisely so the book's
         * session can serve it as a publication asset. */
        fun fontsDir(context: Context): File =
            File(context.filesDir, "fonts")

        suspend fun open(context: Context, book: Book, filesRoot: File): ReadiumEpubSession =
            withContext(Dispatchers.IO) {
                val httpClient = DefaultHttpClient()
                val retriever = AssetRetriever(context.contentResolver, httpClient)
                val asset = when {
                    book.documentUri != null -> {
                        val url = Url(book.documentUri!!) as? AbsoluteUrl
                            ?: throw LocalFileMissingException("file not found")
                        // Existence probe first: a moved/deleted
                        // file must read as "file not found", not
                        // as a parse failure.
                        val reachable = runCatching {
                            context.contentResolver
                                .openInputStream(Uri.parse(book.documentUri))?.close()
                            true
                        }.getOrDefault(false)
                        if (!reachable) throw LocalFileMissingException("file not found")
                        when (val result = retriever.retrieve(url)) {
                            is Try.Success -> result.value
                            is Try.Failure -> throw LocalFileMissingException("file not found")
                        }
                    }
                    book.filePath != null -> {
                        val file = File(filesRoot, book.filePath!!)
                        if (!file.isFile) throw LocalFileMissingException("file not found")
                        when (val result = retriever.retrieve(file)) {
                            is Try.Success -> result.value
                            is Try.Failure -> throw LocalFileMissingException(
                                "That EPUB couldn't be opened."
                            )
                        }
                    }
                    else -> throw LocalFileMissingException("file not found")
                }
                // v1.5.10 (G3): when this book carries a user
                // font, the font file's bytes join the
                // publication's container at a synthetic href
                // BEFORE the opener runs — the navigator's
                // WebView server resolves the @font-face source
                // through publication.get(), which reads this
                // container. A non-container asset (never an
                // EPUB in practice) or a missing font file
                // leaves the book exactly as it was: the
                // bundled font road, no failure.
                val resolvedFont = resolveUserFont(
                    context.applicationContext, book.id
                )
                val effectiveAsset = if (
                    resolvedFont != null && asset is ContainerAsset
                ) {
                    val fontUrl = Url(resolvedFont.href)
                    val resolvedUrl = fontUrl?.let {
                        asset.container.sourceUrl?.resolve(it)
                    }
                    if (fontUrl == null || resolvedUrl == null) {
                        asset
                    } else {
                        ContainerAsset(
                            asset.format,
                            UserFontContainer(
                                delegate = asset.container,
                                fontUrl = fontUrl,
                                resolvedFontUrl = resolvedUrl,
                                fontResource = InMemoryResource(
                                    resolvedFont.bytes, resolvedUrl
                                )
                            )
                        )
                    }
                } else {
                    asset
                }
                val opener = PublicationOpener(
                    DefaultPublicationParser(
                        context.applicationContext, httpClient, retriever,
                        pdfFactory = null
                    )
                )
                val publication = when (
                    val result = opener.open(
                        effectiveAsset, allowUserInteraction = false
                    )
                ) {
                    is Try.Success -> result.value
                    is Try.Failure -> throw LocalFileMissingException(
                        "That EPUB couldn't be opened."
                    )
                }
                val hrefs = publication.readingOrder.map {
                    it.href.resolve(publication.baseUrl).toString()
                }
                ReadiumEpubSession(
                    publication = publication,
                    outline = buildOutline(publication, hrefs),
                    readingOrderHrefs = hrefs,
                    userFont = if (effectiveAsset !== asset) {
                        EpubUserFont(
                            fileName = resolvedFont!!.fileName,
                            href = resolvedFont.href,
                            familyName = USER_FONT_FAMILY_NAME
                        )
                    } else {
                        null
                    }
                )
            }

        /** The resolved font behind [EpubUserFont]: the public
         * strings plus the file's bytes (never exposed on the
         * session). */
        private class ResolvedFont(
            val fileName: String,
            val href: String,
            val bytes: ByteArray
        )

        /**
         * v1.5.10 (G3): the per-book user font for [bookId],
         * read from the SAME settings DataStore key
         * `LocalReaderPrefs` writes (the reader's prefs class
         * builds its key from [USER_FONT_FILE_KEY_SUFFIX]).
         * The session resolves it itself because the open road
         * (BookEngine) carries no prefs channel — the seam the
         * build map assigns to this file. Any failure (no
         * stored choice, the file moved/deleted, unreadable)
         * resolves to null: the book opens on the bundled road.
         */
        private suspend fun resolveUserFont(
            context: Context,
            bookId: Long
        ): ResolvedFont? {
            val fileName = runCatching {
                context.settingsDataStore.data.first()[
                    stringPreferencesKey(
                        "localreader.$bookId$USER_FONT_FILE_KEY_SUFFIX"
                    )
                ]
            }.getOrNull() ?: return null
            // A plain file name only — a stored value may
            // never escape the fonts dir.
            if (fileName.isBlank() || File(fileName).name != fileName) {
                return null
            }
            val file = File(fontsDir(context), fileName)
            if (!file.isFile) return null
            val bytes = runCatching { file.readBytes() }.getOrNull()
                ?: return null
            if (bytes.isEmpty()) return null
            return ResolvedFont(
                fileName = fileName,
                href = userFontHref(fileName),
                bytes = bytes
            )
        }

        /** TOC flattened in document order; each entry's target is
         * the reading-order index of its resource. TOC-less books
         * fall back to one entry per reading-order resource. */
        private fun buildOutline(
            publication: Publication,
            hrefs: List<String>
        ): List<OutlineEntry> {
            val entries = mutableListOf<OutlineEntry>()
            fun sectionOf(link: Link): Int? {
                val href = link.href.resolve(publication.baseUrl).toString()
                return hrefs.indexOfFirst { hrefMatches(it, href) }
                    .takeIf { it >= 0 }
            }
            fun walk(links: List<Link>) {
                for (link in links) {
                    val section = sectionOf(link)
                    val title = link.title?.trim().orEmpty()
                    if (section != null && title.isNotEmpty()) {
                        entries += OutlineEntry(
                            title = title,
                            targetJson = LocatorJson.epubSectionTarget(section)
                        )
                    }
                    walk(link.children)
                }
            }
            walk(publication.tableOfContents)
            if (entries.isNotEmpty()) return entries
            return publication.readingOrder.mapIndexed { index, link ->
                OutlineEntry(
                    title = link.title?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "Section ${index + 1}",
                    targetJson = LocatorJson.epubSectionTarget(index)
                )
            }
        }
    }
}
