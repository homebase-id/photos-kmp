@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import id.homebase.photos.disposeVideo
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.loadOriginalBytes
import id.homebase.photos.prepareVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.ExperimentalUuidApi

private const val DEFAULT_IMAGE_MIME = "image/jpeg"
private const val DEFAULT_VIDEO_MIME = "video/mp4"

/**
 * Shares [photo] via an ACTION_SEND chooser. Decrypted bytes land ONLY under `cacheDir/share/`
 * — the single sanctioned plaintext-on-disk exception (with prepareVideo temp files) to the
 * no-plaintext-disk rule — exposed through the manifest FileProvider. Stills come from
 * [loadOriginalBytes]; videos reuse the decrypt-to-temp path and are copied into the share dir
 * so the FileProvider path stays scoped. Returns false when the payload can't be produced.
 */
suspend fun sharePhoto(context: Context, photo: PhotoItem): Boolean = withContext(Dispatchers.IO) {
    val mime = photo.payloadContentType
        ?: if (photo.isVideo) DEFAULT_VIDEO_MIME else DEFAULT_IMAGE_MIME
    val file = (
        if (photo.isVideo) prepareVideoShareFile(context, photo)
        else prepareStillShareFile(context, photo, mime)
        ) ?: return@withContext false

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // launched from a composable, not an Activity ref
    }
    context.startActivity(chooser)
    true
}

private suspend fun prepareStillShareFile(context: Context, photo: PhotoItem, mime: String): File? {
    val bytes = loadOriginalBytes(photo) ?: return null
    val file = shareFileFor(context, photo, extensionFor(mime, "jpg"))
    file.writeBytes(bytes)
    return file
}

private suspend fun prepareVideoShareFile(context: Context, photo: PhotoItem): File? {
    val handle = prepareVideo(photo) ?: return null
    return try {
        val source = File(handle.filePath)
        val target = shareFileFor(context, photo, source.extension.ifEmpty { "mp4" })
        source.copyTo(target, overwrite = true)
        target
    } finally {
        disposeVideo(handle) // the share copy is ours; the viewer temp goes away
    }
}

private fun shareFileFor(context: Context, photo: PhotoItem, extension: String): File {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    return File(dir, "homebase_${photo.fileId}.$extension")
}

private fun extensionFor(mime: String, fallback: String): String =
    mime.substringAfter('/', missingDelimiterValue = "").ifEmpty { fallback }
