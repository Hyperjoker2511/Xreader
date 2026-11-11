/*
 * v1.5.8 (Builder 4): the manga reader's pure decision logic for
 * this revision — kept beside the ViewModel so the JVM proofs
 * exercise the SAME functions the production call path runs
 * (the gate's wiring rule: no test-only twins).
 */
package app.xreader.komikku

import androidx.datastore.preferences.core.intPreferencesKey
import app.xreader.data.db.Chapter
import app.xreader.data.extension.DiagnosticLog
import app.xreader.domain.content.ChapterPages

/**
 * A7 — the poison-clear decision, claim-after-outcome.
 *
 * A stored chapter body with NO page record is poison for the
 * manga reader (an older prose road stored HTML where pages
 * belong). The clear is NOT gated by any process-wide once-set:
 * each [ensuredPages] call re-reads the stored shape and clears
 * exactly when poison is present NOW. A successful fetch writes
 * a page record, so the next call sees no poison and clears
 * nothing; a failed fetch stores nothing (or re-stores prose,
 * which the next call clears again) — re-clearing is
 * self-limiting by construction, and Try again can succeed.
 */
class PoisonClearingEnsurer(
    /** The stored shape: body present? + its page record. */
    private val readStoredShape: suspend (bookId: Long, chapterId: Long) -> StoredShape?,
    /** Deletes the stored body + nulls the row's content path. */
    private val clearStoredBody: suspend (bookId: Long, chapterId: Long) -> Unit,
    /** The live fetch-and-store (ChapterTextLoader.ensureStored
     * in production); returns the stored pages afterwards. */
    private val ensure: suspend (bookId: Long, chapterId: Long) -> ChapterPages?
) {
    data class StoredShape(
        val hasBody: Boolean,
        val pages: ChapterPages?
    ) {
        /** Poison = a body exists but carries no usable pages. */
        val isPoison: Boolean
            get() = hasBody && (pages == null || pages.pages.isEmpty())
    }

    suspend fun ensuredPages(bookId: Long, chapterId: Long): ChapterPages? {
        val shape = readStoredShape(bookId, chapterId)
        if (shape?.isPoison == true) {
            clearStoredBody(bookId, chapterId)
        }
        return ensure(bookId, chapterId)
    }
}

/**
 * v1.5.9 (E-2) — the page-picker epoch. The v1.5.9 picker fix
 * (Finding 5 §6: `data-full` → largest `srcset` candidate →
 * `data-src` → `src`, in DeclarativeSource's two page pickers)
 * only helps chapters derived AFTER it ships: a chapter
 * already stored keeps the page record the OLD picker wrote
 * — for Fenrir-class markup, the site's preview URLs — and
 * the reader serves stored records first. Stored
 * [ChapterPages] records carry NO derivation marker (and gain
 * none: no new persisted fields, no Room schema change), so
 * the one-time heal cannot ask a record which picker wrote
 * it. The epoch marker below is the substitute: it lives in
 * the app's shared settings DataStore (the
 * `Context.settingsDataStore` file LocalReaderPrefs and the
 * app settings use — never Room; DB stays v11), and the first
 * reader open that finds it behind runs
 * [PickerEpochInvalidator]'s sweep over every stored record,
 * then advances it.
 */
object PickerEpoch {

    /** The shipped picker's epoch: 1 = the v1.5.9 picker.
     * Every stored image-page record on a device whose marker
     * is behind this was derived by a pre-fix picker. */
    const val CURRENT = 1

    /** The marker key in the settings DataStore. Absent (a
     * pre-v1.5.9 store, or a fresh install) reads as epoch 0:
     * behind — the sweep runs (a fresh install simply finds
     * nothing to clear) and stamps the marker current. */
    val MARKER_KEY = intPreferencesKey("komikku.pickerEpoch")
}

