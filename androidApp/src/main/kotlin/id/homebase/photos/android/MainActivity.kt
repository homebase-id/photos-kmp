package id.homebase.photos.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.photos.auth.LoginEvent
import id.homebase.photos.auth.LoginViewModel
import id.homebase.photos.backup.BackupManager
import id.homebase.photos.backup.BackupViewModel
import id.homebase.photos.timeline.TimelineEvent
import id.homebase.photos.timeline.TimelineViewModel
import id.homebase.photos.android.ui.backup.BackupStatusCard
import id.homebase.photos.android.work.BackupScheduler
import id.homebase.photos.android.ui.buildHomebaseImageLoader
import id.homebase.photos.android.ui.home.AppShell
import id.homebase.photos.android.ui.login.LoginScreen
import id.homebase.photos.android.ui.splash.SplashScreen
import id.homebase.photos.android.ui.theme.PhotosTheme
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {

    // Session manager resolved once as a field so onNewIntent/onResume can forward without touching
    // composition state (design/plan 007 §2). Koin `single` — same instance the root observes.
    private val youAuth: YouAuthFlowManager by lazy { GlobalContext.get().get<YouAuthFlowManager>() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // Android 12+ cold-start splash → Theme.HomebasePhotos.Starting (A3).
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // timeline grid renders edge-to-edge (design-system §4.1)
        // Cold-start deep link: the browser may have relaunched us straight into the redirect.
        forwardCallback(intent)
        setContent {
            PhotosTheme {
                val koin = remember { GlobalContext.get() }
                // Root gate: splash / login / timeline switch purely on the shared session state.
                val authState by youAuth.authState.collectAsStateWithLifecycle()
                when (authState) {
                    is YouAuthState.Initializing ->
                        // Branded splash while the session bootstraps (A3).
                        SplashScreen()
                    is YouAuthState.Authenticated -> {
                        // Shared headless ViewModel (StateFlow<TimelineUiState>) resolved from Koin.
                        val vm = remember { koin.get<TimelineViewModel>() }
                        // Shared backup ViewModel — same Koin singleton the BackupWorker resolves.
                        val backupVm = remember { koin.get<BackupViewModel>() }
                        // Coil loader wired with the encrypted Homebase fetcher/keyer.
                        val imageLoader = remember { buildHomebaseImageLoader(this, koin) }
                        // Same Koin singleton the BackupWorker/card resolve — used to reconcile on launch.
                        val backupManager = remember { koin.get<BackupManager>() }

                        val snackbarHostState = remember { SnackbarHostState() }

                        // One-time events → transient snackbars (design: events on a separate SharedFlow).
                        LaunchedEffect(vm) {
                            vm.events.collect { event ->
                                when (event) {
                                    is TimelineEvent.Error -> snackbarHostState.showSnackbar(event.message)
                                    is TimelineEvent.Deleted ->
                                        snackbarHostState.showSnackbar("${event.count} deleted")
                                }
                            }
                        }

                        // Launch reconcile (plan 012 A): restore() seeds the true persisted enabled flag
                        // — after process death the card showed a stale "Off" while the persistent 6h job
                        // kept running — then re-arm or cancel the scheduler to match. Idempotent, so it
                        // also re-arms after a reinstall cleared WorkManager's DB. Keyed to the
                        // authenticated branch → runs once per session (re-runs after logout→login).
                        LaunchedEffect(Unit) {
                            backupManager.restore()
                            if (backupManager.state.value.enabled) {
                                BackupScheduler.enable(this@MainActivity)
                            } else {
                                BackupScheduler.disable(this@MainActivity)
                            }
                        }

                        // Real NavHost back-stack owns the viewer/album/create/search destinations now
                        // (A1) — the Activity only keeps the VM lifecycle effects above.
                        AppShell(
                            timelineViewModel = vm,
                            imageLoader = imageLoader,
                            snackbarHostState = snackbarHostState,
                            // Logout runs in lifecycleScope (survives the recomposition the authState
                            // flip triggers); the root gate above then swaps back to the login screen.
                            onLogout = { lifecycleScope.launch { youAuth.logout() } },
                            backupCard = {
                                BackupStatusCard(
                                    viewModel = backupVm,
                                    snackbarHostState = snackbarHostState,
                                )
                            },
                        )
                    }
                    else -> {
                        // Unauthenticated / Authenticating / Error all share this branch, so the
                        // remembered LoginViewModel survives the transient Authenticating flip mid-login.
                        val loginVm = remember { koin.get<LoginViewModel>() }
                        LoginScreen(loginVm)
                        // The Activity (not the screen) owns login events so the browser it opens can
                        // redirect back into this same singleTask instance.
                        LaunchedEffect(loginVm) {
                            loginVm.events.collect { event ->
                                when (event) {
                                    is LoginEvent.OpenUrl ->
                                        // Custom Tab keeps the YouAuth login in-app; the homebase-photos://
                                        // redirect intent-filter still routes the callback back here.
                                        CustomTabsIntent.Builder().build()
                                            .launchUrl(this@MainActivity, Uri.parse(event.url))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() current for any later reads
        forwardCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // Auto-cancels a stuck Authenticating when the user backs out of the browser (plan 007 §2).
        lifecycleScope.launch { youAuth.onAppResumed() }
    }

    /** Forward a `homebase-photos://` YouAuth redirect to the session manager to complete login. */
    private fun forwardCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "homebase-photos") {
            lifecycleScope.launch { youAuth.handleCallback(data.toString()) }
        }
    }
}
