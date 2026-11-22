package app.xreader.data.prepare

import app.xreader.data.db.Book
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.canonicalHrefKey
import app.xreader.data.parse.ZipPaths
import app.xreader.ui.localreader.EpubPrepareRecord

/**
 * v1.7 Patch 3: the ONE prepare-record validity rule and the
 * href bridge every consumer shares — the route resolver
 * (Item 2a), the local reader's open flow (Item 1a), the
 * novel reader (Item 3e's mark filter) and the book page
 * (Item 4m) must never grow their own versions of either.
 */
object EpubPrepareAccess {

    /** The loader version records are written with. A record
     * from any other version does not key routing: the book
     * re-prepares under the current loader. Version 2 is the
     * Moon Reader update's segmentation (patch 1): chapters
     * became the book's own TOC anchors, so every prepared
     * book re-prepares once and its place and marks ride
     * the parser's merge map across the renumbering.
     * Version 3 is the TOC-tail fix (2026-11-22): the
     * segmentation pass no longer drops the book's last TOC
     * anchors (a forward walk off the book's end, a split
     * dedup collapse, a case-diverged href), so every
     * prepared book re-prepares once more and gains its
     * missing tail chapters, place and marks riding the
     * same merge map. */
    const val LOADER_VERSION: Int = 3

    /**
     * A book counts as PREPARED when a record exists, its
     * loader version is current, and its fingerprint still
     * matches the book's file: the record stores the converted
     * file's SHA-256, compared against the sha segment (the
     * last `|` segment) of [Book.fileFingerprint]. A book
     * whose fingerprint was never captured (old rows) is
     * judged on the record alone; a changed file (re-point,
     * Item 4l) fails the match and re-prepares under Item 3d.
     */
    fun isPrepared(record: EpubPrepareRecord?, book: Book): Boolean {
        record ?: return false
        if (record.loaderVersion != LOADER_VERSION) return false
        val bookSha = book.fileFingerprint
            ?.substringAfterLast('|')
            ?.takeIf { it.isNotBlank() }
            ?: return true
        return record.fingerprint == bookSha
    }

    /**
     * Item 2e: the Contents door for a prepared book. The
     * door's target is a SESSION-space `{"section":i}`; the
     * prepare record's map (built at prepare time, the one
     * moment both index spaces coexisted) translates it to a
     * landed chapter id, resolved against [landedChapters]
     * to the core chapter index. A section with no map entry
     * (the parser skipped it — Item 3a's divergence) or an
     * unresolvable target lands on [continueChapterIndex]:
     * the book opens at its Continue place.
     */
    fun contentsChapterIndex(
        record: EpubPrepareRecord,
        targetJson: String?,
        landedChapters: List<app.xreader.data.db.Chapter>,
        continueChapterIndex: Int
    ): Int {
        val section = LocatorJson.targetIndex(targetJson)
            ?: return continueChapterIndex
        val chapterId = record.sectionMap[section]
            ?: return continueChapterIndex
        return landedChapters.firstOrNull { it.id == chapterId }?.index
            ?: continueChapterIndex
    }
}

/**
 * v1.7 Patch 3 (Item 3a): the href bridge between a Readium
 * session's reading-order space and the parser's spine
 * space. The two provably diverge (the parser never reads
 * `linear`, filters by media type, and skips empty-converting
 * front matter), so sections are matched BY HREF, never by
 * saved index.
 *
 * Keying: parser hrefs are OPF-relative filesystem paths,
 * already percent-decoded by the parser's own resolution
 * ([ZipPaths.percentDecode] semantics — a '+' in a file name
 * survives). Session and locator hrefs are URL forms that
 * [canonicalHrefKey] collapses (path portion, URL-decoded).
 * Both key forms are registered per parser chapter and both
 * are tried per lookup, so a name either road spells
 * differently still meets its chapter.
 */
class EpubHrefBridge(parserHrefs: List<String>) {

    private val byKey: Map<String, Int> = run {
        val map = LinkedHashMap<String, Int>()
        parserHrefs.forEachIndexed { index, href ->
            val bare = href.substringBefore('#')
            map.putIfAbsent(ZipPaths.percentDecode(bare), index)
            map.putIfAbsent(canonicalHrefKey(bare), index)
        }
        map
    }

    /** The parser-space chapter an external href (a locator's
     * href, or a session reading-order href) names, or null. */
    fun chapterForHref(href: String?): Int? {
        if (href.isNullOrBlank()) return null
        byKey[canonicalHrefKey(href)]?.let { return it }
        return byKey[ZipPaths.percentDecode(href.substringBefore('#'))]
    }

    /** The parser-space chapter a SESSION-space index names,
     * spent through the session's own href list (Item 3a's
     * session bridge — the raw index is never trusted as a
     * parser-space index). */
    fun chapterForSessionIndex(
        sessionHrefs: List<String>,
        sessionIndex: Int
    ): Int? = chapterForHref(sessionHrefs.getOrNull(sessionIndex))
}
