package app.xreader.data.sync

import app.xreader.domain.reader.PageTurnOverride
import app.xreader.domain.reader.ReaderAlignment
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.ReaderValidation

/**
 * Reader preferences ⇄ the string map the sync payload carries
 * (PLAN §7: reader preferences sync). Device-specific fields are
 * deliberately NOT synced: brightness (this screen), the imported
 * font's file name (the file itself doesn't travel), and the Labs
 * eye-tracking toggle (a per-device experiment).
 */
object ReaderPrefsCodec {

    fun encode(config: ReaderConfig): Map<String, String> = mapOf(
        "mode" to config.mode.name,
        "palette" to config.paletteName,
        "highContrast" to config.highContrastText.toString(),
        "font" to config.fontChoice.name,
        "fontSize" to config.fontSizeSp.toString(),
        "lineHeight" to config.lineHeightMultiplier.toString(),
        "paragraphSpacing" to config.paragraphSpacingDp.toString(),
        "marginH" to config.marginHorizontalDp.toString(),
        "marginV" to config.marginVerticalDp.toString(),
        "alignment" to config.alignment.name,
        "bionic" to config.bionicReading.toString(),
        "warmLight" to config.warmLight.toString(),
        "volumeKeys" to config.volumeKeyTurn.toString(),
        "keepScreenOn" to config.keepScreenOn.toString(),
        "eInk" to config.eInkMode.toString(),
        "turnOverride" to config.turnOverride.name,
        "autoScroll" to config.autoScrollEnabled.toString(),
        "autoScrollSpeed" to config.autoScrollSpeed.toString(),
        "tapZones" to config.tapZoneLayout.name,
        "pagedDirection" to config.pagedDirection.name,
        "chapterTransition" to config.chapterTransition.name,
        "pageOverlay" to config.showPageOverlay.toString(),
        "customPage" to config.customPageArgb.toString(),
        "customText" to config.customTextArgb.toString(),
        "twoPageSpread" to config.twoPageSpread.toString(),
        "speechRate" to config.speechRate.toString()
    )

    /** Defensive decode (§1.5): every field falls back to the current
     * value when absent or unparseable; the result is validated. */
    fun decode(map: Map<String, String>, current: ReaderConfig): ReaderConfig {
        if (map.isEmpty()) return current
        fun float(key: String, fallback: Float): Float =
            map[key]?.toFloatOrNull() ?: fallback
        fun bool(key: String, fallback: Boolean): Boolean =
            map[key]?.toBooleanStrictOrNull() ?: fallback
        return ReaderValidation.validate(
            current.copy(
                mode = ReaderValidation.parseEnum(
                    map["mode"], ReaderMode.entries.toTypedArray(), current.mode
                ),
                paletteName = map["palette"] ?: current.paletteName,
                highContrastText = bool("highContrast", current.highContrastText),
                autoScrollEnabled = bool("autoScroll", current.autoScrollEnabled),
                autoScrollSpeed = float("autoScrollSpeed", current.autoScrollSpeed),
                tapZoneLayout = ReaderValidation.parseEnum(
                    map["tapZones"],
                    app.xreader.domain.reader.TapZoneLayout.entries.toTypedArray(),
                    current.tapZoneLayout
                ),
                pagedDirection = ReaderValidation.parseEnum(
                    map["pagedDirection"],
                    app.xreader.domain.reader.PagedDirection.entries.toTypedArray(),
                    current.pagedDirection
                ),
                chapterTransition = ReaderValidation.parseEnum(
                    map["chapterTransition"],
                    app.xreader.domain.reader.ChapterTransition.entries.toTypedArray(),
                    current.chapterTransition
                ),
                showPageOverlay = bool("pageOverlay", current.showPageOverlay),
                customPageArgb = map["customPage"]?.toLongOrNull()
                    ?: current.customPageArgb,
                customTextArgb = map["customText"]?.toLongOrNull()
                    ?: current.customTextArgb,
                fontChoice = ReaderValidation.parseEnum(
                    map["font"], ReaderFontChoice.entries.toTypedArray(), current.fontChoice
                ),
                fontSizeSp = float("fontSize", current.fontSizeSp),
                lineHeightMultiplier = float("lineHeight", current.lineHeightMultiplier),
                paragraphSpacingDp = float("paragraphSpacing", current.paragraphSpacingDp),
                marginHorizontalDp = float("marginH", current.marginHorizontalDp),
                marginVerticalDp = float("marginV", current.marginVerticalDp),
                alignment = ReaderValidation.parseEnum(
                    map["alignment"], ReaderAlignment.entries.toTypedArray(),
                    current.alignment
                ),
                bionicReading = bool("bionic", current.bionicReading),
                warmLight = float("warmLight", current.warmLight),
                volumeKeyTurn = bool("volumeKeys", current.volumeKeyTurn),
                keepScreenOn = bool("keepScreenOn", current.keepScreenOn),
                eInkMode = bool("eInk", current.eInkMode),
                turnOverride = ReaderValidation.parseEnum(
                    map["turnOverride"], PageTurnOverride.entries.toTypedArray(),
                    current.turnOverride
                ),
                twoPageSpread = bool("twoPageSpread", current.twoPageSpread),
                speechRate = float("speechRate", current.speechRate)
            )
        )
    }
}