/**
 * v1.5.9 (E-2) — the one-time picker-epoch invalidation, in
 * the [PoisonClearingEnsurer] seam shape: pure decision logic
 * over injected store lambdas, so the JVM proofs exercise the
 * SAME rule the production sweep (wired in
 * KomikkuReaderViewModel) runs.
 *
 * The sweep clears a stored record only when EVERY guard
 * holds (see [Candidate.isStalePickerRecord]):
 *  - the book's source is declarative-backed and the book
 *    resolves MANGA (the caller resolves both through
 *    ExtensionService — player/Keiyoushi records, whose page
 *    lists come from the extension itself and never passed
 *    through the declarative picker, are never candidates);
 *  - a stored body with a non-empty page record exists;
 *  - NO page carries a downloaded file — the record is a
 *    pure reading cache, re-derivable on next open. A record
 *    with even one `localFile` is (part-)downloaded and is
 *    NEVER cleared; the chapter row's user-download state
 *    (BodyOrigin152's USER + READY) independently vetoes too;
 *  - prose/text bodies carry no page record by construction
 *    and are never candidates — and a MIXED body (prose +
 *    pages) is invalidated by the caller's prose-preserving
 *    branch (drop the page record, rewrite the body), never
 *    by deleting the file.
 *
 * Idempotence: the marker advances only after the whole
 * sweep completes, so a sweep cut short (process death, a
 * throwing store) is retried by the next open; records it
 * already cleared no longer match the stale shape, so the
 * retry clears nothing twice. Once the marker is current,
 * [runIfNeeded] does not even enumerate.
 */
class PickerEpochInvalidator(
    /** The stored epoch marker (settings DataStore). */
    private val readEpoch: suspend () -> Int,
    /** Advance the stored epoch marker. */
    private val writeEpoch: suspend (Int) -> Unit,
    /** Every stored chapter record the sweep must judge —
     * the caller enumerates books/chapters and reads each
     * stored body once. */
    private val candidates: suspend () -> List<Candidate>,
    /** Invalidate one stale record (the caller's clear seam:
     * the poison clear's file delete + contentPath null for a
     * pure image record; a pages-dropping rewrite for a mixed
     * prose+pages record). */
    private val invalidate: suspend (Candidate) -> Unit
) {

    data class Candidate(
        val bookId: Long,
        val chapterId: Long,
        /** Declarative-backed source + per-book MANGA verdict,
         * resolved by the caller (ExtensionService's reads). */
        val eligibleSource: Boolean,
        /** A stored chapter-content body exists. */
        val hasBody: Boolean,
        /** The stored body's page record, when it carries one
         * (null for prose/text bodies). */
        val pages: ChapterPages?,
        /** The stored body also carries prose blocks. Only
         * the caller's invalidate branch reads this: a mixed
         * record loses its page record, never its prose. */
        val hasProse: Boolean,
        /** The chapter row is a user download (BodyOrigin152:
         * origin USER + state READY). */
        val rowDownloaded: Boolean
    ) {
        /** A pre-epoch reading-cache image record: derived by
         * the old picker, carrying no downloaded page, safe
         * to re-derive on next open. */
        val isStalePickerRecord: Boolean
            get() = eligibleSource && hasBody && !rowDownloaded &&
                pages != null && pages.pages.isNotEmpty() &&
                pages.pages.all { it.localFile == null }
    }

    /** Run the sweep iff the stored marker is behind.
     * Returns how many records were invalidated. */
    suspend fun runIfNeeded(): Int {
        if (readEpoch() >= PickerEpoch.CURRENT) return 0
        var cleared = 0
        for (candidate in candidates()) {
            if (candidate.isStalePickerRecord) {
                invalidate(candidate)
                cleared++
            }
        }
        writeEpoch(PickerEpoch.CURRENT)
        return cleared
    }
}

/**
 * I5 — chapter-change selection: the shipped skip-read /
 * skip-dupe semantics (v1.5.7) PLUS skip-filtered over E3's
 * exclusion set. Pure; [KomikkuReaderViewModel.selectChapterIndex]
 * delegates here with the live prefs + exclusion set.
 */
