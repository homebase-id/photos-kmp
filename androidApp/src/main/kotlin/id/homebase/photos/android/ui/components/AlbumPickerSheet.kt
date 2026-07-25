@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import id.homebase.photos.albums.AlbumSummary
import id.homebase.photos.domain.AlbumItem
import kotlin.uuid.ExperimentalUuidApi

// Cap so a long album list can't push "New album" off a small screen — the list scrolls instead.
private val PICKER_LIST_MAX_HEIGHT = 420.dp

/**
 * Add-to-album picker (C3). Opened from a Timeline or Viewer selection: "New album" on top, then
 * every existing album with its cover. Purely presentational — the host owns which photos are
 * being added and calls the shared `addToAlbum` / `createAlbumWithPhotos` intents.
 */
@Composable
fun AlbumPickerSheet(
    albums: List<AlbumSummary>,
    onAlbumSelected: (AlbumItem) -> Unit,
    onNewAlbum: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    imageLoader: ImageLoader? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("addto-album-sheet"),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Add to album",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            ListItem(
                headlineContent = { Text("New album") },
                leadingContent = {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                },
                colors = sheetItemColors(),
                modifier = Modifier
                    .clickable(onClick = onNewAlbum)
                    .testTag("addto-new-album"),
            )
            if (albums.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(modifier = Modifier.heightIn(max = PICKER_LIST_MAX_HEIGHT)) {
                    items(
                        items = albums,
                        key = { it.album.fileId.toString() },
                    ) { summary ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = summary.album.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                AlbumCover(
                                    cover = summary.cover,
                                    imageLoader = imageLoader,
                                    radius = 8.dp,
                                    modifier = Modifier.size(40.dp),
                                )
                            },
                            colors = sheetItemColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAlbumSelected(summary.album) }
                                .testTag("addto-album-row"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sheetItemColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)
