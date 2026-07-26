package id.homebase.photos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.YouAuthState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Flat UI state for the Settings screen. All null while unauthenticated. */
data class SettingsUiState(
    val identity: String? = null,
    val displayName: String? = null,
    val initials: String? = null,
)

/**
 * Narrow-seam constructor (raw flows + function ref) — OwnerSessionRepository is a final class
 * with no fake, so the VM takes exactly the seams it observes/calls instead of the repository.
 * NO logout() intent here: logout must run on a scope that survives the authState flip this
 * VM's teardown rides on (see YouAuthFlowManager.logout).
 */
class SettingsViewModel(
    private val authState: StateFlow<YouAuthState>,
    private val ownerSession: StateFlow<OwnerSession?>,
    private val loadOwner: suspend (OdinId) -> Unit,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> =
        combine(authState, ownerSession) { auth, session ->
            if (auth !is YouAuthState.Authenticated) return@combine SettingsUiState()
            SettingsUiState(
                identity = auth.identity.domainName,
                // Repository emits its own identity fallback on load — don't duplicate it here.
                displayName = session?.displayName,
                initials = session?.initials(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /** Loads the owner profile unless the session already holds it for the current identity. */
    fun refresh() {
        val auth = authState.value as? YouAuthState.Authenticated ?: return
        if (ownerSession.value?.odinId == auth.identity) return
        viewModelScope.launch { loadOwner(auth.identity) }
    }
}
