package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.domain.reader.PageTurnOverride
import app.xreader.domain.reader.ReaderAlignment
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.domain.reader.ChapterTransition
import app.xreader.domain.reader.PagedDirection
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.TapZoneLayout
import app.xreader.domain.reader.ReaderValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single writer/reader of reader preferences (PLAN §1.2 rule),
 * on the shared settings DataStore beside [AppearancePrefs]. Every
 * read passes through [ReaderValidation] — stored values are never
 * trusted (PLAN §1.5).
 */
class ReaderPrefs(private val store: DataStore<Preferences>) {

    val config: Flow<ReaderConfig> = store.data.map { it.toConfig() }

    /** Reset customisation (R07): write the default config back
     * through the same single-writer path (update writes every
     * field, and removes the optional ones at their defaults). */
    suspend fun resetToDefaults() = update { ReaderConfig() }

    suspend fun update(transform: (ReaderConfig) -> ReaderConfig) {
        store.edit { prefs ->
            val next = ReaderValidation.validate(transform(prefs.toConfig()))
            prefs[KEY_MODE] = next.mode.name
            prefs[KEY_IMAGE_MODE] = next.imageMode.name
            prefs[KEY_PALETTE] = next.paletteName
            prefs[KEY_HIGH_CONTRAST] = next.highContrastText
            prefs[KEY_FONT] = next.fontChoice.name
            if (next.userFontName != null) prefs[KEY_USER_FONT] = next.userFontName
            else prefs.remove(KEY_USER_FONT)
            prefs[KEY_FONT_SIZE] = next.fontSizeSp
            prefs[KEY_LINE_HEIGHT] = next.lineHeightMultiplier
            prefs[KEY_PARA_SPACING] = next.paragraphSpacingDp
            prefs[KEY_MARGIN_H] = next.marginHorizontalDp
            prefs[KEY_MARGIN_V] = next.marginVerticalDp
            prefs[KEY_ALIGNMENT] = next.alignment.name
            prefs[KEY_BIONIC] = next.bionicReading
            if (next.brightness != null) prefs[KEY_BRIGHTNESS] = next.brightness
            else prefs.remove(KEY_BRIGHTNESS)
            prefs[KEY_WARM] = next.warmLight
            prefs[KEY_VOLUME_KEYS] = next.volumeKeyTurn
            prefs[KEY_KEEP_ON] = next.keepScreenOn
            prefs[KEY_EINK] = next.eInkMode
            prefs[KEY_TURN_OVERRIDE] = next.turnOverride.name
            prefs[KEY_SPREAD] = next.twoPageSpread
            prefs[KEY_EYE] = next.eyeTracking
            prefs[KEY_SPEECH_RATE] = next.speechRate
            prefs[KEY_AUTO_SCROLL] = next.autoScrollEnabled
            prefs[KEY_AUTO_SCROLL_SPEED] = next.autoScrollSpeed
            prefs[KEY_TAP_ZONES] = next.tapZoneLayout.name
            prefs[KEY_PAGED_DIR] = next.pagedDirection.name
            prefs[KEY_DOUBLE_TAP_ZOOM] = next.doubleTapToZoom
            prefs[KEY_DISABLE_ZOOM_OUT] = next.disableZoomOut
            prefs[KEY_TRANSITION] = next.chapterTransition.name
            prefs[KEY_PAGE_OVERLAY] = next.showPageOverlay
            prefs[KEY_READING_LINE] = next.readingLine
            prefs[KEY_KEYBOARD_PRESET] = next.keyboardPreset.name
            prefs[KEY_CUSTOM_PAGE] = next.customPageArgb.toString()
            prefs[KEY_CUSTOM_TEXT] = next.customTextArgb.toString()
        }
    }

