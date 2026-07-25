@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.viewer

import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.disposeVideo
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.prepareVideo
import id.homebase.photos.viewer.VideoHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.uuid.ExperimentalUuidApi

// Outlives any page composition: temp-file deletion must run even as the page disposes.
private val videoCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Matches the still-page chrome idle window so both page kinds feel identical.
private const val CONTROLLER_TIMEOUT_MS = 3_000

/**
 * One video page: decrypts to a temp file via [prepareVideo], then plays it with ExoPlayer under
 * a media3 [PlayerView]. The PlayerView controller (play/pause + scrubber) IS the bottom chrome
 * for video — its visibility is two-way synced with the viewer chrome via [chromeVisible] /
 * [onChromeVisibleChange]. Spinner while preparing; "Can't play this video" when [prepareVideo]
 * returns null (segmented/HLS or decrypt failure). Player release + temp-file dispose on
 * page-change/dispose.
 */
@androidx.annotation.OptIn(UnstableApi::class) // PlayerView controller wiring is @UnstableApi
@Composable
fun VideoPlayerPage(
    photo: PhotoItem,
    isActive: Boolean,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var handle by remember(photo.fileId) { mutableStateOf<VideoHandle?>(null) }
    var failed by remember(photo.fileId) { mutableStateOf(false) }

    LaunchedEffect(photo.fileId) {
        if (handle == null && !failed) {
            handle = prepareVideo(photo)
            failed = handle == null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val prepared = handle
        when {
            failed -> VideoUnplayableState()
            prepared == null -> CircularProgressIndicator(color = PhotosTheme.extended.onOverlay)
            else -> {
                val player = remember(prepared.filePath) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(File(prepared.filePath))))
                        prepare()
                    }
                }
                DisposableEffect(prepared.filePath) {
                    onDispose {
                        player.release()
                        videoCleanupScope.launch { disposeVideo(prepared) }
                    }
                }
                // Swiping away pauses; the temp file lives until the page leaves the pager window.
                LaunchedEffect(isActive) {
                    if (isActive) player.play() else player.pause()
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    onChromeVisibleChange(visibility == View.VISIBLE)
                                },
                            )
                        }
                    },
                    update = { view ->
                        if (chromeVisible) view.showController() else view.hideController()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("viewer-video-surface"),
                )
            }
        }
    }
}

/** Small centered "can't play" state — segmented/HLS videos and decrypt failures land here. */
@Composable
private fun VideoUnplayableState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.VideocamOff,
            contentDescription = null,
            tint = PhotosTheme.extended.onOverlayDim,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Can't play this video",
            style = MaterialTheme.typography.bodyMedium,
            color = PhotosTheme.extended.onOverlay,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
