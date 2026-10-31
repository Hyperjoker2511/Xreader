package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.ui.theme.AppColourThemes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.0 (the Nocturne flip, design §2 + §3): the validation
 * landmine fix, the one-time v2 migration (snapshot first, then
 * presets move, custom/Wallpaper preserved), the book-page pin's
 * lifetime (cleared only by an explicit scheme choice), and the
 * Frosted glass default (user answer Q5).
 */
class NocturneFlipTest {

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

    // ── The landmine (research §5.2): Nocturne must STORE ─────

    @Test
    fun `nocturne violet is registered and round-trips un-coerced`() {
        assertTrue("Nocturne violet" in AppearanceValidation.COLOUR_SCHEMES)
        assertEquals(
            "Nocturne violet",
            AppearanceValidation.parseColourScheme("Nocturne violet")
        )
    }

    @Test
    fun `every picker name validates`() {
        // AppearanceR04Test's invariant, extended to the new
        // picker: picker ⊆ validation, or a listed theme could
        // never be stored.
        AppColourThemes.forEach { scheme ->
            assertTrue(scheme.name, scheme.name in AppearanceValidation.COLOUR_SCHEMES)
            assertEquals(
                scheme.name,
                AppearanceValidation.parseColourScheme(scheme.name)
            )
        }
        assertEquals("Nocturne violet", AppColourThemes.first().name)
        assertEquals(
            AppearanceValidation.FRESH_INSTALL_SCHEME,
            "Nocturne violet"
        )
    }

    // ── Migration (design §2.3) ───────────────────────────────

    @Test
    fun `preset stored users migrate to nocturne with the old value snapshotted`() = runTest {
        listOf("Ember", "Ocean", "Electric violet", "Magenta violet").forEach { preset ->
            val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
            prefs.setColourScheme(preset)
            prefs.migrateToNocturneV2()
            val state = prefs.state.first()
            assertEquals("stored $preset", "Nocturne violet", state.colourScheme)
            assertEquals("stored $preset", preset, state.bookDetailScheme)
        }
    }

    @Test
    fun `custom scheme user is preserved and snapshotted`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.setCustomSchemes(
            listOf(CustomSchemeSpec("c1", "My violet", 0xFF7C5CD6L))
        )
        prefs.setColourScheme("custom:c1")
        prefs.migrateToNocturneV2()
        val state = prefs.state.first()
        assertEquals("custom:c1", state.colourScheme)
        assertEquals("custom:c1", state.bookDetailScheme)
    }

    @Test
    fun `wallpaper user is preserved and snapshotted`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.setColourScheme("Wallpaper")
        prefs.migrateToNocturneV2()
        val state = prefs.state.first()
        assertEquals("Wallpaper", state.colourScheme)
        assertEquals("Wallpaper", state.bookDetailScheme)
    }

    @Test
    fun `already nocturne user stays nocturne and snapshots it`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.setColourScheme("Nocturne violet")
        prefs.migrateToNocturneV2()
        val state = prefs.state.first()
        assertEquals("Nocturne violet", state.colourScheme)
        assertEquals("Nocturne violet", state.bookDetailScheme)
    }

    @Test
    fun `fresh install gets nocturne and no snapshot`() = runTest {
        // The AppContainer order: migration BEFORE priming, so
        // the empty slot is never snapshotted.
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = false)
        prefs.migrateToNocturneV2()
        prefs.primeDefaultScheme()
        val state = prefs.state.first()
        assertEquals("Nocturne violet", state.colourScheme)
        assertNull(state.bookDetailScheme)
    }

    @Test
    fun `migration on an empty store writes no snapshot`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.migrateToNocturneV2()
        assertNull(prefs.state.first().bookDetailScheme)
    }

    @Test
    fun `migration second run is a no-op`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.setColourScheme("Ocean")
        prefs.migrateToNocturneV2()
        // The user then deliberately re-chooses Ocean (which
        // clears the pin); a later launch must NOT resurrect
        // the migration: no re-snapshot, no re-flip.
        prefs.setColourScheme("Ocean")
        prefs.migrateToNocturneV2()
        val state = prefs.state.first()
        assertEquals("Ocean", state.colourScheme)
        assertNull(state.bookDetailScheme)
    }

    // ── Pin lifetime (design §3.2) ────────────────────────────

    @Test
    fun `explicit scheme choice clears the book detail pin`() = runTest {
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        prefs.setColourScheme("Ocean")
        prefs.migrateToNocturneV2()
        assertEquals("Ocean", prefs.state.first().bookDetailScheme)
        prefs.setColourScheme("Deep indigo")
        val state = prefs.state.first()
        assertEquals("Deep indigo", state.colourScheme)
        assertNull(state.bookDetailScheme)
    }

    // ── Glass default (user answer Q5) ────────────────────────

    @Test
    fun `glass defaults to frosted and explicit choices keep winning`() = runTest {
        assertEquals(GlassStyle.FROSTED, AppearanceState().glassStyle)
        assertEquals(GlassStyle.FROSTED, AppearanceValidation.parseGlassStyle(null))
        assertEquals(GlassStyle.FROSTED, AppearanceValidation.parseGlassStyle(""))
        assertEquals(GlassStyle.TINTED, AppearanceValidation.parseGlassStyle("TINTED"))
        assertEquals(GlassStyle.SOLID, AppearanceValidation.parseGlassStyle("SOLID"))
        // An unset stored choice resolves FROSTED through the
        // prefs read, end to end.
        val prefs = AppearancePrefs(FakeDataStore(), establishedInstall = true)
        assertEquals(GlassStyle.FROSTED, prefs.state.first().glassStyle)
        // An explicitly stored TINTED still wins over the default.
        prefs.setGlassStyle(GlassStyle.TINTED)
        assertEquals(GlassStyle.TINTED, prefs.state.first().glassStyle)
    }
}
