@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.photos.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import id.homebase.photos.albums.AlbumSummary
import id.homebase.photos.android.ui.homebaseImageData
import kotlin.uuid.ExperimentalUuidApi

// Album-cover corner radius, Google-Photos style.
internal val ALBUM_COVER_RADIUS = 14.dp

/**
 * One album tile: square cover (the album's cover photo, else a flat neutral gray) clipped to the
 * 14dp album-cover radius, with the album name below — nothing else.
 */
@Composable
fun AlbumCard(
    summary: AlbumSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
) {
    val cover = summary.cover
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("album-card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ALBUM_COVER_RADIUS))
                .background(placeholderColor()),
        ) {
            if (cover != null && imageLoader != null) {
                AsyncImage(
                    model = homebaseImageData(photo = cover, requestedSize = GRID_THUMB_SIZE),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = summary.album.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
