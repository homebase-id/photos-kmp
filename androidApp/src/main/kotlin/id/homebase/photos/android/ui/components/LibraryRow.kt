package id.homebase.photos.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// A disabled row still has to read as a real destination, just not-yet — not as broken chrome.
private const val DISABLED_ALPHA = 0.55f

/**
 * One library destination row (Favorites / Archive / Trash / Utilities): a tonal circular glyph,
 * the label, and an optional trailing note. [enabled] false renders it dimmed and inert — the
 * Batch-D screens don't exist yet, so the rows announce themselves without lying about working.
 */
@Composable
fun LibraryRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    trailingLabel: String? = null,
) {
    ListItem(
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingContent = if (trailingLabel == null) {
            null
        } else {
            {
                Text(
                    text = trailingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .testTag(testTag),
    )
}
