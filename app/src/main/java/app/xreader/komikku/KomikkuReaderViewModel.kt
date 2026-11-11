/*
 * Copyright 2024 The Komikku Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): the transplanted reader's state holder — the
 * donor ReaderViewModel's reader-facing shape (chapter window,
 * ChapterLoader orchestration, per-series reading mode /
 * orientation, progress on page change) over Xreader's stores:
 * chapters and progress are the app's Room rows, page lists arrive
 * through ChapterTextLoader/BookStorage, and history is the app's
 * one-record-per-book store (recordHistoryStart). Preferences:
 * the global ReaderPreferences resolves from Injekt exactly as
 * the donor's does; the scoped (per-book) preferences are our
 * option-scope addition on top.
 */
package app.xreader.komikku

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.XreaderApp
import app.xreader.core.error.AppResult
import androidx.datastore.preferences.core.edit
import app.xreader.data.db.Chapter
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.Bookmark
import app.xreader.data.download.BodyOrigin152
import app.xreader.data.extension.DeclarativeSource
import app.xreader.data.extension.LiveSource
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.player.MangaMemoHeal
import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.data.extension.player.PlayerImageBridge
import app.xreader.data.prefs.settingsDataStore
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterPages
import app.xreader.extensionapi.SourceChapter
import app.xreader.komikku.loader.ChapterLoader
import app.xreader.komikku.loader.ChapterPagePipeline
import app.xreader.komikku.loader.KomikkuPageAdapter
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.ReaderPage
import app.xreader.komikku.model.ViewerChapters
import app.xreader.komikku.setting.ChapterShape
import app.xreader.komikku.setting.PageDimension
import app.xreader.komikku.setting.ReaderOrientation
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode
import app.xreader.komikku.setting.ReadingModeResolution
import app.xreader.komikku.setting.ScopedKomikkuStore
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class KomikkuReaderState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    /** v1.5.8 (K5): the recorded failure detail behind
     * [errorMessage] (ensureStored's DiagnosticLog record,
     * verbatim) — shown one tap deeper under "Details". Null
     * when no detail was recorded. v1.5.17 (F1): the failing
     * call's own exception message is carried here too (see
     * [ReaderErrorDetail.forChapter]). */
    val errorDetail: String? = null,
    val bookTitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val viewerChapters: ViewerChapters? = null,
    val currentPage: Int = 0,
    val readingMode: ReadingMode = ReadingMode.WEBTOON,
    val orientation: ReaderOrientation = ReaderOrientation.FREE,
    val chapterIndex: Int = 0,
    /** §6.2's decode tier for this device (SlicePlan152 budgets). */
    val tier: app.xreader.data.prefs.PerfTier = app.xreader.data.prefs.PerfTier.FULL,
    /** v1.5.7 (R-03/F-12): chapter ids carrying a chapter
     * bookmark (the same Bookmark store the book page reads). */
    val bookmarkedChapterIds: Set<Long> = emptySet(),
    /** v1.5.7 (R-18): an adjacent chapter is loading — the
     * donor's modal Loading dialog state. */
    val isLoadingAdjacent: Boolean = false,
    /** v1.5.10 (B4): the page-list wait has crossed the
     * honest bound (SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS)
     * — the loading surface can say the chapter is taking
     * long instead of spinning inertly, and a DiagnosticLog
     * stall record was written. Back still works: leaving
     * cancels the wait exactly as before. */
    val stalled: Boolean = false
) {
    val currentChapter: ReaderChapter? get() = viewerChapters?.currChapter
    val pageCount: Int get() = currentChapter?.pages?.size ?: 0
    val hasPreviousChapter: Boolean get() = viewerChapters?.prevChapter != null
    val hasNextChapter: Boolean get() = viewerChapters?.nextChapter != null
}

