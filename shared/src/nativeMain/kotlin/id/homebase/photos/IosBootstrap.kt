package id.homebase.photos

import id.homebase.api.client.drives.query.DriveQueryProvider
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatformTools

private var appInitialized = false

/** Native entry point called from Swift @main. Idempotent. */
fun initializeApp() {
    if (appInitialized) return
    appInitialized = true
    initKoin()
    // Open the encrypted DB before the root session gate resolves the auth graph
    // (which pulls DatabaseManager.appDb). Local file op — safe under runBlocking.
    runBlocking { initializeStorage() }
}

/**
 * iOS-callable proof that the protocol layer (apiModule) is wired into the booted
 * Koin container: resolves a [DriveQueryProvider] offline and returns its class
 * name. Swift has no Koin DSL, so this exposes the typed resolution.
 */
fun resolvedProtocolDepName(): String =
    KoinPlatformTools.defaultContext().get().get<DriveQueryProvider>()
        .let { it::class.simpleName ?: "DriveQueryProvider" }
