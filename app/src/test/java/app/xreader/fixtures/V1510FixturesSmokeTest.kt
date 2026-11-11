package app.xreader.fixtures

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.10 (L-FIX): the fixture module's own smoke test —
 * proves the shelf builds with every A4 element in place, the
 * D3 book opens through the real Readium session seam, the
 * I4 hang fixtures actually hang (with probes readable), and
 * the B5 re-keyed shelf carries the stale pointer + its
 * negative twin. If this class is red, every lane's gate
 * evidence built on the module is void.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1510FixturesSmokeTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `legacy shelf builds with every A4 element`() = runBlocking {
        val shelf = LegacyShelf.build(RuntimeEnvironment.getApplication())
        val db = shelf.db
        try {
            val extDao = db.installedExtensionDao()
            val bookDao = db.bookDao()
            val chapterDao = db.chapterDao()

            // 3 bundled + 5 Keiyoushi rows.
            assertEquals(8, extDao.allNow().size)

            // Keiyoushi instance counts: 42 and 7.
            val ball = extDao.getById(LegacyShelf.EXT_MANGABALL)!!
            assertEquals(42, ball.sourceIds.split(",").size)
            val fire = extDao.getById(LegacyShelf.EXT_MANGAFIRE)!!
            assertEquals(
                LegacyShelf.MANGAFIRE_SOURCE_IDS,
                fire.sourceIds.split(",")
            )

            // Epoch-0: books unstamped; BrightNovel's stored
            // grant is the stale singular-host leaf blob.
            val dullard = bookDao.getById(
                LegacyShelf.BOOK_BRIGHTNOVEL_LEGACY
            )!!
            assertEquals(0, dullard.syncedEpoch)
            assertEquals(
                LegacyShelf.BRIGHTNOVEL_LEGACY_URL, dullard.remoteId
            )
            val bright = extDao.getById(LegacyShelf.EXT_BRIGHTNOVEL)!!
            assertTrue(bright.permissionsJson.contains("brightnovel.com"))
            assertFalse(bright.permissionsJson.contains("brightnovels.com"))

            // The long books carry their full chapter rows.
            assertEquals(
                LegacyShelf.LONG_BOOK_1200_CHAPTERS,
                chapterDao.forBook(LegacyShelf.BOOK_LONG_1200).size
            )
            assertEquals(
                LegacyShelf.LONG_BOOK_1345_CHAPTERS,
                chapterDao.forBook(LegacyShelf.BOOK_LONG_1345).size
            )

            // Journal + heal flag at versionCode 50.
            val journal = db.updatePassJournalDao()
                .forVersion(LegacyShelf.JOURNAL_VERSION_CODE)
            assertTrue(
                journal.any {
                    it.providerKey == "__phase0__" &&
                        it.outcome == "complete"
                }
            )
            assertEquals(
                LegacyShelf.JOURNAL_VERSION_CODE,
                SourcePrefs(shelf.sourcePrefsStore)
                    .healSweepDoneForCode.first()
            )

            // The EPUB book's pre-1.5.9 snapshot, RAW: DARKEN +
            // scroll=false, and neither migration marker.
            val settings = shelf.settingsStore.snapshot()
            assertEquals(
                "DARKEN",
                settings[stringPreferencesKey(
                    "localreader.${LegacyShelf.BOOK_EPUB_D3}.epub.plate_filter"
                )]
            )
            assertEquals(
                false,
                settings[booleanPreferencesKey(
                    "localreader.${LegacyShelf.BOOK_EPUB_D3}.epub.scroll"
                )]
            )
            assertNull(
                settings[intPreferencesKey(
                    "localreader.epub.scrollDefaultVersion"
                )]
            )
            assertNull(
                settings[intPreferencesKey(
                    "localreader.epub.plateFilterVersion"
                )]
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `d3 epub opens through the real readium session`() = runBlocking {
        val root = temp.newFolder("d3root")
        val epub = D3EpubFixture.writeBook(root)
        assertTrue(epub.isFile)
        assertEquals(D3EpubFixture.SPINE.size, 7)
        val session = D3EpubFixture.openSession(root, epub)
        try {
            assertEquals(D3EpubFixture.SPINE.size, session.sectionCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun `hang fixtures hang with probes readable`() = runTest {
        val chapters = HangChapterFetchSource()
        val pages = HangPageListSource()
        val catalogue = HangCatalogueSource()

        val chapterJob = async { chapters.fetchChapters("book-1") }
        val komikkuJob = async { pages.fetchPages("chapter-1") }
        val novelJob = async { pages.fetchChapterText("chapter-1") }
        val catalogueJob = async { catalogue.fetchCatalogue(1) }
        advanceUntilIdle()

        // A bounded virtual wait changes nothing: the calls
        // are parked on deferreds, not delays.
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertTrue(chapterJob.isActive)
        assertTrue(komikkuJob.isActive)
        assertTrue(novelJob.isActive)
        assertTrue(catalogueJob.isActive)
        for (gate in listOf(
            chapters.gate, pages.komikkuGate, catalogue.gate
        )) {
            assertTrue(gate.wasStarted)
            assertTrue(gate.isAlive)
            assertEquals(1, gate.walkCount)
            assertFalse(gate.deferred.isCompleted)
        }
        assertTrue(pages.novelGate.wasStarted)
        assertTrue(pages.novelGate.isAlive)
        assertEquals(1, pages.novelGate.walkCount)

        // The test-only release lands the result on the SAME
        // call — walk count stays exactly 1.
        chapters.gate.release(listOf("chapter-1", "chapter-2"))
        assertEquals(
            listOf("chapter-1", "chapter-2"), chapterJob.await()
        )
        assertEquals(1, chapters.gate.walkCount)
        assertFalse(chapters.gate.isAlive)

        chapterJob.cancel(); komikkuJob.cancel()
        novelJob.cancel(); catalogueJob.cancel()
    }

    @Test
    fun `rekeyed shelf carries the stale pointer and its negative twin`() =
        runBlocking {
            val built = reKeyedExtensionShelf(
                RuntimeEnvironment.getApplication()
            )
            val db = built.db
            try {
                val extDao = db.installedExtensionDao()
                val bookDao = db.bookDao()

                // The accident really happened: old row gone,
                // same packageId alive under a NEW id.
                assertNotEquals(
                    built.oldExtensionId, built.newExtensionId
                )
                assertNull(extDao.getById(built.oldExtensionId))
                val reinserted = extDao.getByPackageId(
                    ReKeyedShelf.PACKAGE_ID
                )!!
                assertEquals(built.newExtensionId, reinserted.id)
                assertEquals(
                    ReKeyedShelf.SOURCE_ID, reinserted.sourceIds
                )

                // The book still points at the DELETED row; its
                // remoteId decodes to the declared sourceId.
                val book = bookDao.getById(ReKeyedShelf.BOOK_ID)!!
                assertEquals(built.oldExtensionId, book.extensionId)
                assertEquals(
                    ReKeyedShelf.SOURCE_ID to ReKeyedShelf.BOOK_REMOTE_ID,
                    SourceBookIds.decode(book.remoteId)
                )
                assertTrue(
                    db.chapterDao().forBook(ReKeyedShelf.BOOK_ID)
                        .isNotEmpty()
                )
                // Exactly one installed row declares the source.
                assertEquals(
                    listOf(built.newExtensionId),
                    extDao.allNow().filter {
                        it.sourceIds.split(",")
                            .contains(ReKeyedShelf.SOURCE_ID)
                    }.map { it.id }
                )

                // Addendum 2: the WHOLE set is stale at once —
                // at least 3 distinct packages, every book
                // pointing at its own package's deleted row,
                // every package alive under a NEW id.
                assertTrue(built.reKeyed.size >= 3)
                assertEquals(
                    built.reKeyed.size,
                    built.reKeyed.map { it.spec.packageId }.distinct().size
                )
                for (entry in built.reKeyed) {
                    assertNotEquals(
                        entry.oldExtensionId, entry.newExtensionId
                    )
                    assertNull(extDao.getById(entry.oldExtensionId))
                    assertEquals(
                        entry.newExtensionId,
                        extDao.getByPackageId(entry.spec.packageId)!!.id
                    )
                    val stale = bookDao.getById(entry.spec.bookId)!!
                    assertEquals(
                        entry.oldExtensionId, stale.extensionId
                    )
                    assertEquals(
                        entry.spec.sourceId,
                        SourceBookIds.decode(stale.remoteId)?.first
                    )
                    assertTrue(
                        db.chapterDao().forBook(entry.spec.bookId)
                            .isNotEmpty()
                    )
                }

                // Negative twin: pointer at a deleted row, and
                // NO installed row declares its sourceId.
                assertNull(extDao.getById(built.orphanExtensionId))
                assertNull(
                    extDao.getByPackageId(ReKeyedShelf.ORPHAN_PACKAGE_ID)
                )
                val orphan = bookDao.getById(
                    ReKeyedShelf.ORPHAN_BOOK_ID
                )!!
                assertEquals(
                    built.orphanExtensionId, orphan.extensionId
                )
                assertEquals(
                    ReKeyedShelf.ORPHAN_SOURCE_ID,
                    SourceBookIds.decode(orphan.remoteId)?.first
                )
                assertTrue(
                    extDao.allNow().none {
                        it.sourceIds.split(",")
                            .contains(ReKeyedShelf.ORPHAN_SOURCE_ID)
                    }
                )
            } finally {
                db.close()
            }
        }
}
