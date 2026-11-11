package app.xreader.ui.localreader

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.8 (G4 + G7): the one-tap day/night toggle flips the
 * existing theme pref DARK ⇄ last light preset (Paper by
 * default), live-applied through the same submission the sheet
 * uses; the reading position is never part of the transform.
 * G7: each named preset submits its declared colour pair, and
 * fresh prefs default to AMOLED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158DayNightToggleTest {

    @get:Rule val temp = TemporaryFolder()

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
    fun `toggle from AMOLED dark lands on Paper, and back`() {
        val dark = EpubBookSettings()
        assertEquals(EpubThemeChoice.DARK, dark.theme)
        val light = dark.toggleDayNight()
        assertEquals(EpubThemePreset.PAPER.theme, light.theme)
        assertEquals(EpubThemePreset.PAPER.textColorArgb, light.textColorArgb)
        assertEquals(EpubThemePreset.PAPER.backgroundColorArgb, light.backgroundColorArgb)
        val backAgain = light.toggleDayNight()
        assertEquals(EpubThemePreset.AMOLED.theme, backAgain.theme)
        assertEquals(EpubThemePreset.AMOLED.backgroundColorArgb, backAgain.backgroundColorArgb)
    }

    @Test
    fun `toggle remembers the last light and last dark presets`() {
        val onSepia = EpubBookSettings().applyPreset(EpubThemePreset.SEPIA)
        val toNight = onSepia.applyPreset(EpubThemePreset.NIGHT)
        // From Night (dark), the toggle returns to SEPIA (the
        // last light used), not the Paper default.
        assertEquals(
            EpubThemePreset.SEPIA,
            toNight.toggleDayNight().currentPreset()
        )
        // And from Sepia it returns to NIGHT (the last dark).
        assertEquals(
            EpubThemePreset.NIGHT,
            toNight.toggleDayNight().toggleDayNight().currentPreset()
        )
    }

    @Test
    fun `toggle touches only theme fields - position inputs unchanged`() {
        val settings = EpubBookSettings(
            fontFamilyName = "SERIF",
            fontSize = 1.35,
            scroll = false,
            pageMargins = 1.4
        )
        val toggled = settings.toggleDayNight()
        assertEquals(settings.fontFamilyName, toggled.fontFamilyName)
        assertEquals(settings.fontSize, toggled.fontSize, 0.0)
        assertEquals(settings.scroll, toggled.scroll)
        assertEquals(settings.pageMargins, toggled.pageMargins, 0.0)
    }

    @Test
    fun `toggle persists through the same keys the sheet writes`() = runTest {
        val store = FakeDataStore()
        val prefs = LocalReaderPrefs(store)
        prefs.saveEpub(3L, EpubBookSettings().toggleDayNight())
        val reread = prefs.epubSettings(3L).first()
        assertEquals(EpubThemePreset.PAPER, reread.currentPreset())
    }
}

/** G7's submission-level assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158ThemePresetTest {

    @get:Rule val temp = TemporaryFolder()

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
    fun `each preset submits its declared colour pair`() = runBlocking {
        val root = temp.newFolder("files")
        val session = LocalReaderEpubFixture.openSession(
            root, LocalReaderEpubFixture.writeEpub(root)
        )
        try {
            for (preset in EpubThemePreset.entries) {
                val submitted = EpubBookSettings()
                    .applyPreset(preset)
                    .toEpubPreferences(session)
                assertEquals(
                    "preset $preset theme",
                    when (preset.theme) {
                        EpubThemeChoice.LIGHT ->
                            org.readium.r2.navigator.preferences.Theme.LIGHT
                        EpubThemeChoice.DARK ->
                            org.readium.r2.navigator.preferences.Theme.DARK
                        EpubThemeChoice.SEPIA ->
                            org.readium.r2.navigator.preferences.Theme.SEPIA
                    },
                    submitted.theme
                )
                assertEquals(
                    "preset $preset text colour",
                    preset.textColorArgb?.let {
                        org.readium.r2.navigator.preferences.Color(it)
                    },
                    submitted.textColor
                )
                assertEquals(
                    "preset $preset page colour",
                    preset.backgroundColorArgb?.let {
                        org.readium.r2.navigator.preferences.Color(it)
                    },
                    submitted.backgroundColor
                )
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `fresh default is AMOLED`() = runTest {
        val settings = LocalReaderPrefs(FakeDataStore()).epubSettings(1L).first()
        assertEquals(EpubThemePreset.AMOLED, settings.currentPreset())
        assertEquals(EpubThemeChoice.DARK, settings.theme)
    }
}
