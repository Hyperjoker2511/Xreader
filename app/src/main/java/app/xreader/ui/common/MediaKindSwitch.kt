package app.xreader.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.segmentColorSpec
import app.xreader.data.extension.MediaKind
import app.xreader.data.library.NavIndicatorStyle
import app.xreader.data.prefs.PerfTier
import app.xreader.ui.shell.GooeyIndicator
import app.xreader.ui.shell.NavIndicatorLogic
import kotlin.math.roundToInt

/**
 * Manga programme (M3): THE Novels | Manga switch — one
 * segmented control shared by the Library and Explore, so the
 * split looks and behaves identically on both. A stadium
 * container in the kit's tier colour; labels arrive ready-made
 * from the caller (each surface rides its own live count on
 * the name — see LibrarySplit.switchLabel).
 *
 * Nav-bar styles (v1.6.2, SPEC-NAVBAR-STYLES-2026-11-16
 * Change 3): the active segment's fill is no longer two
 * colours crossfading — it is a 2-stop INDICATOR in the dock's
 * language. Under GOOEY the fill is the dock's own melt
 * (GooeyIndicator over the two segment centres, bridge and
 * all); under every other travelling style a single pill
 * rides the shared travel spring between the stops; under
 * PLAIN (or reduced motion) it sits at the active stop
 * instantly. The fill wears the resolved indicator colour —
 * the user's nav colour choice reaches this switch too — and
 * the labels' colours stay coupled to it (the segment colour
 * ease), so the tapped side answers while the pill travels.
 */
@Composable
fun MediaKindSwitch(
    side: MediaKind,
    novelsLabel: String,
    mangaLabel: String,
    onSelect: (MediaKind) -> Unit,
    modifier: Modifier = Modifier,
    /** The user's dock indicator style (v1.6.2). */
    indicatorStyle: NavIndicatorStyle = NavIndicatorStyle.GOOEY,
    /** The resolved custom indicator colour, or
     * [Color.Unspecified] for the theme primary. */
    indicatorColour: Color = Color.Unspecified,
    perfTier: PerfTier = PerfTier.FULL,
    reduceMotion: Boolean = false
) {
    val colour = if (indicatorColour == Color.Unspecified) {
        MaterialTheme.colorScheme.primary
    } else {
        indicatorColour
    }
    val onColour = Color(
        NavIndicatorLogic.onIndicatorArgb(
            colour.toArgb().toLong() and 0xFFFFFFFFL
        ).toInt()
    )
    val effective = NavIndicatorLogic.effectiveStyle(
        indicatorStyle, perfTier, reduceMotion
    )
    val density = LocalDensity.current
    val padPx = with(density) { 4.dp.roundToPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    val segWidthPx = if (widthPx > 0) {
        (widthPx - 2 * padPx - padPx) / 2f
    } else {
        0f
    }
    val segHeightPx = if (heightPx > 0) {
        (heightPx - 2 * padPx).toFloat()
    } else {
        0f
    }
    fun centreFor(s: MediaKind): Float =
        padPx + segWidthPx / 2f +
            if (s == MediaKind.MANGA) segWidthPx + padPx else 0f
    val targetCentre = centreFor(side)

    // The travelling pill's shared centre (non-gooey styles).
    val travel = remember { Animatable(0f) }
    var travelInit by remember { mutableStateOf(false) }
    LaunchedEffect(targetCentre, effective, perfTier) {
        if (segWidthPx <= 0f) return@LaunchedEffect
        when {
            effective == NavIndicatorStyle.GOOEY ||
                effective == NavIndicatorStyle.PLAIN -> Unit
            !travelInit -> {
                travel.snapTo(targetCentre)
                travelInit = true
            }
            else -> travel.animateTo(
                targetCentre,
                NavIndicatorLogic.navTravelSpec(perfTier)
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    widthPx = it.width
                    heightPx = it.height
                }
        ) {
            if (segWidthPx > 0f && segHeightPx > 0f) {
                when (effective) {
                    NavIndicatorStyle.GOOEY -> GooeyIndicator(
                        targetCentreX = targetCentre,
                        slotWidthPx = segWidthPx,
                        topPx = padPx.toFloat(),
                        heightPx = segHeightPx,
                        colour = colour,
                        perfTier = perfTier,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.fillMaxSize()
                    )
                    NavIndicatorStyle.PLAIN -> Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = colour,
                            topLeft = Offset(
                                targetCentre - segWidthPx / 2f,
                                padPx.toFloat()
                            ),
                            size = Size(segWidthPx, segHeightPx),
                            cornerRadius = CornerRadius(
                                segHeightPx / 2f, segHeightPx / 2f
                            )
                        )
                    }
                    else -> Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (travel.value - segWidthPx / 2f)
                                        .roundToInt(),
                                    padPx
                                )
                            }
                            .size(
                                width = with(density) { segWidthPx.toDp() },
                                height = with(density) { segHeightPx.toDp() }
                            )
                            .background(
                                colour, RoundedCornerShape(20.dp)
                            )
                    ) {}
                }
            }
            Row(
                Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MediaKindSegment(
                    label = novelsLabel,
                    selected = side == MediaKind.TEXT,
                    onIndicatorColour = onColour,
                    onClick = { onSelect(MediaKind.TEXT) },
                    modifier = Modifier.weight(1f)
                )
                MediaKindSegment(
                    label = mangaLabel,
                    selected = side == MediaKind.MANGA,
                    onIndicatorColour = onColour,
                    onClick = { onSelect(MediaKind.MANGA) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MediaKindSegment(
    label: String,
    selected: Boolean,
    onIndicatorColour: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The label colour stays COUPLED to the indicator (fix
    // round §D.3's ease, kept): it eases to the on-indicator
    // colour while the pill travels, so the control you tapped
    // answers first. The fill itself is the travelling
    // indicator behind the row — this segment draws none.
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            onIndicatorColour
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = segmentColorSpec(),
        label = "segmentContent"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