    private fun Preferences.toConfig(): ReaderConfig = ReaderValidation.validate(
        ReaderConfig(
            mode = ReaderValidation.parseEnum(
                this[KEY_MODE], ReaderMode.entries.toTypedArray(), ReaderMode.PAGED
            ),
            imageMode = ReaderValidation.parseEnum(
                this[KEY_IMAGE_MODE],
                app.xreader.domain.reader.ImageReaderMode.entries.toTypedArray(),
                app.xreader.domain.reader.ImageReaderMode.WEBTOON
            ),
            paletteName = this[KEY_PALETTE] ?: "AMOLED",
            highContrastText = this[KEY_HIGH_CONTRAST] ?: false,
            fontChoice = ReaderValidation.parseEnum(
                this[KEY_FONT], ReaderFontChoice.entries.toTypedArray(),
                ReaderFontChoice.LITERATA
            ),
            userFontName = this[KEY_USER_FONT],
            fontSizeSp = this[KEY_FONT_SIZE] ?: 19f,
            lineHeightMultiplier = this[KEY_LINE_HEIGHT] ?: 1.55f,
            paragraphSpacingDp = this[KEY_PARA_SPACING] ?: 10f,
            marginHorizontalDp = this[KEY_MARGIN_H] ?: 24f,
            marginVerticalDp = this[KEY_MARGIN_V] ?: 20f,
            alignment = ReaderValidation.parseEnum(
                this[KEY_ALIGNMENT], ReaderAlignment.entries.toTypedArray(),
                ReaderAlignment.LEFT
            ),
            bionicReading = this[KEY_BIONIC] ?: false,
            brightness = this[KEY_BRIGHTNESS],
            warmLight = this[KEY_WARM] ?: 0f,
            volumeKeyTurn = this[KEY_VOLUME_KEYS] ?: false,
            keepScreenOn = this[KEY_KEEP_ON] ?: true,
            eInkMode = this[KEY_EINK] ?: false,
            turnOverride = ReaderValidation.parseEnum(
                this[KEY_TURN_OVERRIDE], PageTurnOverride.entries.toTypedArray(),
                PageTurnOverride.AUTO
            ),
            twoPageSpread = this[KEY_SPREAD] ?: true,
            eyeTracking = this[KEY_EYE] ?: false,
            speechRate = this[KEY_SPEECH_RATE] ?: 1f,
            autoScrollEnabled = this[KEY_AUTO_SCROLL] ?: false,
            autoScrollSpeed = this[KEY_AUTO_SCROLL_SPEED] ?: 1f,
            tapZoneLayout = ReaderValidation.parseEnum(
                this[KEY_TAP_ZONES], TapZoneLayout.entries.toTypedArray(),
                TapZoneLayout.STANDARD
            ),
            pagedDirection = ReaderValidation.parseEnum(
                this[KEY_PAGED_DIR], PagedDirection.entries.toTypedArray(),
                PagedDirection.LTR
            ),
            doubleTapToZoom = this[KEY_DOUBLE_TAP_ZOOM] ?: true,
            disableZoomOut = this[KEY_DISABLE_ZOOM_OUT] ?: false,
            chapterTransition = ReaderValidation.parseEnum(
                this[KEY_TRANSITION], ChapterTransition.entries.toTypedArray(),
                ChapterTransition.CHAPTER_BY_CHAPTER
            ),
            showPageOverlay = this[KEY_PAGE_OVERLAY] ?: false,
            readingLine = this[KEY_READING_LINE] ?: false,
            keyboardPreset = ReaderValidation.parseEnum(
                this[KEY_KEYBOARD_PRESET],
                app.xreader.domain.reader.KeyboardPreset.entries.toTypedArray(),
                app.xreader.domain.reader.KeyboardPreset.STANDARD
            ),
            customPageArgb = this[KEY_CUSTOM_PAGE]?.toLongOrNull() ?: 0xFF000000L,
            customTextArgb = this[KEY_CUSTOM_TEXT]?.toLongOrNull() ?: 0xFFE8DFD0L
        )
    )

    private companion object {
        val KEY_MODE = stringPreferencesKey("reader.mode")
        val KEY_IMAGE_MODE = stringPreferencesKey("reader.image_mode")
        val KEY_PALETTE = stringPreferencesKey("reader.palette")
        val KEY_HIGH_CONTRAST = booleanPreferencesKey("reader.high_contrast")
        val KEY_FONT = stringPreferencesKey("reader.font")
        val KEY_USER_FONT = stringPreferencesKey("reader.user_font")
        val KEY_FONT_SIZE = floatPreferencesKey("reader.font_size")
        val KEY_LINE_HEIGHT = floatPreferencesKey("reader.line_height")
        val KEY_PARA_SPACING = floatPreferencesKey("reader.paragraph_spacing")
        val KEY_MARGIN_H = floatPreferencesKey("reader.margin_h")
        val KEY_MARGIN_V = floatPreferencesKey("reader.margin_v")
        val KEY_ALIGNMENT = stringPreferencesKey("reader.alignment")
        val KEY_BIONIC = booleanPreferencesKey("reader.bionic")
        val KEY_BRIGHTNESS = floatPreferencesKey("reader.brightness")
        val KEY_WARM = floatPreferencesKey("reader.warm_light")
        val KEY_VOLUME_KEYS = booleanPreferencesKey("reader.volume_keys")
        val KEY_KEEP_ON = booleanPreferencesKey("reader.keep_screen_on")
        val KEY_EINK = booleanPreferencesKey("reader.eink")
        val KEY_TURN_OVERRIDE = stringPreferencesKey("reader.turn_override")
        val KEY_SPREAD = booleanPreferencesKey("reader.two_page_spread")
        val KEY_EYE = booleanPreferencesKey("reader.eye_tracking")
        val KEY_SPEECH_RATE = floatPreferencesKey("reader.speech_rate")
        val KEY_AUTO_SCROLL = booleanPreferencesKey("reader.auto_scroll")
        val KEY_AUTO_SCROLL_SPEED = floatPreferencesKey("reader.auto_scroll_speed")
        val KEY_TAP_ZONES = stringPreferencesKey("reader.tap_zones")
        val KEY_PAGED_DIR = stringPreferencesKey("reader.paged_direction")
        val KEY_DOUBLE_TAP_ZOOM = booleanPreferencesKey("reader.double_tap_zoom")
        val KEY_DISABLE_ZOOM_OUT = booleanPreferencesKey("reader.disable_zoom_out")
        val KEY_TRANSITION = stringPreferencesKey("reader.chapter_transition")
        val KEY_PAGE_OVERLAY = booleanPreferencesKey("reader.page_overlay")
        val KEY_READING_LINE = booleanPreferencesKey("reader.reading_line")
        val KEY_KEYBOARD_PRESET = stringPreferencesKey("reader.keyboard_preset")
        val KEY_CUSTOM_PAGE = stringPreferencesKey("reader.custom_page")
        val KEY_CUSTOM_TEXT = stringPreferencesKey("reader.custom_text")
    }
}
