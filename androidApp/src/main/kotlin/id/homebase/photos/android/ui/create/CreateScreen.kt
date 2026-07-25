@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.create

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Create menu (A2). A small [ModalBottomSheet] with one honest item — "New album" — which now
 * opens the real create dialog (C3) rather than a placeholder screen.
 */
@Composable
fun CreateSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onNewAlbum: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("create-sheet"),
    ) {
        ListItem(
            headlineContent = { Text("New album") },
            leadingContent = {
                Icon(Icons.Outlined.PhotoAlbum, contentDescription = null)
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier
                .clickable(onClick = onNewAlbum)
                .padding(bottom = 24.dp)
                .testTag("create-new-album"),
        )
    }
}
