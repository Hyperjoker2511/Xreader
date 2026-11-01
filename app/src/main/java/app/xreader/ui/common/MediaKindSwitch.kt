package app.xreader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.data.extension.MediaKind

/**
 * Manga programme (M3): THE Novels | Manga switch — one
 * segmented control shared by the Library and Explore, so the
 * split looks and behaves identically on both. A stadium
 * container in the kit's tier colour; the active segment
 * takes the violet primary fill (the NocturneChip selected
 * treatment), the other stays quiet. Labels arrive ready-made
 * from the caller (each surface rides its own live count on
 * the name — see LibrarySplit.switchLabel).
 */
@Composable
fun MediaKindSwitch(
    side: MediaKind,
    novelsLabel: String,
    mangaLabel: String,
    onSelect: (MediaKind) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MediaKindSegment(
                label = novelsLabel,
                selected = side == MediaKind.TEXT,
                onClick = { onSelect(MediaKind.TEXT) },
                modifier = Modifier.weight(1f)
            )
            MediaKindSegment(
                label = mangaLabel,
                selected = side == MediaKind.MANGA,
                onClick = { onSelect(MediaKind.MANGA) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MediaKindSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
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
