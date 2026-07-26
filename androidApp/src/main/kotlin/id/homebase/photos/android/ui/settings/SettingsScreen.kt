@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.photos.android.ui.components.LibraryRow
import id.homebase.photos.settings.SettingsUiState
import id.homebase.photos.settingsViewModel

/**
 * Stateful Settings entry point: resolves the shared SettingsViewModel from Koin (AppShell
 * resolver idiom) and refreshes the owner profile on entry.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = remember { settingsViewModel() }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenBackup = onOpenBackup,
        onLogout = onLogout,
        modifier = modifier,
    )
}

/**
 * Stateless Settings screen: account header (initials avatar, display name, identity), then
 * Backup / About / Sign out rows over the shared [LibraryRow] component. Sign out confirms via
 * a dialog before invoking [onLogout] (which the Activity runs on its lifecycleScope — the
 * confirm here is UI-only). testTags: `settings-root` / `settings-account` / `settings-backup` /
 * `settings-about` / `settings-signout` / `logout-confirm`.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // BuildConfig is disabled for this module — the package manager is the version source.
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-root"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            AccountHeader(state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            LibraryRow(
                icon = Icons.Outlined.CloudUpload,
                label = "Backup",
                testTag = "settings-backup",
                onClick = onOpenBackup,
            )
            LibraryRow(
                icon = Icons.Outlined.Info,
                label = "About",
                testTag = "settings-about",
                trailingLabel = versionName,
            )
            LibraryRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                label = "Sign out",
                testTag = "settings-signout",
                onClick = { showLogoutDialog = true },
            )
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again to see your photos.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    modifier = Modifier.testTag("logout-confirm"),
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/** Account header: initials in a `primaryContainer` circle, display name bold, identity secondary. */
@Composable
private fun AccountHeader(state: SettingsUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("settings-account"),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.initials ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = state.displayName ?: state.identity ?: "Not signed in",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.identity?.let { identity ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = identity,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