object ChapterSelection {
    fun select(
        chapters: List<Chapter>,
        fromIndex: Int,
        direction: Int,
        skipRead: Boolean,
        skipDupe: Boolean,
        skipFiltered: Boolean,
        excludedScanlators: Set<String>,
        numberOf: (String?) -> Float?
    ): Int? {
        if (chapters.isEmpty()) return null
        val currentNumber = numberOf(chapters.getOrNull(fromIndex)?.title)
        var index = fromIndex + direction
        while (index in chapters.indices) {
            val candidate = chapters[index]
            val skip = (skipRead && candidate.read) ||
                (skipDupe && currentNumber != null &&
                    numberOf(candidate.title) == currentNumber) ||
                (skipFiltered &&
                    app.xreader.data.prefs.ScanlatorFilter.isExcluded(
                        candidate.scanlator, excludedScanlators
                    ))
            if (!skip) return index
            index += direction
        }
        return null
    }
}

/**
 * E2/I2 — the donor's chapter meta line: date and scanlator
 * dot-separated, each segment present only when its value is.
 * Null scanlator → the segment simply doesn't render (no
 * placeholder), matching MangaChapterListItem's shape.
 */
object ScanlatorLine {
    fun format(dateText: String?, scanlator: String?): String? {
        val parts = listOfNotNull(
            dateText?.takeIf { it.isNotBlank() },
            scanlator?.takeIf { it.isNotBlank() }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The scanlator segment alone (transition cards). */
    fun scanlatorSegment(scanlator: String?): String? =
        scanlator?.takeIf { it.isNotBlank() }
}

/**
 * K5(ii) — the reader error state's recorded detail: the
 * verbatim DiagnosticLog record ChapterTextLoader.ensureStored
 * left for this chapter (tag "download", detail "chapter N ·
 * book M"). Null when nothing was recorded — the error state
 * then shows its plain headline only.
 */
object ReaderErrorDetail {
    fun fromLog(
        entries: List<DiagnosticLog.Entry>,
        chapterId: Long
    ): String? {
        val marker = "chapter $chapterId"
        val entry = entries.lastOrNull {
            it.tag == "download" && it.detail.contains(marker)
        } ?: return null
        return entry.message
    }

    /**
     * v1.5.17 (F1): the failing call's OWN words — the first
     * non-blank message down [cause]'s chain, capped like
     * the adapter caps source messages (300 chars). This is
     * the detail the pre-fix reader swallowed: on the
     * HttpSource road nothing records the failure in the
     * DiagnosticLog, so the extension's exception (Asura's
     * "Refresh Chapter List") reached the ViewModel as the
     * thrown cause and was replaced by the plain headline.
     * Null when the cause carries no readable message.
     */
    fun fromCause(cause: Throwable?): String? {
        var current = cause
        while (current != null) {
            val message = current.message?.trim()
                ?.takeIf { it.isNotBlank() }
            if (message != null) return message.take(MAX_CAUSE_CHARS)
            current = current.cause
        }
        return null
    }

    /**
     * v1.5.17 (F1): the chapter error state's detail — the
     * cause's own words first (they ARE the failure), the
     * recorded log detail when the cause says nothing more
     * specific. One exception: ChapterLoader's own
     * empty-list sentinel ("The page list is empty") is a
     * host summary, not the failure — when the log recorded
     * the fetch's real reason (the pipeline road records
     * ensureStored's display text, extension detail
     * included), the record wins over the sentinel.
     */
    fun forChapter(
        entries: List<DiagnosticLog.Entry>,
        chapterId: Long,
        cause: Throwable?
    ): String? {
        val causeText = fromCause(cause)
        if (causeText != null && causeText != EMPTY_PAGE_LIST_MESSAGE) {
            return causeText
        }
        return fromLog(entries, chapterId) ?: causeText
    }

    /** ChapterLoader's empty-list sentinel (its literal
     * throw when a loader returns no pages). */
    private const val EMPTY_PAGE_LIST_MESSAGE = "The page list is empty"

    private const val MAX_CAUSE_CHARS = 300
}
