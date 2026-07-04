# Batch 1 — Shared contracts (interfaces for parallel coding)

Stable contracts so code-writing agents can fan out in parallel without colliding. All shared types
live in `id.homebase.photos.*` (`shared/src/commonMain`). ViewModels = `androidx.lifecycle.ViewModel`
exposing `StateFlow<…UiState>` (flat `data class`, `_uiState.update {}`); one-time events on a
separate `SharedFlow`. Native screens (SwiftUI + Compose) render these — duplicate nothing but views.

Backed by the copied protocol layer: `DriveQueryProvider.queryBatch`, `DriveSync`,
`DriveUploadProvider`, `YouAuthFlowManager`, `CredentialsManager`, `DriveMainIndex` (local),
and the Coil pipeline `HomebaseImageFetcher`/`HomebaseImageLoader`/`HomebaseImageData`.

## Domain

```kotlin
data class PhotoItem(
    val fileId: Uuid,
    val uniqueId: Uuid?,
    val userDate: Long,          // EXIF capture millis — timeline sort key
    val isVideo: Boolean,        // PhotoConfig.isVideo(payload.contentType)
    val pixelWidth: Int,         // from previewThumbnail dims (aspect + placeholder)
    val pixelHeight: Int,
    val previewPlaceholder: String?, // inline base64 webp blur placeholder
    val driveId: Uuid,           // + payloadKey → build HomebaseImageData for Coil
    val payloadKey: String,      // PhotoConfig.PAYLOAD_KEY ("dflt_key")
)

data class Album(val id: Uuid, val name: String, val coverFileId: Uuid?, val count: Int)
```

`PhotoItem` → Coil: native layers build a `HomebaseImageData(fileId, driveId, payloadKey, size)`
choosing `225x300` (grid) / `900x1200` (viewer) / original; placeholder = `previewPlaceholder`.

## Repositories (interfaces; impls wrap `homebase-api`)

```kotlin
interface PhotosRepository {
    fun observePhotos(): Flow<List<PhotoItem>>             // local DriveMainIndex, userDate DESC
    suspend fun loadPage(beforeUserDate: Long?, limit: Int): List<PhotoItem>
    suspend fun sync()                                     // DriveSync pull of the Photos drive
}

interface AlbumRepository {
    suspend fun listAlbums(): List<Album>                 // queryBatch fileType=900
    suspend fun photosInAlbum(albumId: Uuid): List<PhotoItem> // queryBatch fileType=0, tag=albumId
}

// [SCHEMA GATE] BackupService file-build format needs owner sign-off before implementation.
interface BackupService {
    suspend fun backup(assetPaths: List<String>)          // pick→EXIF→build→encrypt→Outbox→upload
    fun progress(): Flow<BackupProgress>
}
data class BackupProgress(val pending: Int, val uploaded: Int, val failed: Int, val running: Boolean)
```

## ViewModels + UiState

```kotlin
// Login (auth wiring; owner performs the actual login on-device — ping them)
enum class LoginPhase { LoggedOut, AwaitingBrowser, Authenticating, LoggedIn }
data class LoginUiState(val phase: LoginPhase = LoginPhase.LoggedOut,
                        val identity: String = "", val error: String? = null)
class LoginViewModel {
    val state: StateFlow<LoginUiState>
    fun onIdentityChange(value: String)
    fun startLogin()           // YouAuthFlowManager.authorize(...) → opens URL; event = OpenUrl
    fun onCallback(url: String) // handleCallback → CredentialsManager
    fun logout()
}

// Timeline (the heart of the MVP)
data class TimelineUiState(
    val isLoading: Boolean = true,
    val sections: List<TimelineSection> = emptyList(),  // month-grouped for sticky headers
    val pagedItems: List<PhotoItem> = emptyList(),       // flat list backing the viewer pager
    val endReached: Boolean = false,
    val error: String? = null,
)
data class TimelineSection(val title: String /* "June 2026" */, val items: List<PhotoItem>)
class TimelineViewModel {
    val state: StateFlow<TimelineUiState>
    fun refresh()              // sync() then reload from local index
    fun loadMore()             // paginate older by userDate
}

// Viewer
data class ViewerUiState(val items: List<PhotoItem> = emptyList(), val index: Int = 0)
class ViewerViewModel {
    val state: StateFlow<ViewerUiState>
    fun setIndex(index: Int)
}

// Albums
data class AlbumsUiState(val isLoading: Boolean = true,
                         val albums: List<Album> = emptyList(), val error: String? = null)
class AlbumsViewModel {
    val state: StateFlow<AlbumsUiState>
    fun refresh()
    fun openAlbum(albumId: Uuid)   // → photosInAlbum; event = OpenAlbum(items)
}

// Backup [SCHEMA GATE]
data class BackupUiState(val progress: BackupProgress = BackupProgress(0,0,0,false))
class BackupViewModel {
    val state: StateFlow<BackupUiState>
    fun pickAndBackup()   // native picker → BackupService.backup(...)
}
```

## Gates (do not skip)
- **Login**: build runnable on both devices, then **ping owner** to perform login (no fabricated identity).
- **Backup `BackupService` file format**: **owner schema sign-off required** before writing the upload/file-build path.
- **Timeline perf**: low-end-device frame-budget profiling gate (Argent) before the grid is "done".
