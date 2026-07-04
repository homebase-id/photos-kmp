package id.homebase.photos

import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.di.apiModule
import id.homebase.api.file.FileOperationsProvider
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Offline boot proof for the protocol-layer DI graph. Boots the exact module
 * list initKoin() uses (platformModule() + apiModule + photosModule) via a
 * throwaway KoinApplication — no global startKoin, no server. Asserts:
 *  1. createdAtStart eager singletons resolve (apiModule's StartupCacheAudit
 *     needs FileOperationsProvider from platformModule — the one boot-blocking dep).
 *  2. A representative protocol-layer factory (DriveQueryProvider) resolves from
 *     the assembled graph, proving apiModule's HttpClient + CredentialsManager
 *     bindings are reachable without any network.
 */
class ApiModuleBootTest {

    @Test
    fun graphBootsOfflineAndResolvesProtocolDeps() {
        // koinApplication() runs createAtStart singletons at build time — so if the
        // platform FileOperationsProvider weren't bound, StartupCacheAudit would throw here.
        val app = koinApplication {
            modules(platformModule(), apiModule, photosModule)
        }

        val koin = app.koin

        // The boot-blocking eager dep.
        assertNotNull(koin.get<FileOperationsProvider>(), "FileOperationsProvider must be bound by platformModule()")

        // A protocol-layer provider that depends only on apiModule bindings
        // (HttpClient + CredentialsManager) — resolves with no DB and no server.
        assertNotNull(koin.get<DriveQueryProvider>(), "DriveQueryProvider must resolve from the assembled graph")

        app.close()
    }
}
