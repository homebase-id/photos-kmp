package id.homebase.photos.android.ui.backup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.photos.android.work.BackupScheduler
import id.homebase.photos.backup.BackupViewModel
import id.homebase.photos.backup.FolderUi
import kotlinx.coroutines.launch

/**
 * Stateful backup surface for the timeline. Mirrors the shared [BackupViewModel] state and, when the
 * user enables backup, requests the media-read permission before flipping the toggle and scheduling
 * WorkManager. Denied → the toggle stays off (the switch is bound to shared state) and a snackbar
 * explains why.
 *
 * Folder-selective (D6): the same media permission gates the folder picker; opening it lazily loads
 * the device folders, and enabling backup with nothing selected opens the picker instead of firing
 * an empty run.
 */
@Composable
fun BackupStatusCard(
    viewModel: BackupViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = remember { requiredMediaPermission() }

    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onToggle(true)
            BackupScheduler.enable(context)
        } else {
            scope.launch { snackbarHostState.showSnackbar("Allow photo access to back up your library.") }
        }
    }

    val foldersLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.loadFolders()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Allow photo access to choose folders.") }
        }
    }

    BackupStatusCard(
        enabled = state.enabled,
        running = state.running,
        done = state.done,
        total = state.total,
        lastCompletedAt = state.lastCompletedAt,
        selectedFolderCount = state.selectedFolderCount,
        folders = state.folders,
        onToggle = { wantEnabled ->
            if (wantEnabled) {
                if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.onToggle(true)
                    BackupScheduler.enable(context)
                } else {
                    enableLauncher.launch(permission)
                }
            } else {
                viewModel.onToggle(false)
                BackupScheduler.disable(context)
            }
        },
        onChooseFolders = {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.loadFolders()
            } else {
                foldersLauncher.launch(permission)
            }
        },
        onFolderToggled = { folderId -> viewModel.onFolderToggled(folderId) },
        modifier = modifier,
    )
}

/**
 * Stateless backup status card — a compact row: title, a status/progress line, and the on/off
 * toggle. While a pass runs it shows `done of total` plus a slim determinate bar. When enabled, a
 * "Choose folders" affordance opens the folder picker; flipping the toggle on with nothing selected
 * opens the picker instead of starting an empty run. Design tokens only; testTags `backup-card` /
 * `backup-toggle` / `backup-progress` / `backup-choose-folders` (+ the picker's own tags).
 */
@Composable
fun BackupStatusCard(
    enabled: Boolean,
    running: Boolean,
    done: Int,
    total: Int,
    lastCompletedAt: Long?,
    selectedFolderCount: Int,
    folders: List<FolderUi>,
    onToggle: (Boolean) -> Unit,
    onChooseFolders: () -> Unit,
    onFolderToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    // Open the picker: surface the sheet and (permission-gated, in the wrapper) load the folders.
    fun openPicker() {
        pickerOpen = true
        onChooseFolders()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("backup-card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Back up",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (running) {
                        Text(
                            text = "$done of $total",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("backup-progress"),
                        )
                    } else {
                        Text(
                            text = statusLine(enabled, lastCompletedAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { wantEnabled ->
                        // Enabling with nothing selected would upload nothing — pick folders first.
                        if (wantEnabled && selectedFolderCount == 0) {
                            openPicker()
                        } else {
                            onToggle(wantEnabled)
                        }
                    },
                    modifier = Modifier.testTag("backup-toggle"),
                )
            }
            if (running && total > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            if (enabled) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { openPicker() },
                    modifier = Modifier.testTag("backup-choose-folders"),
                ) {
                    Text(
                        text = if (selectedFolderCount > 0) "Choose folders ($selectedFolderCount)" else "Choose folders",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        FolderPickerSheet(
            folders = folders,
            onFolderToggled = onFolderToggled,
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * Folder picker bottom sheet: one tappable row per device folder (checkbox, name, photo count).
 * Toggling a row persists immediately via [onFolderToggled] (shared-side). testTags
 * `backup-folder-sheet`, `backup-folder-row-<folderId>`, `backup-folder-done`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerSheet(
    folders: List<FolderUi>,
    onFolderToggled: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("backup-folder-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Back up these folders",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            folders.forEach { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFolderToggled(folder.folderId) }
                        .testTag("backup-folder-row-${folder.folderId}")
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = folder.selected,
                        onCheckedChange = { onFolderToggled(folder.folderId) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${folder.photoCount} photos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("backup-folder-done"),
            ) {
                Text("Done")
            }
        }
    }
}

/** Non-running status: "Backed up <relative>" when we have a timestamp, else On/Off. */
private fun statusLine(enabled: Boolean, lastCompletedAt: Long?): String = when {
    enabled && lastCompletedAt != null ->
        "Backed up ${DateUtils.getRelativeTimeSpanString(
            lastCompletedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )}"
    enabled -> "On"
    else -> "Off"
}

/** Android 13+ scopes photo reads to READ_MEDIA_IMAGES; older devices use storage. */
private fun requiredMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
