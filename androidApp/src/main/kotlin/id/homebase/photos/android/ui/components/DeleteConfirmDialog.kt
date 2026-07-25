package id.homebase.photos.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Delete confirmation per contract C5 — destructive confirm tagged `delete-confirm`.
 * Shared by the timeline selection bar, the viewer action bar, and album deletion (DRY, owner
 * directive). [title]/[message] override the photo-count copy for non-photo deletions; the
 * confirm target keeps its tag either way.
 */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = if (count == 1) "Delete 1 item?" else "Delete $count items?",
    message: String = "They'll be removed from your Homebase photo library.",
    confirmLabel: String = "Delete",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("delete-confirm")) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
