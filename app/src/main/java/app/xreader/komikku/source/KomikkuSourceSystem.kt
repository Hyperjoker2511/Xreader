/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
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
 * v1.5.7 (Komikku full — owner sharpening: "Copy Komikku how it
 * uses Keiyoushi source. Not imitation. Full copy."): how the
 * copied Komikku source system fits together in Xreader, and
 * the reader's donor-shaped binding to it.
 *
 * THE DONOR'S SHAPE (research/komikku, studied end-to-end):
 *  1. ExtensionApi fetches the repo index (the extensions.json
 *     catalogue) from the configured extension repos.
 *  2. ExtensionManager downloads an extension APK, verifies it
 *     (signature trust), and installs it (PackageInstaller /
 *     private exts dir).
 *  3. ExtensionLoader loads the installed package's Dex,
 *     reads its SOURCE_CLASS / SOURCE_FACTORY metadata, and
 *     instantiates the extension's Source / SourceFactory.
 *  4. AndroidSourceManager registers every live Source by its
 *     id; consumers resolve sourceManager.get(id) and drive
 *     the eu.kanade.tachiyomi.source.Source DIRECTLY:
 *     fetchPopularManga / fetchSearchManga / fetchLatestUpdates
 *     / fetchMangaDetails / fetchChapterList / getPageList.
 *  5. The reader binds exactly there: ReaderViewModel takes
 *     the HttpSource from the source manager, ChapterLoader
 *     builds HttpPageLoader(chapter, source), and page bytes
 *     come from source.getImage(page) on the SOURCE's own
 *     OkHttp client, into the ChapterCache, out as streams.
 *
 * XREADER'S COPY OF THAT SHAPE (what already stands, and what
 * this file binds):
 *  - :mihon-api IS the donor's source-api, transplanted
 *    verbatim (eu.kanade.tachiyomi.source.Source / HttpSource /
 *    CatalogueSource / SourceFactory + the model types) — the
 *    ABI real Keiyoushi extensions are compiled against.
 *  - KeiyoushiPlayerManager is steps 1–4: catalog() is the repo
 *    index fetch; beginInstall/confirmInstall is the verified
 *    download+install; loadRecord is the Dex load +
 *    instantiation (ChildFirstPathClassLoader); liveSourcesOf
 *    / sourceById / allLiveSources are the source registry
 *    (AndroidSourceManager's get/getAll).
 *  - MihonSourceAdapter is the driving layer: it calls the
 *    donor Source's own suspend API the way the donor's own
 *    interactors do (getPageList, getImageUrl, getImage,
 *    fetchMangaDetails… via the mihon-api HttpSource surface)
 *    and maps SManga/SChapter/Page at the boundary, exactly
 *    where the donor converts too (Manga.toSManga()).
 *  - THIS object is step 5's binding for the reader's IMAGE
 *    leg: getImage (adapter.pageBytes through the bridge's
 *    owner registry) and its ownership check, exposed with
 *    donor names so the reader's pipeline reads as the donor's
 *    ChapterLoader/HttpPageLoader reads. The PAGE LIST does
 *    not travel through this object — v1.5.8 (A8): it travels
 *    the pipeline seam (PipelinePageLoader → ensuredPages →
 *    ChapterTextLoader.ensureStored →
 *    ExtensionChapterFetcher), the road every v1.5.8 A/B fix
 *    hardens. This facade's former getPageList was DELETED:
 *    it shipped in v1.5.7 with no production caller while a
 *    build report claimed it fed the loader; a surface that
 *    looks wired but is not is how false claims happen.
 *    The bridge's owner map is the one Xreader addition
 *    the donor doesn't need: the donor holds the Source object
 *    in-process, while Xreader's stored-page model must find
 *    the owning source again after a process death (the M2/M3
 *    seams); the FETCH itself is the donor's — the source's own
 *    image request on the source's own client.
 */
package app.xreader.komikku.source

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.extension.SourceHandle
import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.data.extension.player.PlayerImageBridge
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter

/**
 * The copied source system as the reader and browse bind to
 * it. All functions delegate to the donor-shaped pieces named
 * in the header; nothing here invents a second source stack.
 */
object KomikkuSourceSystem {

    /** True when [handle]'s source is a Keiyoushi extension
     * source (the donor's HttpSource-backed catalogue source),
     * i.e. manga by construction. */
    fun isKeiyoushiSource(handle: SourceHandle): Boolean =
        handle.source is MihonSourceAdapter

    /** The adapter driving the donor Source inside [handle],
     * or null for non-Keiyoushi sources. */
    fun keiyoushiAdapter(handle: SourceHandle): MihonSourceAdapter? =
        handle.source as? MihonSourceAdapter

    // -- Catalogue driving (donor CatalogueSource calls) ------

    fun fetchPopularManga(handle: SourceHandle, page: Int): SourceBookPage? =
        keiyoushiAdapter(handle)?.popular(page)

    fun fetchLatestUpdates(handle: SourceHandle, page: Int): SourceBookPage? =
        keiyoushiAdapter(handle)?.latest(page)

    fun fetchSearchManga(
        handle: SourceHandle,
        query: String,
        page: Int
    ): SourceBookPage? = keiyoushiAdapter(handle)?.search(query, page)

    fun fetchMangaDetails(handle: SourceHandle, book: SourceBook): SourceBook? =
        keiyoushiAdapter(handle)?.details(book)

    fun fetchChapterList(
        handle: SourceHandle,
        book: SourceBook
    ): List<SourceChapter>? = keiyoushiAdapter(handle)?.chapters(book)

    // -- Reader driving (donor ChapterLoader/HttpPageLoader) --

    /**
     * Donor `source.getImage(page)`: one page's bytes through
     * the OWNING source's own image request. The URL's owner is
     * resolved through the bridge registry (the post-process-
     * death seam); an owned URL that cannot be served returns
     * null — never a headerless plain fetch (the M2 law, which
     * is also the donor's spirit: a page comes from its source
     * or it fails visibly).
     */
    suspend fun getImage(url: String): ByteArray? =
        PlayerImageBridge.fetchOrReresolve(url)

    /** Whether [url] is owned by a Keiyoushi source (so a null
     * from [getImage] is a hard failure, not a cue to fall back
     * to a plain fetch). */
    fun isOwnedBySource(url: String): Boolean =
        PlayerImageBridge.ownerOf(url) != null

    /**
     * The Finding-4 seam, reader side: the Keiyoushi source
     * that CLAIMS [book]'s host, when the coordinator's wiring
     * supplies the claimed-hosts lookup (the extension
     * workstream's keiyoushiClaimedHosts seam). Until that
     * wiring lands, this returns null and the book's manga
     * route stands on the declarative kind stamp + this
     * reader's Finding-3 seam — the owner's link-added Asura
     * opens as manga either way; this seam is where the real
     * extension would take over driving it, with no re-add.
     */
    fun keiyoushiSourceFor(
        book: Book,
        claimedSourceProvider: ((Book) -> SourceHandle?)?
    ): SourceHandle? {
        val handle = claimedSourceProvider?.invoke(book) ?: return null
        return handle.takeIf { isKeiyoushiSource(it) }
    }

    /** AppResult unwrapping for call sites that prefer it. */
    fun <T> AppResult<T>.valueOrNull(): T? = when (this) {
        is AppResult.Ok -> value
        is AppResult.Err -> null
    }
}
