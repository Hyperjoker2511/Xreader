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
                AsyncImage(
                    model = coverRequest ?: coverModel,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PlaceholderCover(
                    title = title,
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
