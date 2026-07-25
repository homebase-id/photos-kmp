package id.homebase.photos

import co.touchlab.kermit.Logger
import id.homebase.api.di.apiModule
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.wipeOutboxStaging
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.photos.albums.AlbumDetailViewModel
import id.homebase.photos.albums.AlbumsViewModel
import id.homebase.photos.auth.AuthGateway
import id.homebase.photos.auth.LoginViewModel
import id.homebase.photos.auth.YouAuthGateway
import id.homebase.photos.backup.BackupEnabledStore
import id.homebase.photos.backup.BackupFolderSelectionStore
import id.homebase.photos.backup.BackupLedger
import id.homebase.photos.backup.BackgroundBackup
import id.homebase.photos.backup.BackupManager
import id.homebase.photos.backup.BackupViewModel
import id.homebase.photos.backup.OutboxPhotoUploadEnqueuer
import id.homebase.photos.backup.PhotoFileBuilder
import id.homebase.photos.backup.PhotoUploadEnqueuer
import id.homebase.photos.data.AlbumsRepository
import id.homebase.photos.data.AlbumsRepositoryImpl
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.data.PhotosRepositoryImpl
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.timeline.TimelineViewModel
import id.homebase.photos.viewer.VideoHandle
import id.homebase.photos.viewer.ViewerViewModel
import kotlin.uuid.Uuid
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val photosModule = module {
    factory { HelloViewModel() }

    // HomebaseImageLoader is the only PhotosRepositoryImpl dep not already in apiModule.
    // DriveFileProvider is an apiModule factory; FileOperationsProvider a platformModule single.
    single { HomebaseImageLoader(driveFileProvider = get(), fileOperationsProvider = get()) }

    // Real timeline repo. driveId is the Photos drive alias literal — the SAME value used as
    // the mandatoryDrives mount key below (DriveMainIndex.driveId IS the alias, no resolution).
    single<PhotosRepository> {
        PhotosRepositoryImpl(
            driveId = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS),
            driveSyncManager = get(),
            databaseManager = get(),
            credentialsManager = get(),
            imageLoader = get(),
            driveFileProvider = get(),
            fileOps = get(),
        )
    }

    factory { TimelineViewModel(get(), get()) } // eventBus: sync-completion reload

    // Albums: files off the local index, membership via server queryBatch (both apiModule deps).
    single<AlbumsRepository> {
        AlbumsRepositoryImpl(
            driveId = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS),
            databaseManager = get(),
            credentialsManager = get(),
            driveQueryProvider = get(),
        )
    }
    factory { AlbumsViewModel(get()) }

    // Sync engine backing YouAuthFlowManager. single because its authState-driven mount
    // must survive across the login screen and root gate (one instance, one driveStatuses).
    // All five injectables come from apiModule; the mandatory-drives map is this app's
    // one invariant sync target, encoded here (the only app-level decision in this graph).
    single {
        DriveSyncManager(
            driveQueryProvider = get(), credentialsManager = get(), eventBus = get(),
            scope = get(), databaseManager = get(),
            // Photos drive is this app's one mandatory sync target (mounted post-auth).
            mandatoryDrives = mapOf(Uuid.parseHex(PhotoConfig.DRIVE_ALIAS) to "Photo Library"),
            // Outbox send-gate: start() brings it online + drains, stop()/pause() offline.
            // Replaces chat-kmp's WS-connection-driven setOnline (we have no WebSocket).
            outboxSync = get(),
        )
    }

    // Auth: YouAuthFlowManager MUST be single — its init restores the session, so the
    // root session gate and the login screen must share one instance and one authState.
    // Its first ctor dep (DriveSyncManager) is bound just above; the remaining four
    // (CredentialsManager, HttpClient, DriveFileProviderCached, PublicProfileProviderCached)
    // come from apiModule.
    // outboxSync (named): brings the outbox online + drains on every session-active
    // transition (restore/login) and offline on logout — the authoritative mirror of
    // chat-kmp's WS-driven setOnline. clearPlatformCaches is the logout hook.
    single {
        val fileOps: FileOperationsProvider = get()
        YouAuthFlowManager(
            get(), get(), get(), get(), get(),
            // Durable outbox staging (#842) sits OUTSIDE cacheDir, so the logout DB wipe
            // (driveSyncManager.clearStorage) drops the outbox rows but orphans their staged
            // payloads. Wipe them here so rows and staged payloads leave together (mirrors
            // chat-kmp AppModule). Best-effort — a failure can't block the authState flip.
            clearPlatformCaches = {
                runCatching { wipeOutboxStaging(fileOps.getOutboxStagingDirectory()) }
                    .onFailure {
                        Logger.w(tag = "YouAuthFlowManager", throwable = it) { "logout outbox-staging wipe failed" }
                    }
            },
            outboxSync = get(),
        )
    }
    single<AuthGateway> { YouAuthGateway(get()) }
    factory { LoginViewModel(get()) }

    // --- Auto-backup pipeline (Android-first). The crawler is bound per-platform: real MediaStore
    // on Android, a no-op stub on iOS/JVM. Everything else is platform-agnostic shared logic. ---
    // Ledger reuses the existing KeyValue table (no new .sq → no DATABASE_VERSION bump).
    single { BackupLedger(get<DatabaseManager>().keyValue) }
    // Folder selection (D6): persisted over the same KeyValue table (no new .sq). Default = none.
    single { BackupFolderSelectionStore(get<DatabaseManager>().keyValue) }
    // Enabled flag (plan 012 A): persisted over the same KeyValue table (no new .sq). Default = off.
    single { BackupEnabledStore(get<DatabaseManager>().keyValue) }
    single { PhotoFileBuilder(fileOps = get(), driveId = Uuid.parseHex(PhotoConfig.DRIVE_ALIAS)) }
    // Upload seam over the COPIED outbox — never a parallel uploader.
    single<PhotoUploadEnqueuer> { OutboxPhotoUploadEnqueuer(get()) }
    single { BackupManager(crawler = get(), ledger = get(), builder = get(), uploader = get(), selectionStore = get(), enabledStore = get(), scope = get()) }
    // Background pass entrypoint — shared across platforms; only the trigger (WorkManager / BGTask) is native.
    single { BackgroundBackup(youAuth = get(), enabledStore = get(), backupManager = get(), repository = get(), outboxSync = get()) }
    factory { BackupViewModel(get()) }
}

