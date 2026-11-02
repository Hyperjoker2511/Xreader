package app.xreader.data.db

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import app.xreader.di.buildXreaderDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.4.2 regression test — behavioural proof for the v1.4.1
 * first-open crash, and the test gap that let it ship.
 *
 * The crash (user's phone, real trace): v1.4.1 pinned
 * `BundledSQLiteDriver()` on the production database builder. A
 * configured Room driver suppresses the default
 * SupportSQLiteOpenHelper factory, so the FIRST
 * [androidx.room.withTransaction] after the update threw
 * IllegalStateException ("Cannot return a SupportSQLiteOpenHelper
 * since no SupportSQLiteOpenHelper.Factory was configured with
 * Room") — lazily, at the transaction, not at `build()`.
 *
 * No JVM test caught it because no JVM test built a Room database
 * at all: SchemaSmokeTest compares the KSP-exported schema JSON on
 * disk, and MigrationTest covers the import parsers. This test
 * closes that gap at the production seam: it builds the database
 * through [buildXreaderDatabase] — the exact factory AppContainer
 * uses — opens it, and runs a withTransaction write + read.
 *
 * It is a Robolectric test because plain JVM cannot run Room's
 * Android builder (a real Context and the framework SQLite are
 * required). Coverage is stated honestly: this proves the builder
 * configuration under Robolectric's Android runtime on the JVM —
 * not on a physical device.
 *
 * The plain [Application] override keeps the test about the
 * database: XreaderApp.onCreate would construct the whole
 * AppContainer (engines, prefs, WorkManager) inside the test,
 * none of which the factory path depends on.
 *
 * sdk 35 is pinned because that is the newest runtime the
 * Robolectric version on the test classpath supports (the app
 * targets 36). The configuration under test — builder shape and
 * the transaction bridge — is not SDK-level behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProductionDatabaseTest {

    @Test
    fun `production factory database survives a withTransaction write and read`() {
        val context: Context = RuntimeEnvironment.getApplication()
        // Isolate from any earlier sandbox state: the factory builds
        // the real named database file, exactly as production does.
        context.deleteDatabase(XreaderDatabase.NAME)
        val db = buildXreaderDatabase(context)
        try {
            val written = Tag(
                slug = "production-factory-probe",
                displayName = "Production Factory Probe",
                createdAt = 1L
            )
            val readBack = runBlocking {
                db.withTransaction {
                    db.tagDao().insertTag(written)
                    db.tagDao().tag(written.slug)
                }
            }
            assertEquals(written, readBack)
        } finally {
            db.close()
        }
    }
}
