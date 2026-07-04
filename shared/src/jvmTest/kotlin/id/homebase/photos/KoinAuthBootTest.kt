package id.homebase.photos

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.di.apiModule
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.photos.auth.AuthGateway
import id.homebase.photos.auth.LoginViewModel
import id.homebase.photos.data.PhotosRepository
import id.homebase.photos.data.PhotosRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Offline boot proof for the AUTH slice of the DI graph. Complements
 * [ApiModuleBootTest] (protocol layer) by resolving the auth chain end to end:
 * LoginViewModel -> AuthGateway -> YouAuthFlowManager -> DriveSyncManager. A green
 * assert here proves DriveSyncManager (its first ctor dep) and all five of its
 * apiModule deps are reachable — the wiring the mock-repo timeline path never exercised.
 *
 * Precondition: DriveSyncManager's `databaseManager` resolves via apiModule's
 * `single { DatabaseManager.appDb }`, and `appDb` is a lateinit populated only by
 * [DatabaseManager.initialize]. This test stands up an in-memory DB in [initDb] to
 * model the startup step the app must perform before resolving the auth graph.
 * NOTE: at real startup PhotosApp.onCreate and IosBootstrap.initializeApp now run
 * `initializeStorage()` (DatabaseManager.initializeWithRecovery) right after initKoin,
 * so appDb is populated before the root gate resolves youAuthFlowManager(). This test
 * substitutes its own in-memory driver for that step so it stays offline and device-free.
 */
class KoinAuthBootTest {

    // In-memory OdinDatabase (schema auto-created by DatabaseManager's init). Guarded so a
    // second test sharing this JVM — where the lateinit is already set — is a no-op.
    @BeforeTest
    fun initDb() {
        try {
            runBlocking {
                DatabaseManager.initialize { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) }
            }
        } catch (e: IllegalStateException) {
            // "Already initialized" — a prior test stood the DB up; reuse it.
        }
    }

    @Test
    fun authGraphResolvesYouAuthFlowManagerAndLoginChain() {
        val app = koinApplication {
            modules(platformModule(), apiModule, photosModule)
        }

        val koin = app.koin

        // Resolving this transitively constructs DriveSyncManager (its first ctor dep).
        // If DriveSyncManager or any of its five apiModule deps were unbound, this throws.
        assertNotNull(
            koin.get<YouAuthFlowManager>(),
            "YouAuthFlowManager (+ its DriveSyncManager dep) must resolve from the assembled graph",
        )

        // The root session gate observes AuthGateway; the login screen drives LoginViewModel.
        // Both funnel through the same YouAuthFlowManager, so they also exercise the binding.
        assertNotNull(koin.get<AuthGateway>(), "AuthGateway must resolve from photosModule")
        assertNotNull(koin.get<LoginViewModel>(), "LoginViewModel must resolve from photosModule")

        // The timeline now binds the REAL repo (mock removed). Resolving it proves the swap
        // wired: driveId literal + DriveSyncManager + DatabaseManager + CredentialsManager +
        // HomebaseImageLoader all reachable offline.
        assertNotNull(
            koin.get<PhotosRepository>() as? PhotosRepositoryImpl,
            "PhotosRepository must resolve to the real PhotosRepositoryImpl",
        )

        app.close()
    }
}
