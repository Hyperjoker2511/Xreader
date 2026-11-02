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
 * disagree: ON shows everything, OFF hides 18+ (the
 * default). The full three-way control stays in Customise.
 */
@Composable
fun AdultVisibilityToggle(
    visibility: ContentVisibility,
    onSetVisibility: (ContentVisibility) -> Unit,
    modifier: Modifier = Modifier
) {
    val showing = visibility == ContentVisibility.SHOW_ALL
    NocturneChip(
        label = "18+",
        selected = showing,
        onClick = {
            onSetVisibility(
                if (showing) {
                    ContentVisibility.HIDE_ADULT
                } else {
                    ContentVisibility.SHOW_ALL
                }
            )
        },
        modifier = modifier
    )
}
