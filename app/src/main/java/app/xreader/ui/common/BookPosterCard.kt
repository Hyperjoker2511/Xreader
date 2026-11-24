package app.xreader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.ui.library.PlaceholderCover
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * v1.0.6.2 (F6 + Revision 2 S1): THE book card — one design
 * everywhere a book appears as a card: Library shelf, Recent books,
 * Browse catalogue grids and Search results.
 *
 * The cover IS the card: a 2:3 poster, full-bleed, centre-cropped,
 * in the app's rounded corners — no slab behind it, no border
 * strip. Title sits BELOW the cover in a fixed two-line area (rows
 * align), author small beneath it when known, then ONE compact
 * metadata line, and progress as a thin quiet line. No-cover books
 * get the violet-family placeholder tile at the same proportions.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookPosterCard(
    title: String,
    author: String?,
    coverModel: Any?,
    metaLine: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    progressFraction: Float? = null,
    topEndBadge: String? = null,
    topStartBadge: String? = null,
    glowColor: Color? = null,
    busy: Boolean = false,
    tilt: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(if (tilt) Modifier.pressTilt() else Modifier),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
    ) {
        BoxWithConstraints(
            if (glowColor != null) {
                Modifier.drawBehind {
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(
                                glowColor.copy(alpha = 0.55f),
                                Color.Transparent
                            ),
                            radius = size.maxDimension * 0.8f
                        ),
                        radius = size.maxDimension * 0.8f
                    )
                }
            } else {
                Modifier
            }
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
        ) {
            // v1.2.0 (WS6 add-on, following WS4's Library pattern):
            // decode the cover near the size it is actually drawn
            // at — this box's own measured width (the 2:3 poster
            // makes the height width × 1.5) — instead of a full
            // decode of the stored image. Falls back to the raw
            // model when no finite size is known (see the helper).
            val coverWidthDp = maxWidth
            val coverRequest = rememberSizedPosterCover(
                coverModel, coverWidthDp.value
            )
            if (coverModel != null) {
                // v1.3.3 (V132-CHECK 2, scope S2): a cover that
                // FAILS to load (a guarded host, a dead URL) used
                // to paint NOTHING — an invisible box in the
                // grid. The loading and error slots now render
                // the designed title cover, so a failed cover is
                // a visible cover, never a hole.
                coil.compose.SubcomposeAsyncImage(
                    model = coverRequest ?: coverModel,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        PlaceholderCover(
                            title = title,
                            author = author,
                            modifier = Modifier.fillMaxSize()
                        )
                    },
                    error = {
                        PlaceholderCover(
                            title = title,
                            author = author,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )
            } else {
                PlaceholderCover(
                    title = title,
                    author = author,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (topEndBadge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        topEndBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            if (topStartBadge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        topStartBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .height(26.dp)
                )
            }
        }
        // Fixed two-line title area: short titles never shrink the
        // card, long ones ellipsize — grid rows stay aligned.
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!author.isNullOrBlank()) {
            Text(
                author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (metaLine != null) {
            Text(
                metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (progressFraction != null && progressFraction > 0f) {
            // Code 124: the wavy variant belonged to the
            // removed MATERIAL3 design; the 3dp pill line is
            // every surviving design's cover progress line.
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
            )
        }
    }
}

/**
 * v1.2.0 (WS6 add-on): a cover [ImageRequest] that asks Coil to
 * decode near the size the poster is actually drawn at, following
 * the `rememberSizedCover` pattern Worker D added in
 * ui/library/LibraryScreen.kt (WS4) — display width in dp converted
 * with the device density; the poster's fixed 2:3 aspect makes the
 * height width × 1.5. Works for every model type this card receives
 * (local File, remote URL string). Returns null — and the caller
 * falls back to the raw model — when there is no model or no finite
 * positive width is known; the null-model placeholder path is
 * untouched.
 */
// ── Code 121 (S6): the Material 3 design's shared book atoms ──
// These composables render ONLY under the Material 3 design
// (callers branch on LocalDesignState before reaching them);
// the Nocturne card above is untouched. S6's three atoms: the
// cover thumbnail (with the title-cover fallback), the wide
// book card (R1's shelf card) and the poster card (R20/R33
// only). No glow, no cover-shadow, no glass in any atom — a
// cover takes the shape of the component that carries it.

/**
 * S6 cover thumbnail: the cover image in the carrying
 * component's shape (medium by default), or — when there is no
 * cover, or the cover fails to load — the title-cover fallback:
 * a filled `surfaceContainerHigh` block bearing the title in
 * titleSmall and the author in bodySmall.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M3CoverThumbnail(
    title: String,
    author: String?,
    coverModel: Any?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.material3.MaterialTheme.shapes.medium
) {
    Box(modifier.clip(shape)) {
        if (coverModel != null) {
            coil.compose.SubcomposeAsyncImage(
                model = coverModel,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    M3TitleCoverFallback(
                        title = title, author = author,
                        modifier = Modifier.fillMaxSize()
                    )
                },
                error = {
                    M3TitleCoverFallback(
                        title = title, author = author,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        } else {
            M3TitleCoverFallback(
                title = title, author = author,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** S6's title-cover fallback block (see [M3CoverThumbnail]). */
@Composable
fun M3TitleCoverFallback(
    title: String,
    author: String?,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (!author.isNullOrBlank()) {
                Text(
                    author,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * S6 book card — R1's wide filled card: cover thumbnail at
 * leading, emphasized title, author, and the caller's status /
 * progress line, with an optional trailing slot (the shelf's
 * overflow menu lives there). Long-press is the shelf's
 * multi-select door.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
fun M3BookCard(
    title: String,
    author: String?,
    coverModel: Any?,
    supportingLine: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    progressFraction: Float? = null,
    selected: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null
) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) {
                androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
            } else {
                androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            M3CoverThumbnail(
                title = title,
                author = author,
                coverModel = coverModel,
                modifier = Modifier
                    .width(52.dp)
                    .height(76.dp)
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!author.isNullOrBlank()) {
                    Text(
                        author,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (supportingLine != null) {
                    Text(
                        supportingLine,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progressFraction != null && progressFraction > 0f) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * S6 poster card — cover-over-title, used ONLY by R20's
 * catalogue grid and R33's generated-art strip: a filled card
 * whose media is the cover (2:3) with title + metadata inside
 * the card under it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M3PosterCard(
    title: String,
    author: String?,
    coverModel: Any?,
    metaLine: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressFraction: Float? = null
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            M3CoverThumbnail(
                title = title,
                author = author,
                coverModel = coverModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (metaLine != null) {
                    Text(
                        metaLine,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progressFraction != null && progressFraction > 0f) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberSizedPosterCover(model: Any?, widthDp: Float): ImageRequest? {
    val context = LocalContext.current
    return remember(model, widthDp) {
        if (model == null || !widthDp.isFinite() || widthDp <= 0f) {
            null
        } else {
            val density = context.resources.displayMetrics.density
            val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
            val heightPx = (widthPx * 1.5f).toInt().coerceAtLeast(1)
            ImageRequest.Builder(context)
                .data(model)
                .size(widthPx, heightPx)
                .build()
        }
    }
}
