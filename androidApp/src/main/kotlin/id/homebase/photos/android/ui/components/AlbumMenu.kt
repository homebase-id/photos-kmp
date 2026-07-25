package id.homebase.photos.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Album-level overflow menu (C2): Rename, Set as cover, Delete. "Set as cover" only lights up when
 * exactly one photo is selected — the cover is a single photo, so anything else is meaningless.
 * Lives here (not in the screen) because both the album top bar and its selection bar mount it.
 */
@Composable
fun AlbumOverflowMenu(
    onRename: () -> Unit,
    onSetCover: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    setCoverEnabled: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("album-menu")) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Album options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRename()
                },
                modifier = Modifier.testTag("album-rename"),
            )
            DropdownMenuItem(
                text = { Text("Set as cover") },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                enabled = setCoverEnabled,
                onClick = {
                    expanded = false
                    onSetCover()
                },
                modifier = Modifier.testTag("album-setcover"),
            )
            DropdownMenuItem(
                text = { Text("Delete album") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                modifier = Modifier.testTag("album-delete"),
            )
        }
    }
}
