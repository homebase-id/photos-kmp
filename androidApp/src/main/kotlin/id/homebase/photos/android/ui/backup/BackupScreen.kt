@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import id.homebase.photos.backup.FolderUi
import id.homebase.photos.backupViewModel
import kotlinx.coroutines.launch

/**
 * Stateful Backup screen (re-homed from the old timeline card). Mirrors the shared BackupViewModel
 * state; enabling requests the media-read permission before flipping the toggle AND arms
 * WorkManager via [BackupScheduler] (the shared toggle alone never schedules anything). Denied →
 * the toggle stays off (bound to shared state) and a snackbar explains why.
 *
 * Folder-selective (D6): the same media permission gates the folder picker; opening it lazily
 * loads the device folders, and enabling backup with nothing selected opens the picker instead of
 * firing an empty run.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember { backupViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val permission = remember { requiredMediaPermission() }

    // Progress notifications are advisory — asked for, never gated on. A denial only costs the
    // progress bar; BackupWorker's foreground service runs either way.
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val enableBackup = {
        viewModel.onToggle(true)
        BackupScheduler.enable(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableBackup()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Allow photo access to back up your library.") }
        }
    }

    // Continuation for a picker-open request that had to detour through the permission prompt.
    var pendingPickerOpen by remember { mutableStateOf<(() -> Unit)?>(null) }
    val foldersLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.loadFolders()
            pendingPickerOpen?.invoke()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Allow photo access to choose folders.") }
        }
        pendingPickerOpen = null
    }

    BackupScreen(
        enabled = state.enabled,
        running = state.running,
        done = state.done,
        total = state.total,
        currentName = state.currentName,
        lastCompletedAt = state.lastCompletedAt,
        selectedFolderCount = state.selectedFolderCount,
        folders = state.folders,
        onBack = onBack,
        onToggle = { wantEnabled ->
            if (wantEnabled) {
                if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    enableBackup()
                } else {
                    enableLauncher.launch(permission)
                }
            } else {
                viewModel.onToggle(false)
                BackupScheduler.disable(context)
            }
        },
        onBackupNow = {
            viewModel.onBackupNow()
            BackupScheduler.backupNow(context)
        },
        onChooseFolders = { onReady ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.loadFolders()
                onReady()
            } else {
                pendingPickerOpen = onReady
                foldersLauncher.launch(permission)
            }
        },
        onFolderToggled = { folderId -> viewModel.onFolderToggled(folderId) },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * Stateless Backup screen: on/off toggle with a status line ("Backed up <relative>" / On / Off),
 * a `done of total` progress row while a pass runs, a "Back up now" action, and a "Choose folders"
 * row opening the folder picker sheet. Flipping the toggle on with nothing selected opens the
 * picker instead of starting an empty run. testTags: `backup-screen` / `backup-toggle` /
 * `backup-progress` / `backup-now` / `backup-folders` (+ the picker's own tags).
 */
@Composable
fun BackupScreen(
    enabled: Boolean,
    running: Boolean,
    done: Int,
    total: Int,
    currentName: String?,
    lastCompletedAt: Long?,
    selectedFolderCount: Int,
    folders: List<FolderUi>,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit = {},
    onBackupNow: () -> Unit = {},
    onChooseFolders: (onReady: () -> Unit) -> Unit = { it() },
    onFolderToggled: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    // Open the picker: the wrapper loads folders (permission-gated) and only then surfaces the
    // sheet — a denied prompt must not leave an empty sheet over the explanatory snackbar.
    fun openPicker() {
        onChooseFolders { pickerOpen = true }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("backup-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Backup", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backup-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
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
                            text = if (currentName != null) "$done of $total · $currentName" else "$done of $total",
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
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onBackupNow,
                enabled = enabled && !running,
                modifier = Modifier.testTag("backup-now"),
            ) {
                Text("Back up now")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { openPicker() },
                modifier = Modifier.testTag("backup-folders"),
            ) {
                Text(
                    text = if (selectedFolderCount > 0) "Choose folders ($selectedFolderCount)" else "Choose folders",
                    style = MaterialTheme.typography.labelLarge,
                )
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
