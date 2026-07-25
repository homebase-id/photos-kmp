package id.homebase.photos.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Single-text-field dialog — the shared shape behind "New album" and "Rename album" (DRY: one
 * dialog, two call sites). Confirm stays disabled while the name is blank, and the field opens
 * focused with any existing name selected so a rename is one keystroke away.
 */
@Composable
fun NameInputDialog(
    title: String,
    confirmLabel: String,
    testTag: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    fieldLabel: String = "Album name",
    fieldTestTag: String = "name-dialog-field",
    confirmTestTag: String = "name-dialog-confirm",
) {
    var value by remember {
        mutableStateOf(TextFieldValue(text = initialName, selection = TextRange(0, initialName.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val name = value.text.trim()
    val confirm = { if (name.isNotEmpty()) onConfirm(name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(fieldLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(fieldTestTag),
            )
        },
        confirmButton = {
            TextButton(
                onClick = confirm,
                enabled = name.isNotEmpty(),
                modifier = Modifier.testTag(confirmTestTag),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        modifier = Modifier.testTag(testTag),
    )
}