class KomikkuReaderViewModel(
    application: Application,
    private val bookId: Long,
    initialChapterId: Long,
    private val initialPage: Int?
) : AndroidViewModel(application) {

    private val container: AppContainer =
        (application as XreaderApp).container

    /** The donor pattern: global defaults resolve from Injekt. */
    val globalPreferences: ReaderPreferences = Injekt.get()

    /** Option-scope addition: this series' overlay over the global
     * defaults. In-reader setting writes land here — this book
     * only; the global store is never written through it. */
    val scopedPreferences: ReaderPreferences = ReaderPreferences(
        ScopedKomikkuStore(
            container.komikkuGlobalStore,
            container.komikkuBookStore(bookId)
        )
    )

    private val pipeline = object : ChapterPagePipeline {
        override suspend fun storedPages(
            bookId: Long,
            chapterId: Long
        ): ChapterPages? =
            when (val result = container.bookStorage.readChapterContent(bookId, chapterId)) {
                is AppResult.Ok -> result.value.pages
                is AppResult.Err -> null
            }

        override suspend fun ensuredPages(
            bookId: Long,
            chapterId: Long
        ): ChapterPages? {
            val chapter = container.database.chapterDao().getById(chapterId)
                ?: return storedPages(bookId, chapterId)
            // v1.5.7 (Finding 3 seam) + v1.5.8 (A7): a MANGA
            // chapter whose stored content EXISTS but carries
            // NO page record is poison (see PoisonClearingEnsurer).
            // A7 drops the process-wide once-set: the clear is
            // claimed only by the stored shape in front of us —
            // after a FAILED fetch nothing (or fresh prose) is
            // stored, so a second open clears again and Try
            // again can succeed; after a successful fetch the
            // page record stands and nothing is cleared.
            return poisonEnsurer.ensuredPages(bookId, chapterId)
        }

        override fun providesPageBytes(): Boolean = true

        /**
         * v1.5.7 (Komikku full): the donor HttpPageLoader's
         * fetch, Xreader-shaped. A bridge-owned URL is fetched
         * through its owning source's own image request
         * (PlayerImageBridge.fetchOrReresolve ==
         * MihonSourceAdapter.pageBytes == the donor's
         * HttpSource.getImage: the source's headers on the
         * source's client). A URL the bridge OWNS but cannot
         * serve fails honestly (the M2 law: never degrade to a
         * headerless plain fetch). Any other URL (declarative
         * manga, local-network sources) rides the app's
         * session-carrying image client — the same client Coil
         * uses for covers — with the chapter's Referer.
         */
        override suspend fun fetchImageBytes(
            url: String,
            referer: String?
        ): ByteArray? {
            // The donor binding (KomikkuSourceSystem): a
            // source-owned page comes from its source's own
            // getImage, or fails honestly.
            app.xreader.komikku.source.KomikkuSourceSystem
                .getImage(url)?.let { return it }
            if (app.xreader.komikku.source.KomikkuSourceSystem
                    .isOwnedBySource(url)
            ) {
                return null
            }
            return withContext(Dispatchers.IO) {
                runCatching {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .apply {
                            referer?.let { header("Referer", it) }
                        }
                        .build()
                    imageClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            null
                        } else {
                            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                        }
                    }
                }.getOrNull()
            }
        }

        override fun pageCacheDir(): File =
            File(getApplication<Application>().cacheDir, "komikku-pages")

        /**
         * v1.5.6 (§5.3, M3): cold-process refresh of a stored
         * chapter's page registrations, keyed by the chapter's
         * source identity (the book's extension + source id,
         * the chapter's remote id). The adapter re-resolves
         * the page list so every page is registered with the
         * image bridge again; the stored record itself is
         * never re-downloaded or rewritten. Runs at most once
         * per chapter per process (a successful refresh marks
         * the chapter in [refreshedChapterIds]); any failure
         * leaves the chapter unmarked so a later open retries.
         */
        override suspend fun refreshRegistrations(
            bookId: Long,
            chapterId: Long
        ) {
            if (chapterId in refreshedChapterIds) return
            val book = container.database.bookDao().getById(bookId)
                ?: return
            val extensionId = book.extensionId ?: return
            val (sourceId, _) = SourceBookIds.decode(book.remoteId)
                ?: return
            val chapter = container.database.chapterDao()
                .getById(chapterId) ?: return
            val remoteChapterId = chapter.remoteId ?: return
            val handle = (container.extensionService.source(
                extensionId, sourceId
            ) as? AppResult.Ok)?.value ?: return
            val adapter = handle.source as? MihonSourceAdapter
                ?: return
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.extensionService.guarded(handle) {
                        adapter.refreshChapterPages(
                            SourceChapter(
                                id = remoteChapterId,
                                name = chapter.title,
                                // v1.5.11 (transplant — Gemini
                                // hunk 4A): the cold-start
                                // registration road builds the
                                // SAME complete chapter as the
                                // fetcher road (hunks 2A/2B):
                                // the number recovered from the
                                // title by the in-file helper,
                                // and the stored dateText /
                                // scanlator / memo (schema v12)
                                // — never (id, title, null)
                                // again.
                                // v1.5.12 (M4): the persisted
                                // number (inside the memo payload)
                                // beats the title parse — an
                                // unnumbered title ("Prologue")
                                // parses to null here, but the
                                // extension's true number was
                                // persisted at chapter-list time.
                                number = MihonSourceAdapter
                                    .persistedChapterNumber(chapter.memo)
                                    ?: chapterNumberOf(chapter.title),
                                dateText = chapter.dateText,
                                scanlator = chapter.scanlator,
                                memo = chapter.memo
                            )
                        )
                    }
                }.getOrNull()
            }
            if ((result as? AppResult.Ok)?.value == true) {
                refreshedChapterIds += chapterId
            }
        }

        /**
         * v1.5.11 (transplant, Verdict 1): the donor
         * ChapterLoader binding for a Keiyoushi chapter — the
         * live adapter (standing in the donor HttpSource's
         * place) plus the facade chapter rebuilt from the
         * chapter's stored row with its FULL field set (title,
         * title-parsed number, dateText, scanlator, memo), so
         * the transplanted HttpPageLoader's getPageList hands
         * the extension the same complete SChapter Mihon
         * would. Null when the book's source is not a
         * MANGA-kind Keiyoushi HttpSource (declarative / JS /
         * local / novel-extension chapters keep their existing
         * roads). The book's source resolves by stable
         * identity (the B5 seam the fetcher road uses), so a
         * stale stored row id heals instead of failing the
         * chapter open.
         */
        override suspend fun httpSourceBinding(
            bookId: Long,
            chapterId: Long
        ): app.xreader.komikku.loader.HttpSourceBinding? {
            val book = container.database.bookDao().getById(bookId)
                ?: return null
            if (book.extensionId == null) return null
            val chapter = container.database.chapterDao()
                .getById(chapterId) ?: return null
            val remoteChapterId = chapter.remoteId ?: return null
            val handle = (container.extensionService
                .resolveBookSource(book) as? AppResult.Ok)?.value
                ?: return null
            val adapter = handle.source as? MihonSourceAdapter
                ?: return null
            if (adapter.mediaKind != MediaKind.MANGA) return null
            if (adapter.mihon !is
                eu.kanade.tachiyomi.source.online.HttpSource
            ) {
                return null
            }
            // v1.5.17 (F1 — heal-on-open, V1513-LANE-A A-1):
            // a chapter whose EFFECTIVE memo is missing/empty
            // (a legacy pre-v12 row: memo NULL, restored by
            // the cold rebuild as an empty object) dies inside
            // the extension's getPageList before any network
            // call ("Refresh Chapter List"). Run ONE
            // chapter-list refresh for the book through the
            // sync apply path — the healed memo lands in the
            // adapter's retained snapshot AND the chapter
            // rows — then re-read the row and build the
            // binding from it. Bounded by [MangaMemoHeal]:
            // at most one attempt per book per process,
            // single-flight per book, and the open proceeds
            // regardless of the outcome.
            var row = chapter
            val probe = SourceChapter(
                id = remoteChapterId,
                name = row.title,
                number = MihonSourceAdapter
                    .persistedChapterNumber(row.memo)
                    ?: chapterNumberOf(row.title),
                dateText = row.dateText,
                scanlator = row.scanlator,
                memo = row.memo
            )
            val healRan = MangaMemoHeal.healOnceIfMemoMissing(
                key = MangaMemoHeal.key(
                    adapter.namespacedId, book.id.toString()
                ),
                memoMissing = adapter.effectiveMemoMissing(probe),
                refreshChapterList = {
                    container.sourceBookSync.refresh(book)
                }
            )
            if (healRan) {
                row = container.database.chapterDao()
                    .getById(chapterId) ?: row
            }
            return app.xreader.komikku.loader.HttpSourceBinding(
                source = adapter,
                chapter = SourceChapter(
                    id = remoteChapterId,
                    name = row.title,
                    // v1.5.12 (M4): persisted number first,
                    // title parse as fallback (see
                    // refreshRegistrations).
                    number = MihonSourceAdapter
                        .persistedChapterNumber(row.memo)
                        ?: chapterNumberOf(row.title),
                    dateText = row.dateText,
                    scanlator = row.scanlator,
                    memo = row.memo
                )
            )
        }

        override fun assetFile(bookId: Long, fileName: String): File =
            File(container.bookStorage.contentDir(bookId), "img-$fileName")
    }

    /**
     * v1.5.8 (A7): the poison ensurer behind
     * [pipeline.ensuredPages] — reads the stored shape, clears
     * a poisoned body (file delete + contentPath null, or
     * ensureStored would keep serving the same body from its
     * stored branch), then lets the live fetch decide. No
     * once-set anywhere: the stored shape IS the claim.
     */
    private val poisonEnsurer = PoisonClearingEnsurer(
        readStoredShape = { bId, cId ->
            when (
                val result = container.bookStorage.readChapterContent(bId, cId)
            ) {
                is AppResult.Ok -> PoisonClearingEnsurer.StoredShape(
                    hasBody = true, pages = result.value.pages
                )
                is AppResult.Err -> PoisonClearingEnsurer.StoredShape(
                    hasBody = false, pages = null
                )
            }
        },
        clearStoredBody = { bId, cId ->
            withContext(Dispatchers.IO) {
                container.bookStorage.resolve(
                    container.bookStorage.chapterContentRelative(bId, cId)
                ).delete()
            }
            container.bookStorage.invalidateBookFilesSize(bId)
            val row = container.database.chapterDao().getById(cId)
            if (row?.contentPath != null) {
                container.database.chapterDao()
                    .update(row.copy(contentPath = null))
            }
        },
        ensure = { bId, cId ->
            val chapter = container.database.chapterDao().getById(cId)
            if (chapter != null) {
                container.chapterTextLoader.ensureStored(bId, chapter)
            }
            readStoredPages(bId, cId)
        }
    )

    /**
     * v1.5.9 (E-2, Finding 5 heal rider): the one-time
     * picker-epoch invalidation, wired over the live stores.
     * The epoch marker lives in the app's shared settings
     * DataStore (the LocalReaderPrefs / app-settings seam —
     * [settingsDataStore], never Room). The sweep enumerates
     * every book: only declarative-backed sources
     * ([DeclarativeSource], or [LiveSource] — the learned
     * lane whose chapters derive through declarative configs)
     * whose book resolves MANGA through
     * [app.xreader.data.extension.ExtensionService.mediaKindOf]
     * contribute candidates; player (Keiyoushi) records never
     * passed through the declarative picker and are excluded
     * by construction. The decision rule itself is
     * [PickerEpochInvalidator]'s — this wiring only reads the
     * stores and performs the clear it orders.
     */
    private val pickerEpochInvalidator = PickerEpochInvalidator(
        readEpoch = {
            getApplication<Application>().settingsDataStore.data
                .first()[PickerEpoch.MARKER_KEY] ?: 0
        },
        writeEpoch = { epoch ->
            getApplication<Application>().settingsDataStore.edit { prefs ->
                prefs[PickerEpoch.MARKER_KEY] = epoch
            }
        },
        candidates = { pickerEpochCandidates() },
        invalidate = { candidate -> invalidatePickerRecord(candidate) }
    )

    /** The sweep's enumeration: every stored chapter record
     * of every declarative-backed MANGA book, read once. A
     * book whose handle no longer resolves contributes
     * nothing — the sweep never guesses a source's kind. */
    private suspend fun pickerEpochCandidates():
        List<PickerEpochInvalidator.Candidate> {
        val out = mutableListOf<PickerEpochInvalidator.Candidate>()
        for (book in container.database.bookDao().allBooks()) {
            val extensionId = book.extensionId ?: continue
            val (sourceId, _) = SourceBookIds.decode(book.remoteId)
                ?: continue
            val handle = (container.extensionService.source(
                extensionId, sourceId
            ) as? AppResult.Ok)?.value ?: continue
            if (handle.source !is DeclarativeSource &&
                handle.source !is LiveSource
            ) {
                continue
            }
            if (container.extensionService.mediaKindOf(handle, book) !=
                MediaKind.MANGA
            ) {
                continue
            }
            for (chapter in container.database.chapterDao().forBook(book.id)) {
                val content = (container.bookStorage.readChapterContent(
                    book.id, chapter.id
                ) as? AppResult.Ok)?.value
                out += PickerEpochInvalidator.Candidate(
                    bookId = book.id,
                    chapterId = chapter.id,
                    eligibleSource = true,
                    hasBody = content != null,
                    pages = content?.pages,
                    hasProse = content?.blocks?.any {
                        it is ChapterBlock.Paragraph ||
                            it is ChapterBlock.Heading
                    } == true,
                    rowDownloaded = BodyOrigin152.isDownloaded(chapter)
                )
            }
        }
        return out
    }

    /** Perform one invalidation the sweep ordered. A pure
     * image record is cleared by the poison clear's exact
     * machinery (content file deleted + the row's content
     * path nulled), so the next open re-derives the chapter
     * through the fixed picker via ensureStored. A MIXED
     * record (prose + pages) is NEVER file-cleared: only its
     * page record is dropped and the body rewritten, prose
     * byte-for-byte as stored. */
    private suspend fun invalidatePickerRecord(
        candidate: PickerEpochInvalidator.Candidate
    ) {
        if (candidate.hasProse) {
            val content = (container.bookStorage.readChapterContent(
                candidate.bookId, candidate.chapterId
            ) as? AppResult.Ok)?.value ?: return
            container.bookStorage.writeChapterContent(
                candidate.bookId, candidate.chapterId,
                content.copy(pages = null)
            )
            return
        }
        withContext(Dispatchers.IO) {
            container.bookStorage.resolve(
                container.bookStorage.chapterContentRelative(
                    candidate.bookId, candidate.chapterId
                )
            ).delete()
        }
        container.bookStorage.invalidateBookFilesSize(candidate.bookId)
        val row = container.database.chapterDao()
            .getById(candidate.chapterId)
        if (row?.contentPath != null) {
            container.database.chapterDao()
                .update(row.copy(contentPath = null))
        }
    }

    /** The stored pages read the pipeline's storedPages runs —
     * a named method so the poison ensurer's lambdas never
     * recurse into the pipeline object's own inference. */
    private suspend fun readStoredPages(
        bookId: Long,
        chapterId: Long
    ): ChapterPages? =
        when (
            val result = container.bookStorage.readChapterContent(bookId, chapterId)
        ) {
            is AppResult.Ok -> result.value.pages
            is AppResult.Err -> null
        }

    /** v1.5.8 (I5/E3): this book's excluded scanlators, kept
     * current for chapter-change selection. */
    @Volatile
    private var excludedScanlators: Set<String> = emptySet()

    /** The session-carrying image client (the one Coil rides),
     * built once for the plain-URL leg of the page fetch. */
    private val imageClient: okhttp3.OkHttpClient by lazy {
        app.xreader.di.ImageSessionClient.create(getApplication())
            as okhttp3.OkHttpClient
    }

    /** v1.5.7 (E-10): chapters whose pages this session has
     * already preloaded (next-chapter preload fires once per
     * chapter per process). */
    private val preloadedChapterIds: MutableSet<Long> =
        ConcurrentHashMap.newKeySet()

    /** Preloaded chapters by id, waiting to be transplanted
     * into the window when the reader crosses into them. */
    private val preloadedChapters =
        ConcurrentHashMap<Long, ReaderChapter>()

    private var isLocalBook = false

    /** v1.5.6 (M3): chapters whose page registrations this
     * process has already refreshed (see the pipeline). */
    private val refreshedChapterIds: MutableSet<Long> =
        ConcurrentHashMap.newKeySet()

    /** v1.5.6 (§5.6): the in-flight chapter-shape probe, if
     * any — cancelled when the chapter changes or the user
     * picks a mode explicitly. */
    private var shapeProbeJob: Job? = null

    /** The saved scroll fraction for the opening chapter, applied
     * to the page count once the pages land. */
    private var savedFraction: Float? = null

    /** v1.5.10 (B4): generation counter for the page-list
     * stall watcher — a newer load supersedes an older
     * watcher. */
    @Volatile
    private var pageStallSeq: Int = 0

    private val _state = MutableStateFlow(KomikkuReaderState())
    val state: StateFlow<KomikkuReaderState> = _state.asStateFlow()

    /** Series flags (the donor stores these on its Manga row; ours
     * live in the book's preference layer). 0 = DEFAULT = follow
     * the global default. */
    private val seriesStore = container.komikkuBookStore(bookId)
    private val seriesReadingMode = seriesStore.getInt("manga_reading_mode", 0)
    private val seriesOrientation = seriesStore.getInt("manga_reader_orientation", 0)

    init {
        // v1.5.8 (I5/E3): keep this book's scanlator exclusion
        // set current for chapter-change selection.
        viewModelScope.launch {
            container.bookReaderPrefs.excludedScanlators(bookId)
                .collect { excludedScanlators = it }
        }
        viewModelScope.launch { open(initialChapterId) }
    }

    private suspend fun open(chapterId: Long) {
        // v1.5.9 (E-2): the picker-epoch sweep runs on the
        // first manga-reader open whose marker is behind,
        // BEFORE any stored page record is served — this
        // open's own chapter included. It is awaited, not
        // raced: stored-first serving (the pipeline's
        // storedPages) must never win against it. A sweep
        // failure never fails the open: the marker stays
        // behind and the next open retries (the invalidator
        // advances it only after a completed sweep).
        try {
            pickerEpochInvalidator.runIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Best effort — the next open retries the sweep.
        }
        val book = container.database.bookDao().getById(bookId)
        isLocalBook = book?.extensionId == null
        val appearance = container.appearancePrefs.state.first()
        _state.value = _state.value.copy(
            tier = app.xreader.data.prefs.PerformancePolicy.resolve(
                appearance.performanceMode, container.totalRamMb(),
                appearance.coverGlow
            ).tier
        )
        val chapters = container.database.chapterDao().forBook(bookId)
            .sortedBy { it.index }
        if (chapters.isEmpty()) {
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = "This book has no chapters to read."
            )
            return
        }
        val target = chapters.firstOrNull { it.id == chapterId } ?: chapters.first()
        val saved = container.database.readingProgressDao().getForBook(bookId)
        savedFraction = if (saved != null && saved.chapterId == target.id) {
            saved.scrollFraction
        } else {
            null
        }
        container.recordHistoryStart(bookId)
        val bookmarked = container.database.bookmarkDao().forBook(bookId)
            .map { it.chapterId }.toSet()
        _state.value = _state.value.copy(bookmarkedChapterIds = bookmarked)
        loadInto(target, chapters, initialPage, book?.title ?: "")
    }

    /** One ChapterLoader over this reader's pipeline/prefs —
     * the same instance shape serves the open chapter and the
     * next-chapter preload (E-10). */
    private fun newChapterLoader(): ChapterLoader = ChapterLoader(
        bookId = bookId,
        isLocalBook = isLocalBook,
        pipeline = pipeline,
        readerPrefs = scopedPreferences,
        scope = viewModelScope
    )

    private suspend fun loadInto(
        target: Chapter,
        chapters: List<Chapter>,
        requestedPage: Int?,
        bookTitle: String
    ) {
        // v1.5.7 (R-18): the FIRST load keeps the full-screen
        // spinner; a load with a chapter already on screen is
        // the donor's adjacent-chapter Loading dialog instead.
        val adjacent = _state.value.pageCount > 0
        _state.value = _state.value.copy(
            loading = !adjacent,
            isLoadingAdjacent = adjacent,
            stalled = false,
            errorMessage = null
        )
        // v1.5.10 (B4): arm the stall watcher for this
        // page-list load — past the bound, a still-running
        // load surfaces the honest stall state + writes the
        // DiagnosticLog stall record. The watcher never
        // touches the load itself; leaving cancels as today.
        val stallSeq = ++pageStallSeq
        val stallStartedAt = System.currentTimeMillis()
        val stallChapterId = target.id
        viewModelScope.launch {
            kotlinx.coroutines.delay(
                app.xreader.data.extension.SourceBookSync
                    .CHAPTER_PENDING_UI_BOUND_MS
            )
            if (stallSeq != pageStallSeq) return@launch
            val current = _state.value
            if (!current.loading && !current.isLoadingAdjacent) {
                return@launch
            }
            _state.value = current.copy(stalled = true)
            val stallBook = container.database.bookDao()
                .getById(bookId)
            val stallSourceId = stallBook?.let {
                app.xreader.data.extension.SourceBookIds
                    .decode(it.remoteId)?.first
            } ?: "book $bookId"
            app.xreader.data.extension.ReaderStall.recordIfStalled(
                sourceId = stallSourceId,
                awaited = "chapter $stallChapterId pages",
                startedAtMs = stallStartedAt,
                nowMs = System.currentTimeMillis()
            )
        }
        val pairs = chapters.map { it.id to it.title }
        val window = KomikkuPageAdapter.viewerChapters(
            pairs, target.id,
            lastPageRead = 0,
            read = target.read
        )?.also { w ->
            // v1.5.8 (E2/I2): the transition cards read the
            // scanlator off the ReaderChapter — map it from
            // the Room column (E1) here, the one place the
            // window is built.
            val byId = chapters.associateBy { it.id }
            listOfNotNull(w.prevChapter, w.currChapter, w.nextChapter)
                .forEach { rc ->
                    rc.scanlator = byId[rc.chapterId]?.scanlator
                }
        } ?: run {
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = "That chapter couldn't be opened."
            )
            return
        }
        val loader = newChapterLoader()
        // v1.5.7 (E-10): a chapter this session preloaded keeps
        // its loaded pages — the load below is then the donor's
        // already-ready no-op instead of a second fetch.
        preloadedChapters.remove(target.id)?.let { preloaded ->
            if (preloaded.state is ReaderChapter.State.Loaded) {
                window.currChapter.pageLoader = preloaded.pageLoader
                window.currChapter.state = preloaded.state
            }
        }
        try {
            loader.loadChapter(window.currChapter, page = requestedPage)
        } catch (e: CancellationException) {
            // v1.5.6 (§5.1, M1): cancellation is not an error
            // state — propagate it untouched (ChapterLoader
            // already declined to stamp Error for it).
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                loading = false,
                isLoadingAdjacent = false,
                stalled = false,
                viewerChapters = window,
                bookTitle = bookTitle,
                chapters = chapters,
                chapterIndex = chapters.indexOf(target),
                readingMode = resolveReadingMode(),
                orientation = resolveOrientation(),
                errorMessage = "This chapter's pages couldn't be loaded.",
                // v1.5.8 (K5): the recorded reason rides along.
                // v1.5.17 (F1): the thrown cause's OWN words
                // ride too — pre-fix this path replaced the
                // real exception (e.g. the extension's
                // "Refresh Chapter List") with the headline
                // alone whenever no ensureStored record
                // existed, which is every HttpSource-road
                // failure.
                errorDetail = ReaderErrorDetail.forChapter(
                    app.xreader.data.extension.DiagnosticLog.entries(),
                    target.id,
                    e
                )
            )
            return
        }
        val pageCount = window.currChapter.pages?.size ?: 0
        // v1.5.8 (K5): a chapter whose load ENDED with no pages
        // (state Error, or Loaded-but-empty) is the page-list
        // error state — name it plainly and carry
        // ensureStored's recorded detail for the Details
        // expansion, instead of a blank screen over 0 pages.
        val loadFailed = window.currChapter.state is ReaderChapter.State.Error ||
            (window.currChapter.state is ReaderChapter.State.Loaded && pageCount == 0)
        if (loadFailed) {
            _state.value = _state.value.copy(
                loading = false,
                isLoadingAdjacent = false,
                stalled = false,
                viewerChapters = window,
                bookTitle = bookTitle,
                chapters = chapters,
                chapterIndex = chapters.indexOf(target),
                readingMode = resolveReadingMode(),
                orientation = resolveOrientation(),
                errorMessage = "This chapter's pages couldn't be loaded.",
                // v1.5.17 (F1): an Error state's cause is the
                // load's real exception — carry its words into
                // the Details instead of swallowing them
                // behind the headline (see the throw path
                // above). A Loaded-but-empty chapter has no
                // cause; the recorded detail (if any) stands.
                errorDetail = ReaderErrorDetail.forChapter(
                    app.xreader.data.extension.DiagnosticLog.entries(),
                    target.id,
                    (window.currChapter.state as?
                        ReaderChapter.State.Error)?.error
                )
            )
            return
        }
        val startPage = when {
            requestedPage != null -> requestedPage
            savedFraction != null && pageCount > 1 ->
                (savedFraction!! * (pageCount - 1)).toInt()
            else -> window.currChapter.requestedPage
        }
        savedFraction = null
        _state.value = _state.value.copy(
            loading = false,
            isLoadingAdjacent = false,
            stalled = false,
            viewerChapters = window,
            bookTitle = bookTitle,
            chapters = chapters,
            chapterIndex = chapters.indexOf(target),
            currentPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
            readingMode = resolveReadingMode(),
            orientation = resolveOrientation()
        )
        // v1.5.6 (§5.6): with nothing explicitly chosen, the
        // chapter's shape gets its say once dimensions land.
        probeChapterShape(window.currChapter)
    }

    /**
     * v1.5.6 (§5.6): explicit-vs-default resolution — an
     * explicit series choice wins; an explicit global choice
     * wins next; only when NOTHING was explicitly chosen may
     * the chapter's shape verdict (see [probeChapterShape])
     * lift the default chain to WEBTOON. The shape is not an
     * input here because no dimensions are known at first
     * resolution; the probe applies it when they land.
     */
    fun resolveReadingMode(): ReadingMode =
        ReadingModeResolution.resolve(
            seriesFlag = seriesReadingMode.get(),
            globalFlag = scopedPreferences.defaultReadingMode().get(),
            globalExplicit = globalPreferences
                .defaultReadingModeExplicit().get(),
            shapeWebtoon = null
        )

    /** True when any explicit mode choice exists (series or
     * global) — the shape verdict never overrules a choice. */
    private fun hasExplicitModeChoice(): Boolean =
        ReadingMode.fromPreference(seriesReadingMode.get()) !=
            ReadingMode.DEFAULT ||
            globalPreferences.defaultReadingModeExplicit().get()

    /**
     * v1.5.6 (§5.6 + amendment): compute the chapter's shape
     * verdict over the WHOLE page set. Pages whose dimensions
     * the decode paths haven't recorded yet are probed with a
     * small decode (aspect ratio is all the verdict reads);
     * the verdict applies only when it says WEBTOON and no
     * explicit choice exists.
     */
    private fun probeChapterShape(chapter: ReaderChapter) {
        shapeProbeJob?.cancel()
        // v1.5.8 (K7 census wire): "Auto webtoon mode" was a
        // dead row — the shape probe now honours it (default
        // ON, so shipped behaviour is unchanged until the
        // reader turns it off for this series).
        if (!scopedPreferences.useAutoWebtoon().get()) return
        if (hasExplicitModeChoice()) return
        val pages = chapter.pages ?: return
        if (pages.all { it.imageWidth > 0 }) {
            applyShapeVerdict(pages)
            return
        }
        shapeProbeJob = viewModelScope.launch {
            // v1.5.35 (item 3): the probe RETRIES while the
            // loader's fetches land. The shipped single pass
            // ran once at chapter open, when an online
            // chapter's page cache is still nearly empty —
            // its local-files-only probe (v1.5.11) found
            // fewer than the 3 dimension-known pages a
            // verdict needs, and nothing ever ran it again,
            // so manhwa chapters stayed in the default pager:
            // tall pages fit-inside = the skinny centred
            // column (and the small centred zoom-out rest)
            // of the v1.5.21 proof video. The verdict is
            // recomputed per pass; the loop stops the moment
            // it can decide EITHER way, an explicit choice
            // appears, auto-webtoon is switched off, or the
            // bounded passes run out. Dimensions now also
            // arrive stamped by the loaders themselves
            // (v1.5.35), so later passes mostly read fields,
            // not files.
            var pass = 0
            while (pass < 5) {
                if (!scopedPreferences.useAutoWebtoon().get()) {
                    return@launch
                }
                if (hasExplicitModeChoice()) return@launch
                for (page in pages) {
                    if (page.imageWidth <= 0) probePageDimension(page)
                }
                if (shapeVerdict(pages) != null) {
                    applyShapeVerdict(pages)
                    return@launch
                }
                pass++
                if (pass < 5) kotlinx.coroutines.delay(1_200)
            }
        }
    }

    /** The chapter's shape verdict over the pages'
     * currently recorded dimensions (null = not enough
     * known pages to decide). */
    private fun shapeVerdict(pages: List<ReaderPage>): Boolean? =
        ChapterShape.isWebtoonShaped(
            pages.map { page ->
                if (page.imageWidth > 0 && page.imageHeight > 0) {
                    PageDimension(page.imageWidth, page.imageHeight)
                } else {
                    null
                }
            }
        )

    private fun applyShapeVerdict(pages: List<ReaderPage>) {
        if (hasExplicitModeChoice()) return
        if (shapeVerdict(pages) == true &&
            _state.value.readingMode != ReadingMode.WEBTOON
        ) {
            _state.value = _state.value.copy(
                readingMode = ReadingMode.WEBTOON
            )
        }
    }

    /** A small decode whose only job is the page's aspect
     * ratio (dimensions as the loader resolves the page's
     * image request, at a probe size — the ratio is what the
     * verdict reads, and it survives the downscale).
     *
     * v1.5.11 (transplant — Gemini hunk 4B): the probe reads
     * LOCAL / CACHED page files ONLY. The pre-v1.5.11 probe
     * handed Coil the page's raw URL — for a lazily-registered
     * Keiyoushi page that URL is not a fetchable image address
     * at all (the adapter's #xreaderPage= registration key, or
     * a page url that only the source's own image request can
     * turn into bytes), so the probe failed or hung on exactly
     * the pages the shape verdict needed. Now: a downloaded
     * page probes its stored file; an online page probes the
     * loader's page-cache file when the loader has already
     * fetched it; a page with neither is left unprobed (its
     * dimension stays unknown and the verdict reads null for
     * it — honest absence, never a network guess). */
    private suspend fun probePageDimension(page: ReaderPage) {
        val file = page.localFile?.let { pipeline.assetFile(bookId, it) }
            ?.takeIf { it.isFile }
            ?: pageImageFile(page)
            ?: return
        val context = getApplication<Application>()
        val request = ImageRequest.Builder(context)
            .data(file)
            .size(Size(Dimension.Pixels(96), Dimension.Undefined))
            .apply {
                allowHardware(false)
            }
            .build()
        val result = withContext(Dispatchers.IO) {
            runCatching { context.imageLoader.execute(request) }
                .getOrNull()
        }
        val drawable = (result as? SuccessResult)?.drawable
            ?: return
        if (drawable.intrinsicWidth > 0 &&
            drawable.intrinsicHeight > 0
        ) {
            page.imageWidth = drawable.intrinsicWidth
            page.imageHeight = drawable.intrinsicHeight
        }
    }

    fun resolveOrientation(): ReaderOrientation {
        val series = ReaderOrientation.fromPreference(seriesOrientation.get())
        if (series != ReaderOrientation.DEFAULT) return series
        return ReaderOrientation.fromPreference(scopedPreferences.defaultOrientationType().get())
    }

    /** In-reader change: THIS series only (option-scope rule). */
    fun onChangeReadingMode(mode: ReadingMode) {
        // An explicit choice retires the shape probe (§5.6).
        shapeProbeJob?.cancel()
        seriesReadingMode.set(mode.flagValue)
        _state.value = _state.value.copy(readingMode = mode)
    }

    fun onChangeOrientation(orientation: ReaderOrientation) {
        seriesOrientation.set(orientation.flagValue)
        _state.value = _state.value.copy(orientation = orientation)
    }

    fun onPageChanged(page: Int) {
        val current = _state.value
        val pageCount = current.pageCount
        _state.value = current.copy(currentPage = page)
        val chapter = current.chapters.getOrNull(current.chapterIndex) ?: return
        val chapterFraction = if (pageCount > 1) {
            page.toFloat() / (pageCount - 1)
        } else {
            1f
        }
        val bookFraction = if (current.chapters.isNotEmpty()) {
            (current.chapterIndex + chapterFraction) / current.chapters.size
        } else {
            chapterFraction
        }
        // Donor: reaching the last page finishes the chapter —
        // it is marked read (the row the book page dims) and
        // the next chapter's preload fires (E-10).
        val finished = pageCount > 0 && page >= pageCount - 1
        if (finished && !chapter.read) {
            val updated = current.chapters.map {
                if (it.id == chapter.id) it.copy(read = true) else it
            }
            _state.value = _state.value.copy(chapters = updated)
            viewModelScope.launch {
                container.database.chapterDao().setRead(chapter.id, true)
            }
        }
        if (finished) preloadUpcoming()
        viewModelScope.launch {
            container.database.readingProgressDao().upsert(
                ReadingProgress(
                    bookId = bookId,
                    chapterId = chapter.id,
                    chapterIndex = current.chapterIndex,
                    charOffset = 0,
                    fraction = bookFraction,
                    scrollFraction = chapterFraction,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * v1.5.7 (E-13) + v1.5.8 (I5): the donor's chapter-change
     * selection — skip read chapters (skip_read, default off),
     * skip duplicates (same chapter number from another
     * listing, default off), and NOW skip filtered too
     * (skip_filtered, default ON): E3 gave the book a real
     * chapter-filter state (its excluded scanlators in
     * BookReaderPrefs), so the pref's premise exists and the
     * shipped selection skips those chapters. The semantics
     * live in [ChapterSelection] — this function only reads
     * the live prefs + exclusion set and delegates.
     * Returns the index to open, or null at the list's end.
     */
    private fun selectChapterIndex(fromIndex: Int, direction: Int): Int? {
        return ChapterSelection.select(
            chapters = _state.value.chapters,
            fromIndex = fromIndex,
            direction = direction,
            skipRead = scopedPreferences.skipRead().get(),
            skipDupe = scopedPreferences.skipDupe().get(),
            skipFiltered = scopedPreferences.skipFiltered().get(),
            excludedScanlators = excludedScanlators,
            numberOf = { title -> chapterNumberOf(title) }
        )
    }

    fun nextChapter() {
        val current = _state.value
        val index = selectChapterIndex(current.chapterIndex, +1) ?: return
        val next = current.chapters[index]
        viewModelScope.launch { loadInto(next, current.chapters, 0, current.bookTitle) }
    }

    fun previousChapter() {
        val current = _state.value
        val index = selectChapterIndex(current.chapterIndex, -1) ?: return
        val prev = current.chapters[index]
        viewModelScope.launch { loadInto(prev, current.chapters, 0, current.bookTitle) }
    }

    /** In-reader chapter list jump (R-11): loads the chosen
     * chapter in place — one load, never a chain of next/prev. */
    fun jumpToChapter(index: Int) {
        val current = _state.value
        val target = current.chapters.getOrNull(index) ?: return
        if (index == current.chapterIndex) return
        viewModelScope.launch { loadInto(target, current.chapters, 0, current.bookTitle) }
    }

    /**
     * v1.5.7 (E-10): preload the next chapter's pages the donor
     * way — when the reader reaches the last page, and while a
     * transition card is showing. Once per chapter per process;
     * failures are silent (the real load retries honestly when
     * the reader actually crosses).
     */
    fun preloadUpcoming() {
        val current = _state.value
        val next = current.viewerChapters?.nextChapter ?: return
        if (!preloadedChapterIds.add(next.chapterId)) return
        preloadedChapters[next.chapterId] = next
        viewModelScope.launch {
            runCatching { newChapterLoader().loadChapter(next) }
        }
    }

    /** ExhUtils Boost (R-10/E-09): the current page jumps the
     * loader queue. */
    fun boostCurrentPage() {
        val current = _state.value
        val page = current.currentChapter?.pages
            ?.getOrNull(current.currentPage) ?: return
        current.currentChapter?.pageLoader?.boostPage(page)
    }

    /** ExhUtils Retry all (R-10): re-queue every errored page;
     * returns the count for the toast. */
    fun retryAllErrored(): Int =
        _state.value.currentChapter?.pageLoader?.retryAllErrored() ?: 0

    /** The current chapter's page loader (page actions read
     * image files through it). */
    fun currentPageLoader(): app.xreader.komikku.loader.PageLoader? =
        _state.value.currentChapter?.pageLoader

    /** The on-disk file backing [page]'s image, when one exists
     * (downloaded file or page-cache entry). */
    fun pageImageFile(page: ReaderPage): File? =
        when (val loader = currentPageLoader()) {
            is app.xreader.komikku.loader.PipelinePageLoader ->
                loader.imageFile(page)
            // v1.5.11 (transplant): the donor loader's cache
            // file serves page actions (and the shape probe)
            // for Keiyoushi chapters.
            is app.xreader.komikku.loader.HttpPageLoader ->
                loader.imageFile(page)
            is app.xreader.komikku.loader.DownloadPageLoader ->
                loader.imageFile(page)
            else -> page.localFile?.let { pipeline.assetFile(bookId, it) }
                ?.takeIf { it.isFile }
        }

    /**
     * R-03/F-12: toggle the chapter bookmark for [chapterId] —
     * writes the same Bookmark store the book page reads, so a
     * chapter bookmarked in the reader shows on the book page.
     */
    fun toggleChapterBookmark(chapterId: Long) {
        viewModelScope.launch {
            val dao = container.database.bookmarkDao()
            val existing = dao.forBook(bookId).filter { it.chapterId == chapterId }
            val now = System.currentTimeMillis()
            if (existing.isNotEmpty()) {
                existing.forEach { dao.delete(it) }
            } else {
                val index = _state.value.chapters.indexOfFirst { it.id == chapterId }
                dao.insert(
                    Bookmark(
                        bookId = bookId,
                        chapterId = chapterId,
                        chapterIndex = index.coerceAtLeast(0),
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
            _state.value = _state.value.copy(
                bookmarkedChapterIds = dao.forBook(bookId)
                    .map { it.chapterId }.toSet()
            )
        }
    }

    /**
     * R-12/F-18: set [page] as the book's cover — the page's
     * image file becomes the book's cached cover (the same
     * coverPath the book page and library render).
     */
    fun setPageAsCover(page: ReaderPage, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val source = pageImageFile(page)
            val ok = source != null && withContext(Dispatchers.IO) {
                runCatching {
                    val ext = source.extension.takeIf { it.isNotBlank() } ?: "jpg"
                    val relative = container.bookStorage.coverRelative(bookId, ext)
                    val target = container.bookStorage.resolve(relative)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    val book = container.database.bookDao().getById(bookId)
                        ?: return@runCatching false
                    container.database.bookDao()
                        .update(book.copy(coverPath = relative))
                    container.bookStorage.invalidateBookFilesSize(bookId)
                    true
                }.getOrDefault(false)
            }
            onResult(ok)
        }
    }

    fun retry() {
        val current = _state.value
        val target = current.chapters.getOrNull(current.chapterIndex) ?: return
        viewModelScope.launch { loadInto(target, current.chapters, 0, current.bookTitle) }
    }

    /** The asset resolver the screen draws local pages through. */
    fun assetFile(fileName: String): File = pipeline.assetFile(bookId, fileName)

    /** A chapter's number parsed from its title ("Chapter 12.5",
     * "Ch. 12", a bare leading number) — the only number the
     * Room row carries; used by skip-duplicates (E-13) and the
     * transition cards' missing-chapter gap warning (R-13). */
    private fun chapterNumberOf(title: String?): Float? {
        if (title == null) return null
        val match = CHAPTER_NUMBER_REGEX.find(title) ?: return null
        val number = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: match.groupValues.getOrNull(2)
        return number?.toFloatOrNull()
    }

    companion object {
        // v1.5.8 (A7): the v1.5.7 process-wide poison-clear
        // once-set is GONE — see PoisonClearingEnsurer. A
        // chapter cleared once can be cleared again after a
        // failed fetch re-poisons it; the stored shape, not a
        // set, decides.

        private val CHAPTER_NUMBER_REGEX =
            Regex("""(?:chapter|ch\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)|^\s*(\d+(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)

        fun factory(
            application: Application,
            bookId: Long,
            chapterId: Long,
            page: Int?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                KomikkuReaderViewModel(application, bookId, chapterId, page) as T
        }
    }
}
