package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App size (v1.6.2): the ONE decimal that replaced the R07
 * text-size %. Pins the clamp (the floor runs below the old
 * 80% — the owner's "80% can't do it below"), the 0.05 snap
 * (a snapped preset is bit-identical to its chip constant),
 * the one-time migration from a stored text-size %, the
 * plain-decimal format, and the prefs round-trip through a
 * fake DataStore (the NocturneFlipTest harness shape).
 */
class AppSizeTest {

    private class FakeDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    // ── Clamp ────────────────────────────────────────────────

    @Test
    fun `app size clamps to its range`() {
        assertEquals(0.70f, AppearanceValidation.clampAppSize(0.69f), 0.0f)
        assertEquals(0.70f, AppearanceValidation.clampAppSize(0.0f), 0.0f)
        assertEquals(1.30f, AppearanceValidation.clampAppSize(1.31f), 0.0f)
        assertEquals(1.30f, AppearanceValidation.clampAppSize(9.0f), 0.0f)
        assertEquals(1.0f, AppearanceValidation.clampAppSize(1.0f), 0.0f)
        assertEquals(
            AppearanceValidation.DEFAULT_APP_SIZE,
            AppearanceValidation.clampAppSize(Float.NaN),
            0.0f
        )
    }

    // ── Snap ─────────────────────────────────────────────────

    @Test
    fun `app size snaps to five-hundredth steps and presets are exact`() {
        // The chips set these constants; a value written from
        // the slider must land bit-identical on them or the
        // chip could never show selected.
        assertEquals(
            AppearanceValidation.APP_SIZE_SMALL,
            AppearanceValidation.snapAppSize(0.85f),
            0.0f
        )
        assertEquals(
            AppearanceValidation.APP_SIZE_NORMAL,
            AppearanceValidation.snapAppSize(1.0f),
            0.0f
        )
        assertEquals(
            AppearanceValidation.APP_SIZE_LARGE,
            AppearanceValidation.snapAppSize(1.15f),
            0.0f
        )
        assertEquals(0.85f, AppearanceValidation.snapAppSize(0.87f), 0.0f)
        assertEquals(0.90f, AppearanceValidation.snapAppSize(0.88f), 0.0f)
        assertEquals(0.70f, AppearanceValidation.snapAppSize(0.71f), 0.0f)
        assertEquals(1.30f, AppearanceValidation.snapAppSize(1.29f), 0.0f)
        // Out-of-range input clamps first, then snaps.
        assertEquals(0.70f, AppearanceValidation.snapAppSize(0.40f), 0.0f)
        assertEquals(1.30f, AppearanceValidation.snapAppSize(2.50f), 0.0f)
    }

    // ── Migration (pure) ─────────────────────────────────────

    @Test
    fun `migration maps the legacy percent onto the decimal`() {
        assertEquals(
            1.00f,
            AppearanceValidation.resolveAppSize(null, 100),
            0.0f
        )
        assertEquals(
            0.80f,
            AppearanceValidation.resolveAppSize(null, 80),
            0.0f
        )
        assertEquals(
            1.30f,
            AppearanceValidation.resolveAppSize(null, 130),
            0.0f
        )
        assertEquals(
            0.85f,
            AppearanceValidation.resolveAppSize(null, 85),
            0.0f
        )
        // A % outside the old range is judged by the old clamp
        // first (500% was never a storable read; it read as 130).
        assertEquals(
            1.30f,
            AppearanceValidation.resolveAppSize(null, 500),
            0.0f
        )
        assertEquals(
            0.80f,
            AppearanceValidation.resolveAppSize(null, 10),
            0.0f
        )
    }

    @Test
    fun `stored app size wins over the legacy percent and nothing stored is normal`() {
        assertEquals(
            0.90f,
            AppearanceValidation.resolveAppSize(0.90f, 130),
            0.0f
        )
        assertEquals(
            1.30f,
            AppearanceValidation.resolveAppSize(9.0f, null),
            0.0f
        )
        assertEquals(
            1.00f,
            AppearanceValidation.resolveAppSize(null, null),
            0.0f
        )
    }

    // ── Format ───────────────────────────────────────────────

    @Test
    fun `app size formats as a plain decimal`() {
        assertEquals("0.70", AppearanceValidation.formatAppSize(0.70f))
        assertEquals("0.85", AppearanceValidation.formatAppSize(0.85f))
        assertEquals("1.00", AppearanceValidation.formatAppSize(1.0f))
        assertEquals("1.15", AppearanceValidation.formatAppSize(1.15f))
        assertEquals("1.30", AppearanceValidation.formatAppSize(1.30f))
    }

    // ── Prefs round-trip + migration over the store ─────────

    @Test
    fun `fresh install reads normal and the writer round-trips snapped`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        assertEquals(1.0f, prefs.state.first().appSize, 0.0f)
        prefs.setAppSize(0.85f)
        assertEquals(0.85f, prefs.state.first().appSize, 0.0f)
        // The writer snaps: a between-steps value lands on a step.
        prefs.setAppSize(0.87f)
        assertEquals(0.85f, prefs.state.first().appSize, 0.0f)
        prefs.setAppSize(1.30f)
        assertEquals(1.30f, prefs.state.first().appSize, 0.0f)
    }

    @Test
    fun `a stored legacy percent seeds the app size on read`() = runTest {
        val textKey = intPreferencesKey("appearance.app_text_size")
        val at130 = AppearancePrefs(
            FakeDataStore(preferencesOf(textKey to 130)),
            establishedInstall = true
        )
        assertEquals(1.30f, at130.state.first().appSize, 0.0f)
        val at80 = AppearancePrefs(
            FakeDataStore(preferencesOf(textKey to 80)),
            establishedInstall = true
        )
        assertEquals(0.80f, at80.state.first().appSize, 0.0f)
        // The owner's test position: he turned text size DOWN
        // hunting the layout — an 80% phone must open at 0.80,
        // not silently at 1.00.
        val at100 = AppearancePrefs(
            FakeDataStore(preferencesOf(textKey to 100)),
            establishedInstall = true
        )
        assertEquals(1.00f, at100.state.first().appSize, 0.0f)
    }

    @Test
    fun `the first app size write ends the migration`() = runTest {
        val textKey = intPreferencesKey("appearance.app_text_size")
        val prefs = AppearancePrefs(
            FakeDataStore(preferencesOf(textKey to 130)),
            establishedInstall = true
        )
        assertEquals(1.30f, prefs.state.first().appSize, 0.0f)
        prefs.setAppSize(1.0f)
        assertEquals(1.0f, prefs.state.first().appSize, 0.0f)
    }

    @Test
    fun `a stored app size beats a stored legacy percent`() = runTest {
        val prefs = AppearancePrefs(
            FakeDataStore(
                preferencesOf(
                    floatPreferencesKey("appearance.app_size") to 0.90f,
                    intPreferencesKey("appearance.app_text_size") to 130
                )
            ),
            establishedInstall = true
        )
        assertEquals(0.90f, prefs.state.first().appSize, 0.0f)
    }

    @Test
    fun `reset returns the app size to normal and forgets the legacy percent`() =
        runTest {
            val textKey = intPreferencesKey("appearance.app_text_size")
            val prefs = AppearancePrefs(
                FakeDataStore(preferencesOf(textKey to 130)),
                establishedInstall = true
            )
            assertEquals(1.30f, prefs.state.first().appSize, 0.0f)
            prefs.resetCustomisation()
            // Both keys are gone: no migrated 1.30 resurrection.
            assertEquals(1.0f, prefs.state.first().appSize, 0.0f)
        }
}
