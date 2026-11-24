package app.xreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.xreader.ui.localreader.EpubPlateFilter
import java.io.File

/**
 * v1.7 Patch 3 (Item 4f, ruling R3): the plate viewer on the
 * core road — the new File-fed surface that replaces the
 * river's session-welded EpubPlateViewer for converted
 * books. A tap on an illustration in any reading surface
 * (reported through [LocalImageTapHandler]) opens the image
 * full-screen here: fitted, pinch / double-tap zoom via the
 * shared ZoomableImage, the book's stored plate filter
 * applied, dismissed by the Close chip or Back.
 *
 * The filter is the per-book choice from the old EPUB
 * settings, applied as-is: the river CSS only applied it
 * under the DARK theme; on this road the stored choice is
 * honoured directly, which is what the setting always
 * promised. The Close chip is always visible (the river
 * viewer tap-toggles its affordances; one steady chip is
 * the plainer contract here, and the image's own taps are
 * deliberately inert — the explicit no-op onImageTap below
 * opts this ZoomableImage out of the subtree's plate door).
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun CorePlateViewer(
    file: File,
    filter: EpubPlateFilter,
    onDismiss: () -> Unit,
    /** Code 122 (S9, audit #14): the plate's caption — the
     * host supplies the book title (the illustration's own
     * alt text does not survive the tap handler, which
     * carries only the asset file). Null = the bar reads
     * "Illustration". */
    caption: String? = null
) {
    BackHandler(onBack = onDismiss)
    // Code 122 (S9): under Material 3 the viewer wears the
    // R28 chrome family — a small top app bar above the plate
    // and a floating toolbar at the foot carrying the caption
    // and the Close door. Nocturne keeps the shipped black
    // full-screen box with its steady Close chip below.
    val plateIsM3 = app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ZoomableImage(
            model = file,
            contentDescription = caption,
            contentScale = ContentScale.Fit,
            imageModifier = Modifier.fillMaxSize(),
            colorFilter = filter.toColorFilter(),
            onImageTap = {},
            modifier = Modifier.fillMaxSize()
        )
        if (plateIsM3) {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(
                        caption ?: "Illustration",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored
                                .Filled.ArrowBack,
                            "Back"
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults
                    .topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor =
                            MaterialTheme.colorScheme.surfaceContainer
                    ),
                modifier = Modifier.align(Alignment.TopCenter)
            )
            androidx.compose.material3.HorizontalFloatingToolbar(
                expanded = false,
                colors = androidx.compose.material3.FloatingToolbarDefaults
                    .standardFloatingToolbarColors(
                        toolbarContainerColor =
                            MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = {
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Close")
                    }
                    if (!caption.isNullOrBlank()) {
                        Text(
                            caption,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            )
        } else {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.25f),
                        RoundedCornerShape(50)
                    )
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Close",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        }
    }
}

/** The stored plate filter as a Compose colour filter:
 * ORIGINAL is no filter; DARKEN dims to 80%; INVERT is the
 * photographic negative (the same -1/255 matrix family the
 * manga colour filters use). Pure, so the mapping is
 * pinnable without a composition. */
fun EpubPlateFilter.toColorFilter(): ColorFilter? = when (this) {
    EpubPlateFilter.ORIGINAL -> null
    EpubPlateFilter.DARKEN -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 0.8f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    EpubPlateFilter.INVERT -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}
