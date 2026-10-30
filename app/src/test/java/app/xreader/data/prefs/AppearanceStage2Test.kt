package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.2 Stage 2 appearance rules: the R04 fresh-install default
 * (decided by the main agent under the user's autonomy grant —
 * fresh installs get Electric Violet (v1.0.6.2 F8b — was Violet
 * Night until the final-build plan made the user's own palette
 * the default), upgraders keep their look, via
 * existing-data presence), the three-custom-scheme cap, and the
 * R05/R06/R07 state defaults.
 */
class AppearanceStage2Test {

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test
    fun `fresh install primes to Electric Violet`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = false)
        prefs.primeDefaultScheme()
        assertEquals("Electric violet", prefs.state.first().colourScheme)
    }

    @Test
    fun `established install primes to Ember and never re-skins`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.primeDefaultScheme()
        assertEquals("Ember", prefs.state.first().colourScheme)
    }

    @Test
    fun `priming never overwrites a user choice and is idempotent`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = false)
        prefs.setColourScheme("Ocean")
        prefs.primeDefaultScheme()
        prefs.primeDefaultScheme()
        assertEquals("Ocean", prefs.state.first().colourScheme)
    }

    @Test
    fun `fresh install keeps Electric Violet across a later established run`() = runTest {
        val store = FakeDataStore()
        AppearancePrefs(store, establishedInstall = false).primeDefaultScheme()
        // Second process: the database now exists, so the signal
        // says "established" — the primed value must not flip.
        val second = AppearancePrefs(store, establishedInstall = true)
        second.primeDefaultScheme()
        assertEquals("Electric violet", second.state.first().colourScheme)
    }

    @Test
    fun `custom scheme cap is three for new schemes, replace is free`() {
        val a = CustomSchemeSpec("a", "A", 0xFF000001L)
        val b = CustomSchemeSpec("b", "B", 0xFF000002L)
        val c = CustomSchemeSpec("c", "C", 0xFF000003L)
        val d = CustomSchemeSpec("d", "D", 0xFF000004L)
        val three = listOf(a, b, c)
        assertNull(AppearanceValidation.addCustomScheme(three, d))
        val replaced = AppearanceValidation.addCustomScheme(three, b.copy(name = "B2"))
        assertEquals(3, replaced!!.size)
        assertEquals("B2", replaced.first { it.id == "b" }.name)
        assertEquals(2, AppearanceValidation.addCustomScheme(listOf(a), b)!!.size)
    }

    @Test
    fun `app text size clamps and snaps to five percent steps`() {
        assertEquals(80, AppearanceValidation.clampAppTextSize(10))
        assertEquals(130, AppearanceValidation.clampAppTextSize(500))
        assertEquals(100, AppearanceValidation.clampAppTextSize(102))
        assertEquals(105, AppearanceValidation.clampAppTextSize(103))
        assertEquals(100, AppearanceValidation.clampAppTextSize(100))
    }

    @Test
    fun `stage 2 state defaults follow the plan`() {
        val state = AppearanceState()
        assertEquals(BookColoursScope.BOOK_PAGE_ONLY, state.bookColours)
        assertEquals(PerformanceMode.AUTOMATIC, state.performanceMode)
        assertEquals(false, state.coverGlow)
        assertEquals(100, state.appTextSizePercent)
        assertEquals(false, state.meCardMotionTilt)
        assertTrue("Wallpaper" in AppearanceValidation.COLOUR_SCHEMES)
    }

    @Test
    fun `book colours and performance parse defensively`() {
        assertEquals(
            BookColoursScope.BOOK_PAGE_AND_READER,
            AppearanceValidation.parseBookColours("BOOK_PAGE_AND_READER")
        )
        assertEquals(
            BookColoursScope.BOOK_PAGE_ONLY,
            AppearanceValidation.parseBookColours("junk")
        )
        assertEquals(
            PerformanceMode.SMOOTH,
            AppearanceValidation.parsePerformanceMode("SMOOTH")
        )
        assertEquals(
            PerformanceMode.AUTOMATIC,
            AppearanceValidation.parsePerformanceMode(null)
        )
    }
}
