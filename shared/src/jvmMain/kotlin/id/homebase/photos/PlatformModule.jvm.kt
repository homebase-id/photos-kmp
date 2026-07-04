package id.homebase.photos

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.photos.backup.PhotoLibraryCrawler
import id.homebase.photos.backup.StubPhotoLibraryCrawler
import org.koin.core.module.Module
import org.koin.dsl.module

/** JVM platform deps for apiModule (also covers the offline boot test). No context needed. */
actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { JvmFileOperationsProvider() }
    // SQLCipher driver factory (arg-free on JVM); consumed by initializeStorage().
    single { DatabaseDriverFactory() }
    // No JVM photo library; stub keeps the graph resolvable for the boot tests.
    single<PhotoLibraryCrawler> { StubPhotoLibraryCrawler() }
}
