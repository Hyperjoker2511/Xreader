package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.MediaClip
import app.xreader.domain.content.MediaOverlay
import app.xreader.domain.reader.ChapterText
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
    val chapterTitles: List<String>,
    /** v1.7 OE-2 (R4): the OPF's own fixed-layout flag —
     * `rendition:layout = pre-paginated` in the package
     * metadata. Read from the file itself; no engine session
     * is consulted for it any more. */
    val isFixedLayout: Boolean = false,
    /** v1.7 OE-2 (R4): the OPF spine's
     * `page-progression-direction = "rtl"`, from the file
     * itself, as above. */
    val isRtlProgression: Boolean = false,
    /** v1.7 OE-2 (R5): every spine item's href, resolved
     * OPF-relative in spine order — the reading-order href
     * list the EPUB prepare bridge matches BY HREF (through
     * EpubHrefBridge's canonicalisation, never by raw
     * index). The space the retired session's reading-order
     * list used to supply. */
    val spineHrefs: List<String> = emptyList()
)

/**
 * v1.7 Moon+ standard fix (Part 2): one entry of the book's
 * own table of contents, in document order: the label as
 * the book prints it, the target's path (resolved against
 * the TOC file's own directory, with the fragment split
 * off), and the fragment (the in-file anchor id) when the
 * entry carries one.
 */
data class EpubTocEntry(
    val label: String,
    val href: String,
    val fragment: String?
)

/**
 * Part 2: [EpubParser.tocMap]'s yield — the ordered TOC
 * entries plus every spine item's href (resolved
 * OPF-relative) in spine order. The reader bridges entries
 * to landed chapters by matching hrefs against this list:
 * chapter rows store no href (Room stays v12), so the
 * spine is the only shared index space.
 *
 * Fix Round G (Part A1): [hasTocSource] records whether
 * the book's package DECLARES a TOC source at all (an NCX
 * manifest item via the spine's toc id, or a nav document)
 * — the reader's tri-state bridge may only conclude "this
 * book has no TOC" when no source is declared; a declared
 * source that yields nothing is a failure, never a
 * cacheable absence.
 */
