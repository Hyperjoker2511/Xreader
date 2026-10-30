package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.MediaClip
import app.xreader.domain.content.MediaOverlay
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

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
class EpubParser : BookParser {

    override val format: BookFormat = BookFormat.EPUB

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
        val opfPath = parseContainer(container)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's contents list couldn't be read"))
        val opfFile = File(unzipped, opfPath)
        if (!opfFile.isFile) {
            return AppResult.Err(AppError.BadFile("this EPUB is missing its book file"))
        }
        val opf = parseOpf(opfFile)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's book file couldn't be read"))

        val opfDir = opfPath.substringBeforeLast('/', "")
        val titles = mutableMapOf<String, String>()
        opf.tocId?.let { tocId ->
            opf.manifest[tocId]?.let { tocItem ->
                val ncxPath = ZipPaths.resolve(opfDir, tocItem.href)
                val ncxFile = File(unzipped, ncxPath)
                if (ncxFile.isFile) {
                    val ncxDir = ncxPath.substringBeforeLast('/', "")
                    titles.putAll(
                        parseNcx(ncxFile).mapKeys { (src, _) -> ZipPaths.resolve(ncxDir, src) }
                    )
                }
            }
        }
        opf.manifest.values.firstOrNull { "nav" in it.properties }?.let { navItem ->
            val navPath = ZipPaths.resolve(opfDir, navItem.href)
            val navFile = File(unzipped, navPath)
            if (navFile.isFile) {
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
            val smilFile = File(unzipped, smilPath)
            if (!smilFile.isFile) continue
            val smilDir = smilPath.substringBeforeLast('/', "")
            val parsed = SmilParser.parse(smilFile) ?: continue
            // Copy each referenced audio file once; group clips by the
            // text document they narrate, keeping the dominant audio.
            val byText = parsed.clips.groupBy { it.textHref }
            for ((textHref, clips) in byText) {
                val textPath = ZipPaths.resolve(smilDir, textHref)
                val audioCounts = clips.groupingBy { it.audioHref }.eachCount()
                val mainAudio = audioCounts.maxByOrNull { it.value }?.key ?: continue
                val audioSource = File(unzipped, ZipPaths.resolve(smilDir, mainAudio))
                if (!audioSource.isFile) continue
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
        val spineItems = opf.spine.mapNotNull { idref ->
            val item = opf.manifest[idref] ?: return@mapNotNull null
            if (item.mediaType != "application/xhtml+xml") null
            else item to ZipPaths.resolve(opfDir, item.href)
        }
        spineItems.forEachIndexed { index, (item, relPath) ->
            val itemFile = File(unzipped, relPath)
            val itemDir = relPath.substringBeforeLast('/', "")
            val converter = XhtmlConverter { src ->
                val clean = src.substringBefore('#').substringBefore('?')
                if (clean.startsWith("http", true) || clean.startsWith("data:", true)) {
                    null
                } else {
                    val imagePath = ZipPaths.resolve(itemDir, clean)
                    val imageFile = File(unzipped, imagePath)
                    if (!imageFile.isFile) {
                        null
                    } else {
                        val name = ZipPaths.assetFileName(imagePath, usedAssetNames)
                        imageFile.copyTo(File(assetsDir, name), overwrite = true)
                        name
                    }
                }
            }
            val converted = if (itemFile.isFile) {
                runCatching {
                    itemFile.inputStream().buffered().use { converter.convert(it) }
                }.getOrNull()
            } else {
                null
            }
            if (converted == null) {
                failures++
                writer.add(titles[relPath] ?: "Chapter ${index + 1}", ChapterContent())
            } else if (converted.blocks.isEmpty() && overlays[relPath] == null) {
                // v1.0.6.2 (F7): a spine item that converts
                // cleanly to NO readable blocks and carries no
                // narration is front matter (a cover/title page),
                // not a chapter. Storing it as an empty chapter
                // made every real chapter's number one higher
                // than the book's own headings ("Chapter 2" over
                // the chapter titled Chapter 1). Skip it.
                Unit
            } else {
                val content = ChapterContent(
                    blocks = converted.blocks,
                    mediaOverlay = overlays[relPath]
                )
                writer.add(
                    titles[relPath] ?: converted.heading ?: "Chapter ${index + 1}",
                    content
                )
            }
            onProgress(if (spineItems.isEmpty()) 1f else (index + 1).toFloat() / spineItems.size)
        }
        if (spineItems.isNotEmpty() && failures * 2 > spineItems.size) {
            return AppResult.Err(AppError.BadFile("this EPUB's chapters couldn't be read"))
        }
        if (writer.metas.isEmpty()) {
            return AppResult.Err(AppError.BadFile("this EPUB has no readable chapters"))
        }

        val coverFile = opf.coverHref?.let { href ->
            val src = File(unzipped, ZipPaths.resolve(opfDir, href))
            if (!src.isFile) {
                null
            } else {
                val ext = href.substringAfterLast('.', "img").lowercase().take(5)
                val dest = File(workDir, "cover.$ext")
                try {
                    src.copyTo(dest, overwrite = true)
                    dest
                } catch (e: IOException) {
                    null
                }
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
                assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true }
            )
        )
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

    private fun extractZip(staged: File, destDir: File): AppError? {
        return try {
            destDir.mkdirs()
            val destCanonical = destDir.canonicalPath
            var entries = 0
            ZipInputStream(staged.inputStream().buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ENTRIES) {
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
                            var n = zip.read(buffer)
                            while (n >= 0) {
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
        val coverHref: String?
    )

    private fun parseOpf(file: File): Opf? = try {
        val parser = newParser(file)
        var title: String? = null
        var author: String? = null
        var description: String? = null
        var language: String? = null
        var coverId: String? = null
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
                }
            }
            event = parser.next()
        }
        val coverHref = coverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { "cover-image" in it.properties }?.href
        Opf(title, author, description, language, manifest, spine, tocId, coverHref)
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
        private const val MAX_ENTRIES = 20_000
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
