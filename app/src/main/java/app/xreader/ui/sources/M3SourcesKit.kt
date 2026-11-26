package app.xreader.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.AppDesign
import app.xreader.ui.theme.LocalDesignState
import coil.compose.AsyncImage

/**
 * Code 121 (W4): the shared Material 3 atoms for the Explore &
 * sources surfaces (scratch design §3.4). These render ONLY
 * inside a Material 3 branch — every Nocturne surface keeps its
 * shipped atoms byte-identically.
 */

/** True when the active design is the Material 3 design
 * — the branch condition every W4 surface uses.
 *
 * Code 123: widened to the M3 FAMILY (the design then
 * named Editorial included). Code 124: the family IS the
 * one Material 3 design (the separate code-120 face is
 * removed); only Nocturne keeps the shipped forms. */
@Composable
internal fun isM3Design(): Boolean =
    LocalDesignState.current.design != AppDesign.NOCTURNE

/** R2/S8: a source's tonal circle avatar carrying its initial —
 * the leading element of every M3 source list item and search
 * group header. */
@Composable
internal fun M3InitialAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 40
) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/** A group header in the scratch design's language: labelLarge,
 * onSurfaceVariant (§3.4 R2 / S8). */
@Composable
internal fun M3GroupHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/**
 * S8 / R1-style wide book card: a filled card carrying the cover
 * at its leading edge, the emphasized title, the author line and
 * the "In library" badge where the book is already held. Used by
 * the global-search results; [coverModel] is a URL string or a
 * local File, exactly as Coil accepts elsewhere in the app.
 */
@Composable
internal fun M3WideBookCard(
    title: String,
    author: String?,
    coverModel: Any?,
    inLibrary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Patch B (gap 31): the matched-text snippet for an
     * inside-downloads hit ("…snippet…"), as Nocturne shows. */
    snippet: String? = null,
    /** Patch B (gap 32): an extra meta line under the author —
     * the "On N sources" count for a search result. */
    metaLine: String? = null
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(48.dp)
                        .height(68.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                )
            } else {
                Box(
                    Modifier
                        .width(48.dp)
                        .height(68.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
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
                if (!snippet.isNullOrBlank()) {
                    Text(
                        "…$snippet…",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (!metaLine.isNullOrBlank()) {
                    Text(
                        metaLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (inLibrary) {
                    Text(
                        "In library",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
