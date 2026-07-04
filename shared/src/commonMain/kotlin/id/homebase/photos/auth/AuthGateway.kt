package id.homebase.photos.auth

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.TargetDriveAccessRequest
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.photos.PhotoConfig
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin seam over [YouAuthFlowManager] so [LoginViewModel] is unit-testable — the
 * manager's ctor needs the whole HTTP/sync graph, this narrows it to what login uses.
 */
interface AuthGateway {
    val authState: StateFlow<YouAuthState>
    suspend fun authorize(identity: OdinId): String
    suspend fun handleCallback(url: String)
    suspend fun cancelAuth()
    suspend fun logout()
}

/** Photos drive access request. Alias/type are sent DASHED-canonical (Uuid.toString()) — chat-kmp's proven format; undashed hex fails to match existing drives in the owner console. */
internal fun photosDriveAccessRequest(): TargetDriveAccessRequest = TargetDriveAccessRequest(
    alias = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS).toString(),
    type = Uuid.parseHex(PhotoConfig.DRIVE_TYPE).toString(),
    name = "Photo Library",
    description = "Place for your memories",
    permissions = listOf(DrivePermission.Read, DrivePermission.Write),
)

/** Real gateway: forwards to the Koin-held [YouAuthFlowManager], pinning Photos' app + drive request. */
class YouAuthGateway(private val manager: YouAuthFlowManager) : AuthGateway {
    override val authState: StateFlow<YouAuthState> get() = manager.authState

    // Request Write now (alongside Read) so the later backup wave needs no re-consent.
    override suspend fun authorize(identity: OdinId): String = manager.authorize(
        identity = identity,
        appId = PhotoConfig.APP_ID,
        appName = PhotoConfig.APP_NAME,
        drives = listOf(photosDriveAccessRequest()),
    )

    override suspend fun handleCallback(url: String) = manager.handleCallback(url)
    override suspend fun cancelAuth() = manager.cancelAuth()
    override suspend fun logout() = manager.logout()
}
