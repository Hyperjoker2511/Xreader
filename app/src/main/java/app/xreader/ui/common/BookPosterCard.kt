package app.xreader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.pressTilt
import app.xreader.ui.library.PlaceholderCover
import coil.compose.AsyncImage

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
        Box(
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
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
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
