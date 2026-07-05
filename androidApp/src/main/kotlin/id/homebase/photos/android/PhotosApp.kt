package id.homebase.photos.android

import android.app.Application
import id.homebase.api.ActivityProvider
import id.homebase.api.storage.SecureStorage
import id.homebase.api.storage.SharedPreferences
import id.homebase.photos.android.work.MediaWatchScheduler
import id.homebase.photos.initKoin
import id.homebase.photos.initializeStorage
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class PhotosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Process-scoped Context for components that resolve it before an Activity exists.
        ActivityProvider.initializeApplicationContext(this)
        // Static keystore-backed storage singletons (used once auth/DB features land).
        SecureStorage.initialize(this)
        SharedPreferences.initialize(this)
        // androidContext() feeds platformModule()'s AndroidFileOperationsProvider.
        initKoin {
            androidLogger()
            androidContext(this@PhotosApp)
        }
        // Open the encrypted DB before the root gate resolves the auth graph — the gate
        // resolves it synchronously in MainActivity.onCreate (which runs after this).
        runBlocking { initializeStorage() }
        // Re-arm the media watch every process start (same-id replace is idempotent);
        // covers force-stop, which clears scheduled jobs.
        MediaWatchScheduler.schedule(this)
    }
}
