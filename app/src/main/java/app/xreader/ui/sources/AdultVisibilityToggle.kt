package app.xreader.ui.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.xreader.data.extension.ContentVisibility
import app.xreader.ui.common.NocturneChip

/**
 * v1.4.3: the 18+ quick toggle for the source screens'
 * top-right corner (the user's ask: "small 18 button tap on
 * tap off"). It drives the SAME [ContentVisibility]
 * preference as Customise → Sources & repairs — one source
 * of truth, so the toggle and the three-way chips can never
 * disagree: ON shows everything, OFF hides per the mode.
 * The full three-way control stays in Customise.
 *
 * v1.5.1 (full-app audit F5): the step itself is delegated to
 * [onToggle] (SourcePrefs.toggleContentVisibility) — the old
 * binary write (ON = SHOW_ALL, OFF = HIDE_ADULT) silently
 * destroyed a "Hide Mixed and 18+" choice. The toggle now
 * remembers the mode it stepped away from and restores it
 * exactly on the step back (AdultToggleLogic).
 */
@Composable
fun AdultVisibilityToggle(
    visibility: ContentVisibility,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showing = visibility == ContentVisibility.SHOW_ALL
    NocturneChip(
        label = "18+",
        selected = showing,
        onClick = onToggle,
        modifier = modifier
    )
}
