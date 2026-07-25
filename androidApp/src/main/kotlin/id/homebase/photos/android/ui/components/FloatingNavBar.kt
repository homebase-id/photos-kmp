package id.homebase.photos.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.photos.android.ui.nav.Route

/**
 * Google-Photos-2026 floating shell: a rounded pill hovering above content with the three feed/action
 * destinations (Photos · Collections · Create) and a separate round Search button to its right. The
 * active feed's item expands to icon + label (the "leading icon denoting the current feed"); the rest
 * collapse to icons. Create reads as an accented action, never a selected feed. Callers hide the whole
 * bar during timeline selection and on pushed screens.
 */
@Composable
fun FloatingNavBar(
    currentRoute: String?,
    onPhotos: () -> Unit,
    onCollections: () -> Unit,
    onCreate: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.testTag("bottom-nav"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillItem(
                    selected = currentRoute == Route.Photos.path,
                    icon = Icons.Outlined.PhotoLibrary,
                    label = "Photos",
                    onClick = onPhotos,
                    tag = "tab-photos",
                )
                PillItem(
                    selected = currentRoute == Route.Collections.path,
                    icon = Icons.Outlined.Collections,
                    label = "Collections",
                    onClick = onCollections,
                    tag = "tab-collections",
                )
                PillItem(
                    selected = false,
                    icon = Icons.Rounded.Add,
                    label = "Create",
                    onClick = onCreate,
                    tag = "tab-create",
                    accent = true,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Surface(
            onClick = onSearch,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(56.dp)
                .semantics { contentDescription = "Search" }
                .testTag("search-button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(16.dp)
                    .size(24.dp),
            )
        }
    }
}

/**
 * One pill destination. Selected → filled highlight (secondaryContainer) with icon + label; otherwise
 * icon-only. [accent] paints an action (Create) in `primary` so it never reads as a passive feed.
 */
@Composable
private fun PillItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
    accent: Boolean = false,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (selected) 16.dp else 14.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}
