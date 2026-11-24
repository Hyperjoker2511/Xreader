package app.xreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.AppDesign
import app.xreader.data.prefs.DesignState
import app.xreader.data.prefs.M3ThemeMode

/**
 * Code 120 (Material 3 design build): the locals deep
 * surfaces read instead of threading state through every
 * signature.
 *
 * [LocalDesignState] carries the active design's state
 * (defaults to the Nocturne state, so any surface composed
 * outside the themed root — previews, the Komikku tree —
 * reads Nocturne).
 *
 * [LocalSelectionTint] is recheck erratum E1's binding: the
 * selection tint and handles follow the ACTIVE design's
 * scheme. Its default is the shipped ReaderViolet constant's
 * value, so under Nocturne — where nothing provides it —
 * every selection surface renders byte-identically to before
 * this build. The Material 3 theme (EditorialThemeRoot)
 * provides the scheme's `primary` role.
 *
 * Code 124: the removed MATERIAL3 design's own theme root
 * (M3ThemeRoot) and colour resolution are gone with the
 * face. The surviving Material 3 design — the face built as
 * Editorial in code 123 — resolves its colour in
 * EditorialTheme.kt from the same shared colour state this
 * file's helpers serve.
 */
val LocalDesignState = compositionLocalOf { DesignState() }

val LocalSelectionTint = compositionLocalOf { Color(0xFF8B5CF6) }

/** The Material 3 design's dark/light decision (§2.11: its
 * own mode, default Dark — Nocturne's theme mode is never
 * consulted). */
@Composable
fun m3DarkTheme(design: DesignState): Boolean = when (design.themeMode) {
    M3ThemeMode.DARK -> true
    M3ThemeMode.LIGHT -> false
    M3ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
}

/** True when the active design (as read from
 * [LocalDesignState]) is the Material 3 design — the face
 * built as Editorial in code 123 and renamed by the owner's
 * code-124 ruling ("Editorial is material 3"). */
@Composable
fun isEditorialDesign(): Boolean = LocalDesignState.current.design == AppDesign.EDITORIAL

/** Code 123: true for the whole M3 family. Since code 124
 * removed the separate MATERIAL3 design, the family is the
 * one Material 3 design (EDITORIAL) — surfaces branch on
 * this when their family form serves the design, exactly as
 * they did when the family had two members. */
@Composable
fun isM3FamilyDesign(): Boolean = LocalDesignState.current.design != AppDesign.NOCTURNE
