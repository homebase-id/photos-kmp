package id.homebase.photos

import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.photos.backup.MediaStoreCrawler
import id.homebase.photos.backup.PhotoLibraryCrawler
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android platform deps for apiModule. FileOperationsProvider needs the app Context (cacheDir, ContentResolver). */
actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { AndroidFileOperationsProvider(androidContext()) }
    // SQLCipher driver factory needs the app Context; consumed by initializeStorage().
    single { DatabaseDriverFactory(androidContext()) }
    // Real photo-library source for backup — MediaStore images. Context reaches ContentResolver.
    single<PhotoLibraryCrawler> { MediaStoreCrawler(androidContext()) }
}
