package id.homebase.photos.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.api.exception.AuthInProgressException
import id.homebase.api.util.cleanDomain
import id.homebase.api.youauth.YouAuthState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Login progress. Native screens (SwiftUI + Compose) render off [LoginUiState.phase]. */
enum class LoginPhase { LoggedOut, AwaitingBrowser, Authenticating, LoggedIn }

/** Flat UI state for the login screen. */
data class LoginUiState(
    val phase: LoginPhase = LoginPhase.LoggedOut,
    val identity: String = "",          // current text-field value (normalized)
    val error: String? = null,          // inline error copy, cleared on edit/start
)

/** One-time events the native layer consumes (kept off the StateFlow). */
sealed interface LoginEvent {
    /** Open the YouAuth authorize URL in the platform browser. */
    data class OpenUrl(val url: String) : LoginEvent
}

/**
 * Shared login ViewModel over [AuthGateway]. Drives the identity field, kicks off the
 * browser authorize flow, and folds the gateway's [YouAuthState] into [LoginPhase].
 */
class LoginViewModel(
    private val gateway: AuthGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LoginEvent> = _events.asSharedFlow()

    init {
        // The manager owns the source of truth for auth; mirror it into phase. The
        // ROOT session gate (not this VM) observes .authState for the splash, so
        // Initializing is a no-op here.
        gateway.authState
            .onEach { reduceAuthState(it) }
            .launchIn(viewModelScope)
    }

    private fun reduceAuthState(authState: YouAuthState) {
        when (authState) {
            is YouAuthState.Authenticated -> _state.update {
                it.copy(phase = LoginPhase.LoggedIn, identity = authState.identity.domainName, error = null)
            }
            is YouAuthState.Error -> _state.update {
                it.copy(phase = LoginPhase.LoggedOut, error = authState.message)
            }
            YouAuthState.Unauthenticated -> _state.update {
                // Unauthenticated always means logged out. The collector starts in init
                // (long before any tap), so the stale-replay race the old guard feared
                // can't happen; and Android's back-out recovery (onAppResumed -> cancelAuth
                // -> Unauthenticated) is manager-driven and MUST clear AwaitingBrowser here,
                // else login stays stuck on "Connecting…". Error is left untouched.
                it.copy(phase = LoginPhase.LoggedOut)
            }
            // AwaitingBrowser/Authenticating are owned by the local funcs below.
            YouAuthState.Authenticating -> Unit
            YouAuthState.Initializing -> Unit
        }
    }

    /** Normalize the identity field as the user types; clear any stale inline error. */
    fun onIdentityChange(value: String) {
        _state.update { it.copy(identity = value.cleanDomain(), error = null) }
    }

    /** Parse the identity, request an authorize URL, and emit it for the browser. */
    fun startLogin() {
        val identity = _state.value.identity
        if (identity.isBlank()) {
            _state.update { it.copy(error = "Enter your Homebase ID") }
            return
        }
        val odinId = try {
            OdinId(identity)
        } catch (e: Exception) {
            _state.update { it.copy(error = "That doesn't look like a Homebase ID") }
            return
        }
        _state.update { it.copy(phase = LoginPhase.AwaitingBrowser, error = null) }
        viewModelScope.launch {
            try {
                val url = gateway.authorize(odinId)
                _events.tryEmit(LoginEvent.OpenUrl(url))
            } catch (e: AuthInProgressException) {
                // A flow is already running — leave phase as-is.
            } catch (e: Exception) {
                Logger.w(tag = TAG) { "authorize failed: ${e.message}" }
                _state.update { it.copy(error = e.message ?: "Couldn't start sign-in", phase = LoginPhase.LoggedOut) }
            }
        }
    }

    /** The redirect deep link came back; finalize via the gateway (result arrives on authState). */
    fun onCallback(url: String) {
        _state.update { it.copy(phase = LoginPhase.Authenticating) }
        viewModelScope.launch { gateway.handleCallback(url) }
    }

    /** User closed the browser without completing; cancel unless a callback already advanced us. */
    fun onBrowserDismissed() {
        if (_state.value.phase == LoginPhase.AwaitingBrowser) {
            _state.update { it.copy(phase = LoginPhase.LoggedOut) }
            viewModelScope.launch { gateway.cancelAuth() }
        }
    }

    /** Sign out; the authState flip drives phase back to LoggedOut. */
    fun logout() {
        viewModelScope.launch { gateway.logout() }
    }

    companion object {
        private const val TAG = "LoginViewModel"
    }
}
