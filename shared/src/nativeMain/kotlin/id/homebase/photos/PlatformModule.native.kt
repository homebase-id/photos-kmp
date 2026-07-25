package id.homebase.photos

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.sync.database.DatabaseDriverFactory
import id.homebase.photos.backup.PHAssetCrawler
import id.homebase.photos.backup.PhotoLibraryCrawler
import org.koin.core.module.Module
import org.koin.dsl.module

/** iOS platform deps for apiModule. IOSFileOperationsProvider uses Foundation singletons — no context needed. */
actual fun platformModule(): Module = module {
    single<FileOperationsProvider> { IOSFileOperationsProvider() }
    // SQLCipher driver factory (arg-free on native); consumed by initializeStorage().
    single { DatabaseDriverFactory() }
    // iOS photo-library crawler over the Photos framework (PHAsset).
    single<PhotoLibraryCrawler> { PHAssetCrawler() }
}
