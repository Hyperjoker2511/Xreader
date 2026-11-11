package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.MediaClip
import app.xreader.domain.content.MediaOverlay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Hard size caps shared by every archive reader in the app (the
 * EPUB/CBZ parsers, backup restore, the book importer). Sized
 * generously — a backup holding thousands of book files, an EPUB
 * with thousands of chapters, or a comic with thousands of pages
 * stays far below every limit — but hard: an archive that expands
 * past them (the classic zip-bomb shape) fails fast with a
 * plain-words error instead of filling the phone's storage or
 * running the process out of memory.
 */
object ArchiveLimits {
    /** Zip entries in one archive. */
    const val MAX_ENTRIES = 100_000

    /** Bytes one metadata entry (the backup payload JSON,
     * ComicInfo.xml, a files manifest…) may be read into memory. */
    const val MAX_METADATA_ENTRY_BYTES = 64L * 1024 * 1024

    /** Bytes one streamed entry (a comic page, one backed-up book
     * file, one EPUB member) may expand to. */
    const val MAX_STREAMED_ENTRY_BYTES = 1024L * 1024 * 1024

    /** Bytes a whole archive may expand to, across all entries. */
    const val MAX_TOTAL_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024

    /** Bytes of the picked/downloaded file itself at import. */
    const val MAX_SOURCE_FILE_BYTES = 2L * 1024 * 1024 * 1024
}

/** Thrown the moment an archive crosses an [ArchiveLimits] cap;
 * each reader catches it and turns it into its plain-words error. */
internal class ArchiveLimitException(message: String) : Exception(message)

/** Running total of expanded bytes for one archive read. */
internal class ArchiveBudget(private val maxTotalBytes: Long) {
    var totalBytes: Long = 0
        private set

    fun charge(bytes: Long, what: String) {
        totalBytes += bytes
        if (totalBytes > maxTotalBytes) {
            throw ArchiveLimitException("$what expands past the safety limit")
        }
    }
}

/** Reads this stream fully, refusing (mid-read, so memory stays
 * bounded) once it passes [maxBytes]. */
internal fun InputStream.readBytesCapped(maxBytes: Long, what: String): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw ArchiveLimitException("$what is too large")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Copies this stream to [out], charging every chunk to [budget]
 * and refusing once this single entry passes [maxEntryBytes]. */
internal fun InputStream.copyToCapped(
    out: OutputStream,
    budget: ArchiveBudget,
    maxEntryBytes: Long,
    what: String
): Long {
    val buffer = ByteArray(64 * 1024)
    var entryBytes = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        entryBytes += n
        if (entryBytes > maxEntryBytes) {
            throw ArchiveLimitException("$what is too large")
        }
        budget.charge(n.toLong(), what)
        out.write(buffer, 0, n)
    }
    return entryBytes
}

/**
 * A live zip-entry stream that charges everything its reader pulls
 * from it to [budget] (and to its own per-entry cap) — for sinks
 * that copy an entry themselves, like backup restore: the copy is
 * counted as it happens, and crossing a cap aborts it mid-stream.
 */
internal class CountingEntryInputStream(
    base: InputStream,
    private val budget: ArchiveBudget,
    private val maxEntryBytes: Long,
    private val what: String
) : FilterInputStream(base) {
    private var entryBytes = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) charge(1)
        return b
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val n = super.read(target, offset, length)
        if (n > 0) charge(n.toLong())
        return n
    }

    private fun charge(n: Long) {
        entryBytes += n
        if (entryBytes > maxEntryBytes) {
            throw ArchiveLimitException("$what is too large")
        }
        budget.charge(n, what)
    }
}

/**
 * EPUB (PLAN §4): streaming zip extraction (entry-by-entry, 64 KB
 * buffer — never the whole book in RAM), container → OPF → spine,
 * each spine item converted to the normalised model by
 * [XhtmlConverter]. Images and Media Overlay audio are copied to the
 * staged assets dir; SMIL overlays are parsed into the sync data model
 * ([MediaOverlay]) that Phase 4's playback consumes.
 *
 * DRM: `META-INF/encryption.xml` referencing anything other than font
 * files means the book's content is encrypted → [AppError.DrmProtected],
 * stated honestly, never faked (PLAN §0.2). Font-only obfuscation is
 * not DRM against reading and parses normally.
 */
/**
 * v1.5.5 (§4): what the EPUB probe reads — metadata, the spine's
 * chapter titles and the cover — WITHOUT converting a single
 * chapter. The importer's file-first registration files the book
 * from this skeleton in well under a second; the heavy spine
 * conversion runs afterwards as the completion job.
 */
