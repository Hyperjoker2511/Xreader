package app.xreader.data

import android.app.Application
import android.os.Looper
import app.xreader.data.db.Book
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.library.ShelfLogic
import app.xreader.di.AppContainer
import app.xreader.extensionapi.ExtensionApi
import app.xreader.ui.book.BookDetailViewModel
import app.xreader.ui.library.LibraryUiState
import app.xreader.ui.library.ShelfBook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.5.3 (item 28; finding #9, strand A3): the PRODUCTION
 * favourite round-trip, end to end — the seam the v1.5.2 gate
 * never exercised. [V152FavouriteJourneyTest] pins the key-level
 * truth only: it writes favourites via `setFavouriteByKey` and
 * hand-builds `BookFacts` from `favouritesByKey`, so it stayed
 * green while the owner's real Favourites shelf read 0 (the
 * favourited rows were previews, `inLibrary = false`, and every
 * Library surface reads `observeShelf()`).
 *
 * This test does the opposite at every step:
 *  - the book starts as a PREVIEW row, inserted the way
 *    `SourceBookSync.openBook` inserts it (`inLibrary = false`);
 *  - the favourite is set through the PRODUCTION writer,
 *    [BookDetailViewModel.setFavourite] — the exact call the
 *    book page's heart makes;
 *  - the verdict is read back through the PRODUCTION read
 *    mapping: `observeShelf()` rows → row-id
 *    [app.xreader.data.library.BookMetaPrefs.favourites] flow →
 *    [LibraryUiState.shelfFacts] → [ShelfLogic.builtinMembers].
 *
 * Item 24's promotion and the Favourites membership must BOTH
 * hold, or this test fails the way the phone did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V153FavouriteProductionRoundTripTest {

    private fun awaitCondition(
        what: String,
        timeoutMs: Long = 8_000,
        cond: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("timed out waiting for: $what", cond())
    }

    @Test
    fun `favouriting a preview from the book page promotes it onto the Favourites shelf`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val container = AppContainer(app)
            val db = container.database
            val repoId = db.extensionRepoDao().insert(
                ExtensionRepo(
                    name = "Fixture-v153fav",
                    indexUrl = "https://example.test/i"
                )
            )
            val extId = db.installedExtensionDao().insert(
                InstalledExtension(
                    repoId = repoId, displayName = "Fav Source",
                    packageId = "bundled.bundled-v153fav",
                    versionName = "1.0", versionCode = 1,
                    kind = ExtensionKind.DECLARATIVE, artifactPath = null,
                    sha256 = "x", apiLevel = ExtensionApi.API_LEVEL,
                    languages = "en", sourceIds = "fav", enabled = true,
                    trustState = TrustState.TRUSTED, permissionsJson = "{}",
                    installedAt = 1L, updatedAt = 1L
                )
            )
            // The preview row, shaped like openBook's insert:
            // browsed, never added — inLibrary = false.
            val bookId = db.bookDao().insert(
                Book(
                    title = "Preview Book",
                    author = "Some Author",
                    description = "A book the owner only browsed.",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extId, remoteId = "preview-book",
                    addedAt = 1L, updatedAt = 1L, inLibrary = false
                )
            )

            // Before: the preview is invisible to every Library
            // surface and holds no favourite.
            assertFalse(db.bookDao().getById(bookId)!!.inLibrary)
            assertTrue(
                db.bookDao().observeShelf().first().none { it.id == bookId }
            )
            assertTrue(container.bookMetaPrefs.favourites.first().isEmpty())

            // The production writer — the book page's heart.
            val vm = BookDetailViewModel(container, bookId)
            vm.setFavourite(true)

            awaitCondition("favourite lands and the preview promotes") {
                runBlocking {
                    db.bookDao().getById(bookId)!!.inLibrary &&
                        bookId in container.bookMetaPrefs.favourites.first()
                }
            }

            // (a) Item 24: the favourite promoted the preview.
            assertTrue(db.bookDao().getById(bookId)!!.inLibrary)

            // (b) The production read mapping: shelf rows +
            // row-id favourites flow → Library facts → the
            // Favourites shelf's membership contains the book,
            // and the chip the rail renders counts it.
            val shelfBooks = db.bookDao().observeShelf().first()
            assertTrue(shelfBooks.any { it.id == bookId })
            val state = LibraryUiState(
                books = shelfBooks.map { ShelfBook(it, 0f) },
                favourites = container.bookMetaPrefs.favourites.first(),
                metas = container.bookMetaPrefs.all.first()
            )
            val facts = state.shelfFacts(System.currentTimeMillis())
            val members = ShelfLogic.builtinMembers(
                ShelfLogic.BUILTIN_FAVOURITES, facts
            )
            assertTrue(
                "the favourited preview must be a Favourites member",
                bookId in members
            )
            val chip = ShelfLogic.chips(
                facts, emptyList(), emptyMap(), emptySet(),
                System.currentTimeMillis()
            ).first { it.ref == ShelfLogic.BUILTIN_FAVOURITES }
            assertEquals(1, chip.count)

            // Unfavouriting removes the mark but NEVER demotes
            // membership (item 24's second half).
            vm.setFavourite(false)
            awaitCondition("unfavourite lands") {
                runBlocking {
                    bookId !in container.bookMetaPrefs.favourites.first()
                }
            }
            assertTrue(db.bookDao().getById(bookId)!!.inLibrary)
        }
}
