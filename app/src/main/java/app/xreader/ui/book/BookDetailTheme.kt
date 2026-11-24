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
 * The book page's colour pin (v1.1.0, Nocturne reskin design
 * §3): this wrapper lives OUTSIDE BookDetailScreen.kt, at the
 * book-detail route's call site, and changes nothing but the
 * COLOURS its subtree sees. (The screen itself was frozen
 * when this wrapper was written; the owner's 2026-11-24
 * ruling — "Not frozen I said okay" — has since opened it for
 * the Material 3 layout, which branches inside the screen.
 * The pin itself is unchanged — and, per the code-120 fix
 * pass (recheck L1), it resolves ONLY under Nocturne: under
 * Material 3 the wrapper never resolves it, so an M3 book
 * page wears the ambient M3 scheme (or the cover scheme
 * above), never a Nocturne preset or snapshot.)
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
    /** Code 120 (design §2.5 R8): the design state + the open
     * book's MCU cover seed. In the Material 3 design with the
     * book-page cover-colour knob on, this subtree wears a
     * scheme generated from the cover (MCU Content style) —
     * containers and accents only; the app scheme resumes on
     * exit because the scheme is provided to this subtree
     * alone. Nocturne never takes this branch. */
    design: app.xreader.data.prefs.DesignState =
        app.xreader.data.prefs.DesignState(),
    coverSeedArgb: Int? = null,
    content: @Composable () -> Unit
) {
    // Hoisted to plain local vals before any null check (the
    // gate rule: no smart-cast surprises on captured state).
    val snapshotName = appearance.bookDetailScheme?.takeIf { it.isNotBlank() }
    val customSchemes = appearance.customSchemes
    val trueBlack = appearance.trueBlack
    val darkTheme = isDarkScheme()
    val context = LocalContext.current

    // Code 120 (R8): the cover-derived pair, computed
    // unconditionally (hook order never depends on the seed's
    // arrival) and applied only in the M3 branch below.
    val coverPair = remember(coverSeedArgb) {
        coverSeedArgb?.let { seed ->
            app.xreader.ui.theme.McuScheme.contentScheme(seed, true) to
                app.xreader.ui.theme.McuScheme.contentScheme(seed, false)
        }
    }


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

    // Code 120 (R8): the Material 3 cover scheme — placed after
    // every remember above so this composable's hook order is
    // the same on both paths (the file's own rule).
    if (design.design == app.xreader.data.prefs.AppDesign.MATERIAL3 &&
        design.bookPageCoverColour && coverPair != null
    ) {
        val coverColors = if (darkTheme) coverPair.first else coverPair.second
        val coverAppScheme = app.xreader.ui.theme.AppScheme(
            name = "Cover",
            dark = coverPair.first,
            light = coverPair.second,
            primaryTextDark = coverPair.first.primary,
            primaryTextLight = coverPair.second.primary,
            aiTextDark = coverPair.first.tertiary,
            aiTextLight = coverPair.second.tertiary,
            errorTextDark = coverPair.first.error,
            errorTextLight = coverPair.second.error,
            sageTextDark = coverPair.first.secondary,
            sageTextLight = coverPair.second.secondary
        )
        CompositionLocalProvider(LocalAppScheme provides coverAppScheme) {
            MaterialTheme(
                colorScheme = coverColors,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
                content = content
            )
        }
        return
    }

    // Code 120 fix pass (recheck L1 — BLOCKER): under Material
    // 3 the Nocturne colour pin NEVER resolves. The cover
    // branch above returns only when the knob is on AND a seed
    // has landed; in every other M3 case (knob off, seed still
    // computing, cover yields no seed) the page wears the
    // ambient M3 scheme like every other M3 surface — it must
    // not fall through to the pin below, which would dress the
    // page in a Nocturne preset / custom snapshot. Nocturne's
    // pin path below is untouched.
    if (design.design == app.xreader.data.prefs.AppDesign.MATERIAL3) {
        content()
        return
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