data class EpubSkeleton(
    val title: String?,
    val author: String?,
    val description: String?,
    val language: String?,
    val coverFile: File?,
    /** Provisional chapter titles in spine order (text AND
     * image spine items — the completion's final list may
     * differ where front matter is skipped). */
    val chapterTitles: List<String>
)

class EpubParser : BookParser {

    override val format: BookFormat = BookFormat.EPUB

    /**
     * v1.5.5 (§4): the probe — unzip, container → OPF → spine
     * titles + cover, NOTHING converted. DRM keeps its honest
     * refusal HERE (a locked book is never filed), exactly as
     * [parse] classifies it. The unzip is the streaming reader
     * [parse] uses; everything after it is metadata work, so
     * registration lands in a fraction of a full parse.
     */
    fun probe(staged: File, workDir: File): AppResult<EpubSkeleton> {
        val unzipped = File(workDir, "unzipped-probe")
        extractZip(staged, unzipped)?.let { return AppResult.Err(it) }
        val encryption = File(unzipped, "META-INF/encryption.xml")
        if (encryption.isFile && encryptionBlocksContent(encryption)) {
            return AppResult.Err(AppError.DrmProtected)
        }
        val container = File(unzipped, "META-INF/container.xml")
        if (!container.isFile) {
            return AppResult.Err(AppError.BadFile("this EPUB is missing its contents list"))
        }
        val entryIndex = buildEntryIndex(unzipped)
        val opfPath = parseContainer(container)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's contents list couldn't be read"))
        val opfFile = entryFile(unzipped, entryIndex, opfPath)
            ?: return AppResult.Err(AppError.BadFile("this EPUB is missing its book file"))
        val opf = parseOpf(opfFile)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's book file couldn't be read"))
        val opfDir = opfPath.substringBeforeLast('/', "")
        val titles = mutableMapOf<String, String>()
        opf.tocId?.let { tocId ->
            opf.manifest[tocId]?.let { tocItem ->
                val ncxPath = ZipPaths.resolve(opfDir, tocItem.href)
                val ncxFile = entryFile(unzipped, entryIndex, ncxPath)
                if (ncxFile != null) {
                    val ncxDir = ncxPath.substringBeforeLast('/', "")
                    titles.putAll(
                        parseNcx(ncxFile).mapKeys { (src, _) -> ZipPaths.resolve(ncxDir, src) }
                    )
                }
            }
        }
        opf.manifest.values.firstOrNull { "nav" in it.properties }?.let { navItem ->
            val navPath = ZipPaths.resolve(opfDir, navItem.href)
            val navFile = entryFile(unzipped, entryIndex, navPath)
            if (navFile != null) {
                val navDir = navPath.substringBeforeLast('/', "")
                for ((href, label) in parseNav(navFile)) {
                    titles.putIfAbsent(ZipPaths.resolve(navDir, href), label)
                }
            }
        }
        // Provisional titles in spine order — text items by their
        // TOC/nav title, image items as illustrations (v1.5.5 §5:
        // a direct image spine item is a chapter, not a skip).
        val chapterTitles = mutableListOf<String>()
        var illustrations = 0
        for (idref in opf.spine) {
            val item = opf.manifest[idref] ?: continue
            val relPath = ZipPaths.resolve(opfDir, item.href)
            when {
                item.mediaType == "application/xhtml+xml" ->
                    chapterTitles += titles[relPath]
                        ?: "Chapter ${chapterTitles.size + 1}"
                item.mediaType.startsWith("image/") -> {
                    illustrations++
                    chapterTitles += titles[relPath] ?: "Illustration $illustrations"
                }
                else -> Unit
            }
        }
        // The cover, resolved exactly as parse() resolves it
        // (declared cover, else the guide cover page's first
        // picture) — a file copy, never a conversion.
        val coverSource = opf.coverHref?.let { href ->
            entryFile(unzipped, entryIndex, ZipPaths.resolve(opfDir, href))
        } ?: opf.guideCoverPageHref?.let { pageHref ->
            val pagePath = ZipPaths.resolve(opfDir, pageHref)
            val pageFile = entryFile(unzipped, entryIndex, pagePath)
            val pageDir = pagePath.substringBeforeLast('/', "")
            pageFile?.let { firstImageSrc(it) }?.let { src ->
                resolveImageEntry(entryIndex, pageDir, opfDir, src)
                    ?.let { actual -> File(unzipped, actual) }
            }
        }
        val coverFile = coverSource?.let { src ->
            val ext = src.name.substringAfterLast('.', "img").lowercase().take(5)
            val dest = File(workDir, "cover.$ext")
            try {
                src.copyTo(dest, overwrite = true)
                dest
            } catch (e: IOException) {
                null
            }
        }
        return AppResult.Ok(
            EpubSkeleton(
                title = opf.title,
                author = opf.author,
                description = opf.description,
                language = opf.language,
                coverFile = coverFile,
                chapterTitles = chapterTitles
            )
        )
    }

