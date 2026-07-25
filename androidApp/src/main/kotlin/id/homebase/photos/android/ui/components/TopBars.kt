@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Minimal "Photos" top bar over `surface` — small title, 32dp avatar action top-right (opens the
 * log-out dialog via [onAccountClick]). A 1dp `outline` hairline fades in only once the grid has
 * scrolled.
 */
@Composable
fun PhotosTopBar(
    scrolled: Boolean,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hairlineAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        label = "topbar-hairline",
    )
    Column(modifier = modifier) {
        TopAppBar(
            title = { Text(text = "Photos", style = MaterialTheme.typography.titleMedium) },
            actions = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(role = Role.Button, onClick = onAccountClick)
                        .testTag("account-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = "Account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = hairlineAlpha),
        )
    }
}

/**
 * Selection-mode top bar (contract C4/C5): X exits selection, "N selected" title, one primary
 * action. Swapped in for [PhotosTopBar] while any photo is selected. The primary action defaults
 * to the timeline's trash; the album detail screen re-points it at remove-from-album, and both
 * hang their own extras (add-to-album, the album overflow menu) off [extraActions] — one bar, not
 * two near-identical copies.
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector = Icons.Outlined.Delete,
    actionLabel: String = "Delete selected",
    actionTag: String = "selection-delete",
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("selection-count"),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("selection-close")) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Exit selection")
            }
        },
        actions = {
            extraActions()
            IconButton(onClick = onAction, modifier = Modifier.testTag(actionTag)) {
                Icon(imageVector = actionIcon, contentDescription = actionLabel)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier.testTag("selection-topbar"),
    )
}
