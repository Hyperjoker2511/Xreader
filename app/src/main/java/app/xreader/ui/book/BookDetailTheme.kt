package app.xreader.ui.book

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.CUSTOM_SCHEME_PREFIX
import app.xreader.ui.theme.CustomSchemeGenerator
import app.xreader.ui.theme.DynamicSchemeSanitizer
import app.xreader.ui.theme.LocalAppScheme
import app.xreader.ui.theme.appSchemeNamed
import app.xreader.ui.theme.isDarkScheme
import app.xreader.ui.theme.withTrueBlack

/**
 * The frozen book page's colour pin (v1.1.0, Nocturne reskin
 * design §3): BookDetailScreen.kt is FROZEN — this wrapper lives
 * OUTSIDE it, at the book-detail route's call site, and changes
 * nothing but the COLOURS its subtree sees.
 *
 * When [AppearanceState.bookDetailScheme] holds a snapshot (the
 * pre-flip scheme the one-time v2 migration copied verbatim),
 * the snapshot is resolved EXACTLY the way
 * [app.xreader.ui.theme.XreaderTheme] resolves the app scheme —
 * preset name via [appSchemeNamed], `custom:<id>` via
 * [CustomSchemeGenerator], `Wallpaper` via the sanitised
 * Material-You path on API 31+ (Ember fallback below it), then
 * the same true-black transform from the CURRENT appearance —
 * and provided to the subtree as a nested [MaterialTheme] with
 * only its `colorScheme` replaced (typography and shapes are
 * forwarded from the outer theme untouched) plus
 * [LocalAppScheme], so the screen's `primaryTextColor()` /
 * `aiAccentColor()` helpers resolve to the pinned values too.
 * `LocalGlassSettings` and the shell `HazeState` are NOT
 * provided here: glass geometry and blur stay global, identical
 * to the rest of the app.
 *
 * With no snapshot (fresh installs, or after an explicit scheme
 * choice cleared the pin) this is a pass-through: the book page
 * follows the app scheme like every other screen.
 *
 * Dialogs and the download sheet composed inside the route
 * inherit these locals (they parent their composition at the
 * call site), so popups on the book page keep the pinned
 * colours as well.
 */
@Composable
fun BookDetailTheme(
    appearance: AppearanceState,
    content: @Composable () -> Unit
) {
    // Hoisted to plain local vals before any null check (the
    // gate rule: no smart-cast surprises on captured state).
    val snapshotName = appearance.bookDetailScheme?.takeIf { it.isNotBlank() }
    val customSchemes = appearance.customSchemes
    val trueBlack = appearance.trueBlack
    val darkTheme = isDarkScheme()
    val context = LocalContext.current

    // The Wallpaper path, resolved the same way XreaderTheme
    // resolves it — remembered unconditionally so this
    // composable's hook order never depends on the pin state.
    val wallpaperScheme = remember(context) {
        if (Build.VERSION.SDK_INT >= 31) {
            DynamicSchemeSanitizer.appScheme(
                androidx.compose.material3.dynamicDarkColorScheme(context),
                androidx.compose.material3.dynamicLightColorScheme(context)
            )
        } else {
            null
        }
    }
    val resolvedScheme = remember(snapshotName, customSchemes, wallpaperScheme) {
        val name = snapshotName
        when {
            name == null -> null
            name == "Wallpaper" && wallpaperScheme != null -> wallpaperScheme
            else -> {
                val customId = name
                    .takeIf { it.startsWith(CUSTOM_SCHEME_PREFIX) }
                    ?.removePrefix(CUSTOM_SCHEME_PREFIX)
                val spec = customSchemes.firstOrNull { it.id == customId }
                if (spec != null) CustomSchemeGenerator.generate(spec)
                else appSchemeNamed(name)
            }
        }
    }

    val schemeNow = resolvedScheme
    if (schemeNow == null) {
        content()
        return
    }
    val colors = schemeNow.colors(darkTheme).let { schemeColors ->
        if (darkTheme && trueBlack) schemeColors.withTrueBlack() else schemeColors
    }
    CompositionLocalProvider(LocalAppScheme provides schemeNow) {
        MaterialTheme(
            colorScheme = colors,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content
        )
    }
}