    /**
     * v1.0.6.2 (F6a): metadata-only read — the OPF description
     * without converting a single chapter. Powers heal-on-open for
     * library books imported before descriptions were mapped (or
     * whose stored synopsis is empty): cheap enough to run once
     * when the book's details are opened.
     */
    fun readDescription(staged: File, workDir: File): String? = try {
        val unzipped = File(workDir, "unzipped-meta")
        if (extractZip(staged, unzipped) != null) {
            null
        } else {
            val container = File(unzipped, "META-INF/container.xml")
            val opfPath = if (container.isFile) parseContainer(container) else null
            val opfFile = opfPath?.let { File(unzipped, it) }
            if (opfFile != null && opfFile.isFile) {
                parseOpf(opfFile)?.description?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        val unzipped = File(workDir, "unzipped")
        extractZip(staged, unzipped)?.let { return AppResult.Err(it) }

        val encryption = File(unzipped, "META-INF/encryption.xml")
        if (encryption.isFile && encryptionBlocksContent(encryption)) {
            return AppResult.Err(AppError.DrmProtected)
        }
        val container = File(unzipped, "META-INF/container.xml")
        if (!container.isFile) {
            return AppResult.Err(AppError.BadFile("this EPUB is missing its contents list"))
        }
        // v1.5.4 (WS-7): the case-insensitive entry index. Real
        // commercial EPUBs write internal references in a different
        // case from the stored zip entries (refs "images/cover.jpg",
        // entries "Images/Cover.JPG"); resolving references as
        // literal filesystem paths silently dropped the cover AND
        // every illustration of such a book while its text imported
        // perfectly. Every lookup below resolves through this index:
        // exact path first (the index maps a path to itself),
        // case-insensitive match as the fallback.
        val entryIndex = buildEntryIndex(unzipped)

        val opfPath = parseContainer(container)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's contents list couldn't be read"))
        val opfFile = entryFile(unzipped, entryIndex, opfPath)
            ?: return AppResult.Err(AppError.BadFile("this EPUB is missing its book file"))
        val opf = parseOpf(opfFile)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's book file couldn't be read"))

        val opfDir = opfPath.substringBeforeLast('/', "")
        val titles = mutableMapOf<String, String>()
        opf.tocId?.let { tocId ->
            opf.manifest[tocId]?.let { tocItem ->
                val ncxPath = ZipPaths.resolve(opfDir, tocItem.href)
                val ncxFile = entryFile(unzipped, entryIndex, ncxPath)
                if (ncxFile != null) {
                    val ncxDir = ncxPath.substringBeforeLast('/', "")
                    titles.putAll(
                        parseNcx(ncxFile).mapKeys { (src, _) -> ZipPaths.resolve(ncxDir, src) }
                    )
                }
            }
        }
        opf.manifest.values.firstOrNull { "nav" in it.properties }?.let { navItem ->
            val navPath = ZipPaths.resolve(opfDir, navItem.href)
            val navFile = entryFile(unzipped, entryIndex, navPath)
            if (navFile != null) {
                val navDir = navPath.substringBeforeLast('/', "")
                for ((href, label) in parseNav(navFile)) {
                    titles.putIfAbsent(ZipPaths.resolve(navDir, href), label)
                }
            }
        }

        val assetsDir = File(workDir, "assets").apply { mkdirs() }
        val usedAssetNames = mutableSetOf<String>()

        // Media overlays: spine href → overlay (audio + clips).
        val overlays = mutableMapOf<String, MediaOverlay>()
        for (item in opf.manifest.values) {
            val overlayId = item.mediaOverlayId ?: continue
            val smilItem = opf.manifest[overlayId] ?: continue
            val smilPath = ZipPaths.resolve(opfDir, smilItem.href)
            val smilFile = entryFile(unzipped, entryIndex, smilPath) ?: continue
            val smilDir = smilPath.substringBeforeLast('/', "")
            val parsed = SmilParser.parse(smilFile) ?: continue
            // Copy each referenced audio file once; group clips by the
            // text document they narrate, keeping the dominant audio.
            val byText = parsed.clips.groupBy { it.textHref }
            for ((textHref, clips) in byText) {
                val textPath = ZipPaths.resolve(smilDir, textHref)
                val audioCounts = clips.groupingBy { it.audioHref }.eachCount()
                val mainAudio = audioCounts.maxByOrNull { it.value }?.key ?: continue
                val audioSource = entryFile(
                    unzipped, entryIndex, ZipPaths.resolve(smilDir, mainAudio)
                ) ?: continue
                val audioName = ZipPaths.assetFileName(mainAudio, usedAssetNames)
                audioSource.copyTo(File(assetsDir, audioName), overwrite = true)
                overlays[textPath] = MediaOverlay(
                    audioFileName = audioName,
                    clips = clips.filter { it.audioHref == mainAudio }.map {
                        MediaClip(it.textId, it.beginSec, it.endSec)
                    }
                )
            }
        }

        val writer = ChapterFileWriter(File(workDir, "chapters"))
        var failures = 0
        // v1.5.4 (WS-7): chapter pictures that resolve to nothing.
        // Counted, never silent — the importer surfaces the count
        // ("Added — but N pictures in this file couldn't be read")
        // instead of an illustration-less book posing as a clean
        // import (the V154 EPUB-images finding).
        var droppedImages = 0
        // v1.5.5 (§5): the spine filter admits image items too —
        // a spine item that IS an image file (media-type image/*)
        // is an illustration chapter in real publisher books; the
        // old XHTML-only filter dropped it before conversion was
        // even attempted.
        val spineItems = opf.spine.mapNotNull { idref ->
            val item = opf.manifest[idref] ?: return@mapNotNull null
            if (item.mediaType != "application/xhtml+xml" &&
                !item.mediaType.startsWith("image/")
            ) {
                null
            } else {
                item to ZipPaths.resolve(opfDir, item.href)
            }
        }
        var illustrationCount = 0
        spineItems.forEachIndexed { index, (item, relPath) ->
            val itemFile = entryFile(unzipped, entryIndex, relPath)
            if (item.mediaType.startsWith("image/")) {
                // v1.5.5 (§5): a direct image spine item lands as
                // its own illustration chapter — one Image block
                // bound to the copied asset. A copy that fails is
                // a genuinely lost picture: counted, never silent.
                illustrationCount++
                val copied = itemFile?.let { source ->
                    runCatching {
                        val name = ZipPaths.assetFileName(relPath, usedAssetNames)
                        source.copyTo(File(assetsDir, name), overwrite = true)
                        name
                    }.getOrNull()
                }
                if (copied != null) {
                    writer.add(
                        titles[relPath] ?: "Illustration $illustrationCount",
                        ChapterContent(
                            blocks = listOf(
                                app.xreader.domain.content.ChapterBlock.Image(copied, alt = "")
                            )
                        )
                    )
                } else {
                    droppedImages++
                }
                onProgress(
                    if (spineItems.isEmpty()) 1f
                    else (index + 1).toFloat() / spineItems.size
                )
                return@forEachIndexed
            }
            val itemDir = relPath.substringBeforeLast('/', "")
            val converter = XhtmlConverter { src ->
                val actual = resolveImageEntry(entryIndex, itemDir, opfDir, src)
                if (actual == null) {
                    // v1.5.5 (§5): NOT counted here — the
                    // converter may still land the picture from a
                    // fallback candidate (srcset, dual href). The
                    // converter reports one unresolved entry per
                    // element that ultimately failed, and THAT is
                    // what the count below reads.
                    null
                } else {
                    val name = ZipPaths.assetFileName(actual, usedAssetNames)
                    File(unzipped, actual)
                        .copyTo(File(assetsDir, name), overwrite = true)
                    name
                }
            }
            val converted = if (itemFile != null) {
                runCatching {
                    itemFile.inputStream().buffered().use { converter.convert(it) }
                }.getOrNull()
            } else {
                null
            }
            if (converted == null) {
                failures++
                writer.add(titles[relPath] ?: "Chapter ${index + 1}", ChapterContent())
            } else {
                // v1.5.5 (§5): pictures that genuinely couldn't
                // be read — remote references stay a designed
                // drop (an EPUB's http images are not fetched at
                // import); a LOCAL reference that resolves to
                // nothing is a lost picture, counted once per
                // element for the honesty line.
                droppedImages += converted.unresolvedImages.count { !isRemoteRef(it) }
                if (converted.blocks.isEmpty() && overlays[relPath] == null) {
                    // v1.0.6.2 (F7): a spine item that converts
                    // cleanly to NO readable blocks and carries no
                    // narration is front matter (a cover/title page),
                    // not a chapter. Storing it as an empty chapter
                    // made every real chapter's number one higher
                    // than the book's own headings ("Chapter 2" over
                    // the chapter titled Chapter 1). Skip it.
                    // v1.5.5 (§5): the skip is no longer silent
                    // for image-bearing items — a plate page whose
                    // markup the converter still couldn't reach
                    // (its references never became unresolved
                    // entries because no construct matched) is a
                    // LOST illustration, counted like one.
                    if (converted.unresolvedImages.isEmpty() &&
                        itemFile != null && carriesImageMarkup(itemFile)
                    ) {
                        droppedImages++
                    }
                    Unit
                } else {
                    // v1.5.5 (§5): an item with 0 words and ≥1
                    // image (a plate page) reaches this branch
                    // with its Image blocks — it is EMITTED as
                    // its own illustration chapter, never
                    // skipped-and-counted.
                    val content = ChapterContent(
                        blocks = converted.blocks,
                        mediaOverlay = overlays[relPath]
                    )
                    writer.add(
                        titles[relPath] ?: converted.heading ?: "Chapter ${index + 1}",
                        content
                    )
                }
            }
            onProgress(if (spineItems.isEmpty()) 1f else (index + 1).toFloat() / spineItems.size)
        }
        if (spineItems.isNotEmpty() && failures * 2 > spineItems.size) {
            return AppResult.Err(AppError.BadFile("this EPUB's chapters couldn't be read"))
        }
        if (writer.metas.isEmpty()) {
            return AppResult.Err(AppError.BadFile("this EPUB has no readable chapters"))
        }

        // v1.5.4 (WS-7): the cover source resolves through the
        // entry index like everything else; when the declared
        // cover (meta / properties / guide-image) yields nothing
        // and the guide pointed at a cover PAGE, the cover is
        // that page's first resolvable image — the third cover
        // declaration shape commercial EPUBs use.
        val coverSource = opf.coverHref?.let { href ->
            entryFile(unzipped, entryIndex, ZipPaths.resolve(opfDir, href))
        } ?: opf.guideCoverPageHref?.let { pageHref ->
            val pagePath = ZipPaths.resolve(opfDir, pageHref)
            val pageFile = entryFile(unzipped, entryIndex, pagePath)
            val pageDir = pagePath.substringBeforeLast('/', "")
            pageFile?.let { firstImageSrc(it) }?.let { src ->
                resolveImageEntry(entryIndex, pageDir, opfDir, src)
                    ?.let { actual -> File(unzipped, actual) }
            }
        }
        val coverFile = coverSource?.let { src ->
            val ext = src.name.substringAfterLast('.', "img").lowercase().take(5)
            val dest = File(workDir, "cover.$ext")
            try {
                src.copyTo(dest, overwrite = true)
                dest
            } catch (e: IOException) {
                null
            }
        }

        return AppResult.Ok(
            ParsedBook(
                title = opf.title,
                author = opf.author,
                description = opf.description,
                language = opf.language,
                format = BookFormat.EPUB,
                coverFile = coverFile,
                chapters = writer.metas,
                chaptersDir = File(workDir, "chapters"),
                assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true },
                droppedImages = droppedImages
            )
        )
    }

    // -- entry resolution (v1.5.4, WS-7) -------------------------------------

    /** Every extracted file, keyed by its lowercase zip path. A
     * path that exists exactly maps to itself, so an index lookup
     * IS the exact match whenever one exists; the case-insensitive
     * hit is only ever the fallback. Two entries differing only by
     * case collide — the first extracted wins (a pathological book;
     * either choice is as right as the other). */
    private fun buildEntryIndex(unzipped: File): Map<String, String> {
        val index = HashMap<String, String>()
        unzipped.walkTopDown().forEach { file ->
            if (file.isFile) {
                val rel = file.toRelativeString(unzipped).replace('\\', '/')
                index.putIfAbsent(rel.lowercase(), rel)
            }
        }
        return index
    }

    /** The extracted file for [relPath] (a zip-style path), via the
     * case-insensitive [index]; null when no entry matches. */
    private fun entryFile(unzipped: File, index: Map<String, String>, relPath: String): File? {
        val actual = index[relPath.lowercase()] ?: return null
        return File(unzipped, actual).takeIf { it.isFile }
    }

    /** http/data references are never local files (the converter's
     * resolver drops them by design — remote images are not
     * fetched during import). */
    private fun isRemoteRef(src: String): Boolean {
        val clean = src.substringBefore('#').substringBefore('?')
        return clean.startsWith("http", true) || clean.startsWith("data:", true)
    }

    /**
     * Resolves a chapter image reference to the ACTUAL extracted
     * entry path, or null when nothing matches. A leading `/` is
     * the package-root-absolute shape: real readers resolve it
     * against the OPF's directory (the package root), so that
     * reading is tried first and the zip root second — the old
     * code knew only the zip root and lost every such picture in
     * a book whose package lives in a subfolder.
     */
    private fun resolveImageEntry(
        index: Map<String, String>,
        itemDir: String,
        opfDir: String,
        src: String
    ): String? {
        // v1.5.5 (§5, Amendments 2): EVERY reference is
        // normalised against its own spine item's directory —
        // ZipPaths.resolve performs the URI resolve+normalize
        // (`.`/`..` collapse, percent-decode) to the canonical
        // ZIP entry path, so `../image/i-001.jpg` from
        // `OEBPS/xhtml/p-001.xhtml` looks up
        // `OEBPS/image/i-001.jpg`. Backslash separators (a
        // Windows-authored book) are normalised first.
        val clean = src.substringBefore('#').substringBefore('?')
            .replace('\\', '/')
        if (isRemoteRef(clean)) return null
        if (clean.startsWith("/")) {
            index[
                ZipPaths.resolve(opfDir, clean.trimStart('/')).lowercase()
            ]?.let { return it }
            return index[ZipPaths.resolve("", clean).lowercase()]
        }
        return index[ZipPaths.resolve(itemDir, clean).lowercase()]
    }

    /**
     * v1.5.5 (§5): does this XHTML carry image markup of ANY
     * shape (an <img>/<image> tag, a srcset, a CSS url())? The
     * F7 skip's honesty guard: an item that converts to zero
     * blocks while carrying such markup lost a picture, however
     * exotic the construct that carried it.
     */
    private fun carriesImageMarkup(page: File): Boolean {
        val text = runCatching { page.readText() }.getOrNull() ?: return false
        return text.contains("<img", ignoreCase = true) ||
            text.contains("<image", ignoreCase = true) ||
            text.contains("srcset", ignoreCase = true) ||
            text.contains("url(", ignoreCase = true)
    }

    /** The first `<img>`/`<image>` source referenced by an XHTML
     * page — the guide-cover-page fallback's picture hunt. A light
     * scan is enough: this runs once per book, only when every
     * declared cover path already failed. */
    private fun firstImageSrc(page: File): String? {
        val text = runCatching { page.readText() }.getOrNull() ?: return null
        val tagPattern = Regex("""<(img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)
        val attrPattern = Regex(
            """(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        for (tag in tagPattern.findAll(text)) {
            attrPattern.find(tag.value)?.let { return it.groupValues[1] }
        }
        return null
    }

    // -- DRM ----------------------------------------------------------------

    /** True when encryption.xml protects something that isn't a font. */
    private fun encryptionBlocksContent(file: File): Boolean {
        val text = runCatching { file.readText() }.getOrDefault("")
        val refs = Regex("""CipherReference[^>]*URI="([^"]+)"""").findAll(text)
        if (refs.none()) return false
        return refs.any { match ->
            val uri = match.groupValues[1].lowercase()
            FONT_EXTENSIONS.none { uri.endsWith(it) }
        }
    }

    // -- zip ------------------------------------------------------------------

    /** [ArchiveLimits] apply; the limit parameters exist only so
     * tests can trip the caps with small fixtures. On a size
     * overflow the partial extraction is deleted again — nothing
     * half-unzipped stays behind. */
    internal fun extractZip(
        staged: File,
        destDir: File,
        maxEntries: Int = ArchiveLimits.MAX_ENTRIES,
        maxEntryBytes: Long = ArchiveLimits.MAX_STREAMED_ENTRY_BYTES,
        maxTotalBytes: Long = ArchiveLimits.MAX_TOTAL_EXPANDED_BYTES
    ): AppError? {
        return try {
            destDir.mkdirs()
            val destCanonical = destDir.canonicalPath
            var entries = 0
            val budget = ArchiveBudget(maxTotalBytes)
            ZipInputStream(staged.inputStream().buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entries > maxEntries) {
                        return AppError.BadFile("this EPUB has too many files inside")
                    }
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && !name.startsWith("__MACOSX")) {
                        val out = File(destDir, name)
                        if (!out.canonicalPath.startsWith(destCanonical + File.separator)) {
                            return AppError.BadFile("this EPUB contains an unsafe file path")
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { sink ->
                            var entryBytes = 0L
                            var n = zip.read(buffer)
                            while (n >= 0) {
                                entryBytes += n
                                if (entryBytes > maxEntryBytes) {
                                    throw ArchiveLimitException("this EPUB")
                                }
                                budget.charge(n.toLong(), "this EPUB")
                                sink.write(buffer, 0, n)
                                n = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: ArchiveLimitException) {
            destDir.deleteRecursively()
            AppError.BadFile("this EPUB is too large to open safely")
        } catch (e: ZipException) {
            AppError.BadFile("this file isn't a readable EPUB")
        } catch (e: IOException) {
            AppError.BadFile("the EPUB couldn't be opened")
        } catch (e: Exception) {
            AppError.Unknown(e)
        }
    }

    // -- XML plumbing -----------------------------------------------------------

    private fun newParser(file: File): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(file.inputStream().buffered(), null)
        return parser
    }

    private fun parseContainer(file: File): String? = try {
        val parser = newParser(file)
        var event = parser.eventType
        var result: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                result = parser.getAttributeValue(null, "full-path")
                if (result != null) break
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        null
    }

    internal data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: Set<String>,
        val mediaOverlayId: String?
    )

    internal data class Opf(
        val title: String?,
        val author: String?,
        val description: String?,
        val language: String?,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val tocId: String?,
        val coverHref: String?,
        /** v1.5.4 (WS-7): a `<guide>` cover reference that pointed
         * at an XHTML cover PAGE (resolved to the page's first
         * image at cover-derivation time); null when the guide
         * named an image directly (folded into [coverHref]) or
         * the book has no guide cover. */
        val guideCoverPageHref: String? = null
    )

    private fun parseOpf(file: File): Opf? = try {
        val parser = newParser(file)
        var title: String? = null
        var author: String? = null
        var description: String? = null
        var language: String? = null
        var coverId: String? = null
        var guideHref: String? = null
        var inGuide = false
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        var tocId: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: title
                    "creator" -> author = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: author
                    "description" -> description =
                        parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: description
                    "language" -> language =
                        parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: language
                    "meta" -> {
                        if (parser.getAttributeValue(null, "name") == "cover") {
                            coverId = parser.getAttributeValue(null, "content")
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val type = parser.getAttributeValue(null, "media-type") ?: ""
                        val props = parser.getAttributeValue(null, "properties")
                            ?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                        val overlay = parser.getAttributeValue(null, "media-overlay")
                        if (id != null && href != null) {
                            manifest[id] = ManifestItem(
                                ZipPaths.percentDecode(href), type, props, overlay
                            )
                        }
                    }
                    "spine" -> tocId = parser.getAttributeValue(null, "toc")
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spine += it }
                    // v1.5.4 (WS-7): the guide cover — the third
                    // standard cover declaration, which commercial
                    // EPUBs (the Calibre/Sol Press shape) often use
                    // ALONE. It was never parsed at all, so such a
                    // book's cover was never even looked for.
                    "guide" -> inGuide = true
                    "reference" -> {
                        if (inGuide && guideHref == null &&
                            parser.getAttributeValue(null, "type") == "cover"
                        ) {
                            guideHref = parser.getAttributeValue(null, "href")
                        }
                    }
                }
            }
            if (event == XmlPullParser.END_TAG && parser.name == "guide") {
                inGuide = false
            }
            event = parser.next()
        }
        // Guide cover classification: an image manifest item IS the
        // cover; an XHTML item is a cover PAGE (its first image is
        // the cover — derived in parse(), which can read files); an
        // href matching no manifest item is tried as a direct file
        // reference. Third in the cover precedence, behind the
        // meta name and the cover-image property.
        var guideImageHref: String? = null
        var guidePageHref: String? = null
        val guideRaw = guideHref?.let { ZipPaths.percentDecode(it) }
        if (guideRaw != null) {
            val item = manifest.values.firstOrNull { it.href == guideRaw }
            when {
                item == null -> guideImageHref = guideRaw
                item.mediaType.startsWith("image/") -> guideImageHref = item.href
                item.mediaType == "application/xhtml+xml" -> guidePageHref = item.href
                else -> guideImageHref = item.href
            }
        }
        val coverHref = coverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { "cover-image" in it.properties }?.href
            ?: guideImageHref
        Opf(
            title, author, description, language, manifest, spine, tocId,
            coverHref, guidePageHref
        )
    } catch (e: Exception) {
        null
    }

    private fun parseNcx(file: File): Map<String, String> = try {
        val parser = newParser(file)
        val result = mutableMapOf<String, String>()

        class NavPoint {
            var src: String? = null
            var label: String? = null
            var inLabel = false
        }
        val stack = mutableListOf<NavPoint>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "navPoint" -> stack += NavPoint()
                    "content" -> stack.lastOrNull()?.src = parser.getAttributeValue(null, "src")
                    "navLabel" -> stack.lastOrNull()?.inLabel = true
                    "text" -> {
                        val point = stack.lastOrNull()
                        if (point != null && point.inLabel) {
                            point.label = parser.nextText()?.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "navLabel" -> stack.lastOrNull()?.inLabel = false
                    "navPoint" -> {
                        val point = stack.removeAt(stack.lastIndex)
                        val src = point.src
                        val label = point.label
                        if (src != null && !label.isNullOrEmpty()) {
                            result.putIfAbsent(src.substringBefore('#'), label)
                        }
                    }
                }
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    private fun parseNav(file: File): Map<String, String> = try {
        val parser = newParser(file)
        val result = mutableMapOf<String, String>()
        var navDepth = -1
        var depth = 0
        var currentHref: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "nav" && navDepth < 0) navDepth = depth
                    if (navDepth >= 0 && parser.name == "a") {
                        currentHref = parser.getAttributeValue(null, "href")
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (currentHref != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "a" && currentHref != null) {
                        val label = text.toString().replace(Regex("\\s+"), " ").trim()
                        if (label.isNotEmpty()) {
                            result.putIfAbsent(currentHref!!.substringBefore('#'), label)
                        }
                        currentHref = null
                    }
                    if (parser.name == "nav" && depth == navDepth) navDepth = -1
                    depth--
                }
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    companion object {
        private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".woff", ".woff2")
    }
}

/**
 * SMIL (Media Overlay) parsing: `<par>` pairs of `<text src="doc#id">`
 * and `<audio src="a.mp3" clipBegin clipEnd>`, in document order.
 * Split out so the clock-value grammar is directly testable.
 */
object SmilParser {

    data class SmilClip(
        /** Text document href as written in the SMIL (no fragment). */
        val textHref: String,
        val textId: String,
        val audioHref: String,
        val beginSec: Double,
        val endSec: Double
    )

    data class Smil(val clips: List<SmilClip>)

    fun parse(file: File): Smil? = try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(file.inputStream().buffered(), null)

        val clips = mutableListOf<SmilClip>()
        var pendingTextSrc: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "text" -> pendingTextSrc = parser.getAttributeValue(null, "src")
                    "audio" -> {
                        val textSrc = pendingTextSrc
                        val audioSrc = parser.getAttributeValue(null, "src")
                        val begin = parseClock(parser.getAttributeValue(null, "clipBegin"))
                        val end = parseClock(parser.getAttributeValue(null, "clipEnd"))
                        if (textSrc != null && audioSrc != null && begin != null && end != null) {
                            clips += SmilClip(
                                textHref = textSrc.substringBefore('#'),
                                textId = textSrc.substringAfter('#', ""),
                                audioHref = audioSrc,
                                beginSec = begin,
                                endSec = end
                            )
                        }
                        pendingTextSrc = null
                    }
                }
            }
            event = parser.next()
        }
        Smil(clips)
    } catch (e: Exception) {
        null
    }

    /**
     * SMIL clock values: timecount ("12.5s", "3min", "1h", "500ms"),
     * partial clock ("02:30.5"), full clock ("01:02:30.25").
     */
    fun parseClock(raw: String?): Double? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            when {
                value.endsWith("ms") -> value.dropLast(2).toDouble() / 1000.0
                value.endsWith("s") -> value.dropLast(1).toDouble()
                value.endsWith("min") -> value.dropLast(3).toDouble() * 60.0
                value.endsWith("h") -> value.dropLast(1).toDouble() * 3600.0
                ':' in value -> {
                    val parts = value.split(":").map { it.toDouble() }
                    when (parts.size) {
                        2 -> parts[0] * 60 + parts[1]
                        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                        else -> null
                    }
                }
                else -> value.toDouble()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