/**
 * Per-platform Koin bindings the copied `apiModule` needs to boot. Currently the
 * only boot-blocking dep is `FileOperationsProvider` — apiModule's
 * `createdAtStart` `StartupCacheAudit` resolves it eagerly at startKoin. Android
 * binds it with `androidContext()`; iOS/JVM construct it arg-free. Mirrors
 * chat-kmp `homebase-core`'s `expect fun platformModule()`.
 */
expect fun platformModule(): Module

/** All Koin modules, in resolution order. platformModule first so apiModule's eager deps resolve. */
val allModules get() = listOf(platformModule(), apiModule, photosModule)

/** One-time storage bootstrap. MUST complete before the auth graph resolves (root gates resolve it at launch). */
suspend fun initializeStorage() { DatabaseManager.initializeWithRecovery(KoinPlatform.getKoin().get()) }

private var started = false

/**
 * Idempotent Koin boot. Called from Android `Application.onCreate` (via the
 * Android overload that wires `androidContext`) and iOS `initializeApp()`.
 * [appDeclaration] lets the Android side inject `androidContext()`/`androidLogger()`;
 * it's a no-op on platforms whose `platformModule()` needs no Koin-held context.
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    if (started) return
    started = true
    org.koin.core.context.startKoin {
        appDeclaration()
        modules(allModules)
    }
}

/** iOS-callable factory (Swift has no Koin DSL). */
fun helloViewModel(): HelloViewModel = HelloViewModel()

/** iOS-callable factory: resolves the timeline ViewModel from Koin (Swift has no DSL). */
fun timelineViewModel(): TimelineViewModel = KoinPlatform.getKoin().get()

/** iOS-callable factory: resolves the login ViewModel from Koin (Swift has no DSL). */
fun loginViewModel(): LoginViewModel = KoinPlatform.getKoin().get()

/** iOS-callable factory: resolves the albums ViewModel from Koin (Swift has no DSL). */
fun albumsViewModel(): AlbumsViewModel = KoinPlatform.getKoin().get()

/** iOS-callable: the shared background-backup entrypoint. A BGTask handler calls run() (SKIE async). */
fun backgroundBackup(): BackgroundBackup = KoinPlatform.getKoin().get()

/** iOS-callable factory: the backup settings ViewModel from Koin. */
fun backupViewModel(): BackupViewModel = KoinPlatform.getKoin().get()

/** iOS-callable factory: album detail VM for [album] over the shared repository. */
fun albumDetailViewModel(album: AlbumItem): AlbumDetailViewModel =
    AlbumDetailViewModel(album, KoinPlatform.getKoin().get())

/** iOS-callable: the shared auth manager whose .authState the root session gate observes. */
fun youAuthFlowManager(): YouAuthFlowManager = KoinPlatform.getKoin().get()

/** iOS-callable: decoded thumbnail bytes via the repository (SKIE exposes only top-level funcs). */
suspend fun loadThumbnailBytes(item: PhotoItem, maxDim: Int): ByteArray? =
    KoinPlatform.getKoin().get<PhotosRepository>().loadThumbnailBytes(item, maxDim)

/** iOS-callable factory: viewer VM over [items] starting at [initialIndex] (Android builds it via koin.get()). */
fun viewerViewModel(items: List<PhotoItem>, initialIndex: Int): ViewerViewModel =
    ViewerViewModel(items, initialIndex, KoinPlatform.getKoin().get())

/** iOS-callable: full-res decrypted payload bytes via the repository. */
suspend fun loadOriginalBytes(item: PhotoItem): ByteArray? =
    KoinPlatform.getKoin().get<PhotosRepository>().loadOriginalBytes(item)

/** iOS-callable: decrypt [item]'s video to a temp file the platform player can open. */
suspend fun prepareVideo(item: PhotoItem): VideoHandle? =
    KoinPlatform.getKoin().get<PhotosRepository>().prepareVideo(item)

/** iOS-callable: delete a prepared video's temp file. */
suspend fun disposeVideo(handle: VideoHandle) =
    KoinPlatform.getKoin().get<PhotosRepository>().disposeVideo(handle)