data class TocMapResult(
    val entries: List<EpubTocEntry>,
    val spineHrefs: List<String>,
    val hasTocSource: Boolean
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
        locateNcx(unzipped, entryIndex, opf, opfDir)?.let { (ncxFile, ncxPath) ->
            val ncxDir = ncxPath.substringBeforeLast('/', "")
            titles.putAll(
                parseNcx(ncxFile).mapKeys { (src, _) -> ZipPaths.resolve(ncxDir, src) }
            )
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
                chapterTitles = chapterTitles,
                isFixedLayout = opf.isFixedLayout,
                isRtlProgression = opf.isRtlProgression,
                spineHrefs = opf.spine.mapNotNull { idref ->
                    opf.manifest[idref]?.let { item ->
                        ZipPaths.resolve(opfDir, item.href)
                    }
                }
            )
        )
    }

    /**
     * Part 2: the book's own table of contents — ONE
     * probe-shaped pass (unzip, container → OPF, TOC source)
     * returning the ordered entries WITH fragments plus the
     * spine href list, for the reader's open-time bridge to
     * landed chapters. NCX-first precedence, exactly as the
     * title harvest above; the entry parses (below) emit
     * document order where [parseNcx]/[parseNav] collapse to
     * a fragment-stripped map. No chapter is converted.
     * Null on any failure — a book whose TOC can't be read
     * simply has no TOC furniture; it is never an open
     * failure. Fix Round G: an empty entry parse falls back
     * to harvest-derived entries (A2), and the result
     * declares whether a TOC source exists at all (A1).
     */
    fun tocMap(staged: File, workDir: File): TocMapResult? = try {
        val unzipped = File(workDir, "unzipped-toc")
        if (extractZip(staged, unzipped) != null) {
            null
        } else {
            val container = File(unzipped, "META-INF/container.xml")
            val opfPath = if (container.isFile) {
                parseContainer(container)
            } else {
                null
            }
            val entryIndex = buildEntryIndex(unzipped)
            val opf = opfPath?.let {
                entryFile(unzipped, entryIndex, it)
            }?.let { parseOpf(it) }
            if (opf == null || opfPath == null) {
                null
            } else {
                val opfDir = opfPath.substringBeforeLast('/', "")
                val spineHrefs = opf.spine.mapNotNull { idref ->
                    opf.manifest[idref]?.let { item ->
                        ZipPaths.resolve(opfDir, item.href)
                    }
                }
                val parsed = tocEntries(
                    unzipped, entryIndex, opf, opfDir
                )
                TocMapResult(
                    entries = if (parsed.isNotEmpty()) {
                        parsed
                    } else {
                        // Fix Round G (Part A2): the entry
                        // parse came back empty — before the
                        // book may be called TOC-less, derive
                        // the entries from the title harvest
                        // (the proven road: the same walk
                        // parse() titles chapters by). Spine
                        // order, harvested labels, no
                        // fragments. A working entry parse is
                        // never overridden by this fallback.
                        harvestEntries(
                            unzipped, entryIndex, opf, opfDir,
                            spineHrefs
                        )
                    },
                    spineHrefs = spineHrefs,
                    hasTocSource =
                        locateNcx(unzipped, entryIndex, opf, opfDir) != null ||
                            opf.manifest.values.any {
                                "nav" in it.properties
                            }
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Part 2: the TOC source walk. Micro-build M (the
     * Moon+ discovery rule, REA 2026-11-20): the NCX is
     * located by [locateNcx] — the spine's declared toc is
     * only one route; the archive listing itself is scanned
     * for a `.ncx` file — and when the book carries BOTH an
     * NCX and a nav document, both are parsed and the
     * LONGER list is the book's Contents (a tie keeps the
     * NCX, the older precedence). This replaces the shipped
     * rule that a declared-but-broken NCX settled the
     * matter with no nav rescue: that rule is what left
     * real books showing their raw spine sections.
     * Hrefs resolve against the TOC file's directory —
     * ZipPaths.resolve strips the fragment itself, and the
     * fragment is read off the raw source string, so
     * resolution happens BEFORE the split in effect. */
    private fun tocEntries(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String
    ): List<EpubTocEntry> {
        val ncxEntries =
            locateNcx(unzipped, entryIndex, opf, opfDir)
                ?.let { (ncxFile, ncxPath) ->
                    val ncxDir = ncxPath.substringBeforeLast('/', "")
                    parseNcxEntries(ncxFile).map { (label, src) ->
                        EpubTocEntry(
                            label = label,
                            href = ZipPaths.resolve(ncxDir, src),
                            fragment = src.substringAfter('#', "")
                                .ifEmpty { null }
                        )
                    }
                } ?: emptyList()
        val navEntries = navTocEntries(unzipped, entryIndex, opf, opfDir)
        return if (navEntries.size > ncxEntries.size) {
            navEntries
        } else {
            ncxEntries
        }
    }

    /** The nav document's TOC entries, or an empty list
     * when the book declares no nav (or it can't be read). */
    private fun navTocEntries(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String
    ): List<EpubTocEntry> {
        val navItem = opf.manifest.values
            .firstOrNull { "nav" in it.properties }
            ?: return emptyList()
        val navPath = ZipPaths.resolve(opfDir, navItem.href)
        val navFile = entryFile(unzipped, entryIndex, navPath)
            ?: return emptyList()
        val navDir = navPath.substringBeforeLast('/', "")
        return parseNavEntries(navFile).map { (label, href) ->
            EpubTocEntry(
                label = label,
                href = ZipPaths.resolve(navDir, href),
                fragment = href.substringAfter('#', "")
                    .ifEmpty { null }
            )
        }
    }

    /**
     * Fix Round G (Part A2): the title harvest, exactly as
     * [parse] and [probe] run it — NCX titles resolved
     * against the NCX's directory (putAll: the NCX wins),
     * then nav titles for the hrefs the NCX didn't name
     * (putIfAbsent). Keys are fragment-stripped resolved
     * hrefs; values are the book's own labels.
     */
    private fun titleHarvest(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String
    ): Map<String, String> {
        val titles = mutableMapOf<String, String>()
        locateNcx(unzipped, entryIndex, opf, opfDir)?.let { (ncxFile, ncxPath) ->
            val ncxDir = ncxPath.substringBeforeLast('/', "")
            titles.putAll(
                parseNcx(ncxFile).mapKeys { (src, _) ->
                    ZipPaths.resolve(ncxDir, src)
                }
            )
        }
        opf.manifest.values.firstOrNull { "nav" in it.properties }
            ?.let { navItem ->
                val navPath = ZipPaths.resolve(opfDir, navItem.href)
                val navFile = entryFile(unzipped, entryIndex, navPath)
                if (navFile != null) {
                    val navDir = navPath.substringBeforeLast('/', "")
                    for ((href, label) in parseNav(navFile)) {
                        titles.putIfAbsent(
                            ZipPaths.resolve(navDir, href), label
                        )
                    }
                }
            }
        return titles
    }

    /**
     * Fix Round G (Part A2): TOC entries derived from the
     * title harvest — the harvested hrefs that appear in
     * the spine, in SPINE order, each with its harvested
     * label and no fragment (the harvest's keys are
     * fragment-stripped, so a derived entry opens its
     * chapter's top, exactly as an entry without an anchor
     * does on the primary road).
     */
    private fun harvestEntries(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String,
        spineHrefs: List<String>
    ): List<EpubTocEntry> {
        val titles = titleHarvest(unzipped, entryIndex, opf, opfDir)
        if (titles.isEmpty()) return emptyList()
        return spineHrefs.mapNotNull { href ->
            titles[href]?.let { label ->
                EpubTocEntry(label = label, href = href, fragment = null)
            }
        }
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
        locateNcx(unzipped, entryIndex, opf, opfDir)?.let { (ncxFile, ncxPath) ->
            val ncxDir = ncxPath.substringBeforeLast('/', "")
            titles.putAll(
                parseNcx(ncxFile).mapKeys { (src, _) -> ZipPaths.resolve(ncxDir, src) }
            )
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
        // v1.7 Patch 3 (Item 3a): the spine href of every
        // EMITTED chapter, parallel to writer.metas — recorded
        // at exactly the writer.add sites, so skipped items
        // (F7) and unlandable plates never appear here. This
        // is the parser-space half of the place bridge.
        val chapterHrefs = mutableListOf<String>()
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
        // v1.7 Moon Reader update (patch 1, A4): the emitted
        // chapter index per spine item, or -1 where the item
        // emitted nothing (the F7 skip, an unlandable plate).
        // Segmentation groups EMITTED chapters, never raw
        // spine items, so this is recorded at the writer.add
        // sites exactly as chapterHrefs is.
        val spineEmission = IntArray(spineItems.size) { -1 }
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
                    chapterHrefs += relPath
                    spineEmission[index] = writer.metas.lastIndex
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
                chapterHrefs += relPath
                spineEmission[index] = writer.metas.lastIndex
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
                    chapterHrefs += relPath
                    spineEmission[index] = writer.metas.lastIndex
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

        // Patch 1: segmentation — the book's own TOC anchors
        // become the chapters (spine files group under the
        // anchor that starts them). A post-emission merge
        // over the staged chapter files (A4); any failure in
        // the pass leaves the emitted chapters untouched —
        // segmentation never makes a book less readable.
        val segmented = segmentByToc(
            unzipped = unzipped,
            entryIndex = entryIndex,
            opf = opf,
            opfDir = opfDir,
            workDir = workDir,
            oldMetas = writer.metas,
            oldHrefs = chapterHrefs,
            spineHrefs = spineItems.map { it.second },
            spineEmission = spineEmission
        )

        return AppResult.Ok(
            ParsedBook(
                title = opf.title,
                author = opf.author,
                description = opf.description,
                language = opf.language,
                format = BookFormat.EPUB,
                coverFile = coverFile,
                chapters = segmented.metas,
                chaptersDir = segmented.chaptersDir,
                assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true },
                droppedImages = droppedImages,
                chapterHrefs = segmented.hrefs,
                chapterMerge = segmented.merge,
                preSegmentChapterHrefs = segmented.preSegmentHrefs,
                droppedOverlays = segmented.droppedOverlays
            )
        )
    }

    /** Patch 1: [segmentByToc]'s yield. When the book has
     * no usable TOC the emission passes through untouched
     * and [merge]/[preSegmentHrefs] stay empty — the
     * signal every consumer keys on. */
    private data class SegmentationResult(
        val metas: List<ParsedChapterMeta>,
        val chaptersDir: File,
        val hrefs: List<String>,
        val merge: List<ChapterMergeSpan>,
        val preSegmentHrefs: List<String>,
        val droppedOverlays: Int
    )

    /** One contiguous slice of a pre-merge chapter inside
     * one merged chapter: blocks [fromBlock, toBlock). */
    private data class SegmentSlice(
        val oldIndex: Int,
        val fromBlock: Int,
        val toBlock: Int
    )

    /**
     * Patch 1 (spec §1/§2, amendments A3–A6): group the
     * EMITTED chapters under the book's own TOC anchors.
     *
     * The anchor list is [tocEntries] — the same ordered,
     * longer-wins NCX/nav resolution Micro-build M built
     * (A4); parse()'s href→title harvest is NOT an ordered
     * TOC and is never used here. Each anchor starts a
     * chapter at the emitted chapter its href names — or,
     * when that spine item emitted nothing (A5), at the
     * next emitted item after it. A fragment anchor splits
     * its file at the block carrying the fragment id when
     * one resolves (the converter's ids, A5); an anchor
     * resolving to nothing starts no split — anchors
     * sharing one file merge into one chapter named by
     * the first anchor, with or without fragments (A5).
     * Files before the first anchor form one front-matter
     * chapter. Null (the caller keeps the raw emission)
     * when the book declares no entries or none of them
     * lands on an emitted chapter.
     *
     * The merge itself is a post-emission pass over the
     * staged `ch-*.json` files (A4): one group is decoded,
     * concatenated and re-encoded at a time — a whole book
     * is never held. Metas are regenerated by the writer
     * (title = the anchor's label verbatim, wordCount =
     * the merged content's own count, i.e. the sum).
     * Offsets are ChapterText coordinates (A3): each
     * slice's charPrefix accounts the 2-char separator at
     * every seam between absorbed files.
     *
     * TOC-tail fix (2026-11-22): three silent losses are
     * closed in [segmentByTocOrNull] — an anchor whose
     * forward walk runs off the end of the book still
     * opens its own chapter, split at the last emitted
     * chapter's end; the split dedup keys on the anchor's
     * own spine position, so distinct TOC entries landing
     * on one emitted chapter keep distinct chapters; and
     * an anchor whose href meets no spine file falls back
     * through the case-insensitive entry index, and is
     * counted and logged when it still resolves nowhere.
     * Moon+ rule: Contents is the book's own TOC, all
     * of it.
     */
    private fun segmentByToc(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String,
        workDir: File,
        oldMetas: List<ParsedChapterMeta>,
        oldHrefs: List<String>,
        spineHrefs: List<String>,
        spineEmission: IntArray
    ): SegmentationResult {
        val untouched = SegmentationResult(
            metas = oldMetas,
            chaptersDir = File(workDir, "chapters"),
            hrefs = oldHrefs.toList(),
            merge = emptyList(),
            preSegmentHrefs = emptyList(),
            droppedOverlays = 0
        )
        return runCatching {
            segmentByTocOrNull(
                unzipped, entryIndex, opf, opfDir, workDir,
                oldMetas, oldHrefs, spineHrefs, spineEmission
            )
        }.getOrNull() ?: untouched
    }

    private fun segmentByTocOrNull(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String,
        workDir: File,
        oldMetas: List<ParsedChapterMeta>,
        oldHrefs: List<String>,
        spineHrefs: List<String>,
        spineEmission: IntArray
    ): SegmentationResult? {
        val entries = tocEntries(unzipped, entryIndex, opf, opfDir)
        if (entries.isEmpty()) return null

        val oldDir = File(workDir, "chapters")
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }
        fun decodeOld(index: Int): ChapterContent? = runCatching {
            json.decodeChapter(
                File(oldDir, oldMetas[index].contentFileName).readText()
            )
        }.getOrNull()

        // The first spine position of each href — anchor
        // hrefs and spine hrefs come out of the same
        // ZipPaths.resolve pipeline, so they meet exactly.
        // (The anchor lookup below still carries the
        // case-insensitive entry-index fallback for the
        // books where they don't.)
        val spinePosByHref = HashMap<String, Int>()
        val spinePosByLowerHref = HashMap<String, Int>()
        spineHrefs.forEachIndexed { pos, href ->
            spinePosByHref.putIfAbsent(href, pos)
            spinePosByLowerHref.putIfAbsent(href.lowercase(), pos)
        }

        // Split points, deduped in TOC order, then ordered
        // by position. The dedup key carries the anchor's
        // own spine position (TOC-tail fix, 2026-11-22):
        // two distinct TOC entries at distinct spine
        // positions keep distinct chapters even when both
        // resolve to the same emitted chapter at the same
        // block — the old (target, blockIndex) key silently
        // collapsed the second entry into the first one's
        // chapter. True duplicates (same spine position,
        // same landing point) still dedupe, as before.
        data class Split(
            val oldIndex: Int,
            val blockIndex: Int,
            val label: String,
            val spinePos: Int
        )

        val seen = HashSet<Triple<Int, Int, Int>>()
        val splits = ArrayList<Split>()
        var unresolvedAnchors = 0
        val unresolvedLabels = ArrayList<String>()
        for (entry in entries) {
            // TOC-tail fix (S1): an anchor whose href meets
            // no spine href exactly falls back through the
            // case-insensitive machinery the rest of the
            // parser resolves by — the entry index's actual
            // path first, then the spine map keyed by
            // lowercase on both sides. A letter-case
            // divergence between the TOC's spelling and the
            // spine's must not silently cost the entry its
            // chapter. An anchor that still resolves
            // nowhere is counted and logged after the loop,
            // never a bare skip.
            val spinePos = spinePosByHref[entry.href]
                ?: entryIndex[entry.href.lowercase()]
                    ?.let { actual -> spinePosByHref[actual] }
                ?: spinePosByLowerHref[entry.href.lowercase()]
                ?: run {
                    unresolvedAnchors++
                    unresolvedLabels += entry.label
                    continue
                }
            var target = -1
            var pos = spinePos
            while (pos < spineEmission.size) {
                if (spineEmission[pos] >= 0) {
                    target = spineEmission[pos]
                    break
                }
                pos++
            }
            val blockIndex: Int
            if (target >= 0) {
                blockIndex = if (entry.fragment != null) {
                    val found = decodeOld(target)?.blocks
                        ?.indexOfFirst { block ->
                            when (block) {
                                is ChapterBlock.Heading ->
                                    block.id == entry.fragment
                                is ChapterBlock.Paragraph ->
                                    block.id == entry.fragment
                                else -> false
                            }
                        } ?: -1
                    if (found > 0) found else 0
                } else {
                    0
                }
            } else {
                // TOC-tail fix (S2): the anchor's own spine
                // item — and everything after it — emitted
                // nothing (the F7 skip: a clean zero-block
                // conversion, classically an image-only
                // final page), so the forward walk ran off
                // the end of the book and the old code
                // dropped the anchor whole. Moon+ shape:
                // Contents is the book's own TOC, all of
                // it — the anchor still opens its own
                // chapter, split at the END of the last
                // emitted chapter. The chapter is
                // legitimately empty of blocks when nothing
                // emitted remains for it; its title row is
                // the entry's presence in Contents, and the
                // merge below already writes zero-block
                // groups like any other.
                if (oldMetas.isEmpty()) continue
                target = oldMetas.lastIndex
                blockIndex = decodeOld(target)?.blocks?.size
                    ?: Int.MAX_VALUE
            }
            if (seen.add(Triple(spinePos, target, blockIndex))) {
                splits += Split(target, blockIndex, entry.label, spinePos)
            }
        }
        if (unresolvedAnchors > 0) {
            android.util.Log.w(
                "EpubParser",
                "segmentation: $unresolvedAnchors TOC anchor(s) named " +
                    "no spine file and open no chapter: " +
                    unresolvedLabels.joinToString("; ")
            )
        }
        if (splits.isEmpty()) return null
        splits.sortWith(
            compareBy({ it.oldIndex }, { it.blockIndex }, { it.spinePos })
        )

        // Groups: the front matter before the first split,
        // then one group per split reaching to the next.
        val groups = ArrayList<Pair<String?, List<SegmentSlice>>>()
        val first = splits.first()
        val front = ArrayList<SegmentSlice>()
        for (oi in 0 until first.oldIndex) {
            front += SegmentSlice(oi, 0, Int.MAX_VALUE)
        }
        if (first.blockIndex > 0) {
            front += SegmentSlice(first.oldIndex, 0, first.blockIndex)
        }
        if (front.isNotEmpty()) groups += (null to front)
        splits.forEachIndexed { i, split ->
            val next = splits.getOrNull(i + 1)
            val slices = ArrayList<SegmentSlice>()
            val endBlock = if (
                next != null && next.oldIndex == split.oldIndex
            ) {
                next.blockIndex
            } else {
                Int.MAX_VALUE
            }
            slices += SegmentSlice(split.oldIndex, split.blockIndex, endBlock)
            if (next == null) {
                for (oi in split.oldIndex + 1 until oldMetas.size) {
                    slices += SegmentSlice(oi, 0, Int.MAX_VALUE)
                }
            } else if (next.oldIndex > split.oldIndex) {
                for (oi in split.oldIndex + 1 until next.oldIndex) {
                    slices += SegmentSlice(oi, 0, Int.MAX_VALUE)
                }
                if (next.blockIndex > 0) {
                    slices += SegmentSlice(next.oldIndex, 0, next.blockIndex)
                }
            }
            groups += (split.label to slices)
        }

        // The merge: one group at a time over the staged
        // files, spans recorded in old-chapter space (A2).
        val mergedWriter = ChapterFileWriter(File(workDir, "chapters-segmented"))
        val merge = ArrayList<ChapterMergeSpan>()
        val newHrefs = ArrayList<String>()
        var droppedOverlays = 0
        groups.forEachIndexed { newIndex, (label, slices) ->
            val blocks = ArrayList<ChapterBlock>()
            var overlay: MediaOverlay? = null
            var mergedTextLen = 0
            var mergedBlocks = 0
            for (slice in slices) {
                // Fix round 1: a staged file that fails to
                // decode ABORTS the whole pass — the throw
                // lands in segmentByToc's runCatching, which
                // returns the raw unsegmented emission (the
                // spec's contract: any failure → raw
                // emission). Substituting an empty chapter
                // here would silently drop this slice's
                // text from the merged book.
                val content = decodeOld(slice.oldIndex)
                    ?: throw IllegalStateException(
                        "segmentation: staged chapter " +
                            "${slice.oldIndex} failed to decode"
                    )
                val all = content.blocks
                val from = slice.fromBlock.coerceIn(0, all.size)
                val to = slice.toBlock.coerceIn(from, all.size)
                val part = all.subList(from, to)
                val oldText = ChapterText(content)
                val startOffset = if (from > 0) {
                    oldText.startOf(from)
                } else {
                    0
                }
                val sliceTextLen = when {
                    part.isEmpty() -> 0
                    from == 0 && to == all.size -> oldText.length
                    from == 0 ->
                        (oldText.startOf(to) - 2).coerceAtLeast(0)
                    to == all.size -> oldText.length - startOffset
                    else -> (
                        oldText.startOf(to) - 2 - startOffset
                        ).coerceAtLeast(0)
                }
                // Fix round 1: a zero-block slice carries
                // no text, so no separator precedes IT —
                // in ChapterText's join accounting the
                // "\n\n" exists only where a block actually
                // follows one, and it is charged to the
                // next slice that adds a block (mergedTextLen
                // does not advance here, so that slice still
                // gets exactly one +2). This slice's prefix
                // is the seam itself — the end of the text
                // so far — not 2 past it into the following
                // text. (mergedTextLen is 0 while
                // mergedBlocks is 0, so the first-slice case
                // is covered by the same branch.)
                val charPrefix = if (part.isEmpty()) {
                    mergedTextLen
                } else if (mergedBlocks > 0) {
                    mergedTextLen + 2
                } else {
                    0
                }
                merge += ChapterMergeSpan(
                    oldChapterIndex = slice.oldIndex,
                    oldStartOffset = startOffset,
                    newChapterIndex = newIndex,
                    charPrefix = charPrefix
                )
                if (part.isNotEmpty()) {
                    mergedTextLen = charPrefix + sliceTextLen
                    mergedBlocks += part.size
                    blocks += part
                }
                // A6: one overlay slot per merged chapter —
                // clips merge when the audio file is the
                // same; a different audio cannot ride along
                // and is counted, never silent.
                val partOverlay = content.mediaOverlay
                if (partOverlay != null) {
                    val current = overlay
                    when {
                        current == null -> overlay = partOverlay
                        current.audioFileName ==
                            partOverlay.audioFileName ->
                            overlay = current.copy(
                                clips = current.clips + partOverlay.clips
                            )
                        else -> droppedOverlays++
                    }
                }
            }
            val title = label?.takeIf { it.isNotBlank() }
                ?: oldMetas[slices.first().oldIndex].title
            mergedWriter.add(
                title,
                ChapterContent(blocks = blocks, mediaOverlay = overlay)
            )
            newHrefs += oldHrefs[slices.first().oldIndex]
        }

        return SegmentationResult(
            metas = mergedWriter.metas,
            chaptersDir = File(workDir, "chapters-segmented"),
            hrefs = newHrefs,
            merge = merge,
            preSegmentHrefs = oldHrefs.toList(),
            droppedOverlays = droppedOverlays
        )
    }

    // -- entry resolution (v1.5.4, WS-7) -------------------------------------

    /** Every extracted file, keyed by its lowercase zip path. A
     * path that exists exactly maps to itself, so an index lookup
     * IS the exact match whenever one exists; the case-insensitive
     * hit is only ever the fallback. Two entries differing only by
     * case collide — the first extracted wins (a pathological book;
     * either choice is as right as the other). */
    /**
     * Micro-build M: finds the book's NCX file — the pair is
     * the extracted file and its zip-style path (hrefs in
     * the NCX resolve against the path's directory). The
     * discovery is Moon+'s (REA 2026-11-20): the spine's
     * declared `toc` id is tried FIRST (the well-formed
     * road, unchanged for healthy books), then the archive
     * listing itself is scanned for a `.ncx` file — an
     * exact `toc.ncx` name wins, then any folder's
     * `toc.ncx`, then any `.ncx` at all. The shipped code
     * knew only the declared route, so a book whose spine
     * omits or misnames the toc reference silently lost its
     * own table of contents and fell back to spine sections.
     */
    private fun locateNcx(
        unzipped: File,
        entryIndex: Map<String, String>,
        opf: Opf,
        opfDir: String
    ): Pair<File, String>? {
        opf.tocId?.let { tocId ->
            opf.manifest[tocId]?.let { tocItem ->
                val ncxPath = ZipPaths.resolve(opfDir, tocItem.href)
                entryFile(unzipped, entryIndex, ncxPath)?.let { file ->
                    return file to ncxPath
                }
            }
        }
        val candidates = entryIndex.values
            .filter { it.endsWith(".ncx", ignoreCase = true) }
            .sorted()
        val scanned = candidates.firstOrNull { it.lowercase() == "toc.ncx" }
            ?: candidates.firstOrNull {
                it.lowercase().endsWith("/toc.ncx")
            }
            ?: candidates.firstOrNull()
        return scanned?.let { path ->
            entryFile(unzipped, entryIndex, path)?.let { file ->
                file to path
            }
        }
    }

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
        val guideCoverPageHref: String? = null,
        /** v1.7 OE-2 (R4): the package metadata declared
         * `rendition:layout = pre-paginated` — the EPUB 3
         * fixed-layout signal. */
        val isFixedLayout: Boolean = false,
        /** v1.7 OE-2 (R4): the spine declared
         * `page-progression-direction = "rtl"`. */
        val isRtlProgression: Boolean = false
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
        var fixedLayout = false
        var spinePageProgression: String? = null
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
                        // v1.7 OE-2 (R4): the EPUB 3 rendition
                        // signal — <meta property="rendition:layout">
                        // pre-paginated</meta> (a content-attribute
                        // spelling of the same property is read
                        // too; it is the same signal, not another).
                        if (parser.getAttributeValue(null, "property") ==
                            "rendition:layout"
                        ) {
                            val declared = parser
                                .getAttributeValue(null, "content")
                                ?: parser.nextText()
                            if (declared?.trim() == "pre-paginated") {
                                fixedLayout = true
                            }
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
                    "spine" -> {
                        tocId = parser.getAttributeValue(null, "toc")
                        // v1.7 OE-2 (R4): the spine's declared
                        // reading progression.
                        spinePageProgression = parser.getAttributeValue(
                            null, "page-progression-direction"
                        )
                    }
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
            coverHref, guidePageHref,
            isFixedLayout = fixedLayout,
            isRtlProgression = spinePageProgression?.trim()
                ?.equals("rtl", ignoreCase = true) == true
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

    /**
     * Part 2 (A4): the NCX as an ordered entry list —
     * PRE-ORDER, where [parseNcx] inserts at END_TAG and so
     * emits children before their parent into its map. A
     * navPoint's document order is navLabel, then content,
     * then child navPoints, so an entry is emitted the
     * moment both its label and its content src are known —
     * at the content tag in the well-formed shape — which
     * places every parent before its children. Labels get
     * [parseNcx]'s cleaning (trimmed); srcs stay RAW
     * (fragment kept, duplicates kept — an entry list is
     * not a map); playOrder is never read, as today.
     */
    private fun parseNcxEntries(
        file: File
    ): List<Pair<String, String>> = try {
        val parser = newParser(file)
        val result = mutableListOf<Pair<String, String>>()

        class NavPoint {
            var src: String? = null
            var label: String? = null
            var inLabel = false
            var emitted = false
        }
        val stack = mutableListOf<NavPoint>()
        fun emit(point: NavPoint) {
            val src = point.src
            val label = point.label
            if (!point.emitted && src != null &&
                !label.isNullOrEmpty()
            ) {
                result += label to src
                point.emitted = true
            }
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "navPoint" -> stack += NavPoint()
                    "content" -> {
                        val point = stack.lastOrNull()
                        if (point != null) {
                            point.src =
                                parser.getAttributeValue(null, "src")
                            emit(point)
                        }
                    }
                    "navLabel" -> stack.lastOrNull()?.inLabel = true
                    "text" -> {
                        val point = stack.lastOrNull()
                        if (point != null && point.inLabel) {
                            point.label = parser.nextText()?.trim()
                            emit(point)
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "navLabel" -> stack.lastOrNull()?.inLabel = false
                    "navPoint" -> {
                        val point = stack.removeAt(stack.lastIndex)
                        emit(point)
                    }
                }
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Part 2 (A5): the EPUB 3 nav document as an ordered
     * entry list, preferring the <nav> whose epub:type
     * contains "toc" — [parseNav] takes the first <nav>
     * regardless of type, which an entry list cannot
     * survive (a landmarks nav listed first would become
     * the book's Contents). Falls back to the first nav
     * when none is typed. Labels get [parseNav]'s cleaning
     * (whitespace collapsed, trimmed); hrefs stay RAW
     * (fragment kept, duplicates kept).
     */
    private fun parseNavEntries(
        file: File
    ): List<Pair<String, String>> = try {
        val parser = newParser(file)
        // One entry list per <nav>, in document order, each
        // with its nav's epub:type ("" when untyped).
        val navs = mutableListOf<
            Pair<String, MutableList<Pair<String, String>>>
            >()
        var depth = 0
        var currentNav: MutableList<Pair<String, String>>? = null
        var currentNavDepth = -1
        var currentHref: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "nav" && currentNav == null) {
                        val type = (0 until parser.attributeCount)
                            .firstOrNull {
                                parser.getAttributeName(it) == "type"
                            }
                            ?.let { parser.getAttributeValue(it) }
                            ?: ""
                        val list = mutableListOf<Pair<String, String>>()
                        navs += type to list
                        currentNav = list
                        currentNavDepth = depth
                    }
                    if (currentNav != null && parser.name == "a") {
                        currentHref =
                            parser.getAttributeValue(null, "href")
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (currentHref != null) {
                    text.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "a" && currentHref != null) {
                        val label = text.toString()
                            .replace(Regex("\\s+"), " ").trim()
                        if (label.isNotEmpty()) {
                            currentNav?.add(label to currentHref!!)
                        }
                        currentHref = null
                    }
                    if (parser.name == "nav" &&
                        depth == currentNavDepth
                    ) {
                        currentNav = null
                        currentNavDepth = -1
                    }
                    depth--
                }
            }
            event = parser.next()
        }
        val chosen = navs.firstOrNull { (type, _) ->
            type.contains("toc")
        } ?: navs.firstOrNull()
        chosen?.second ?: emptyList()
    } catch (e: Exception) {
        emptyList()
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
