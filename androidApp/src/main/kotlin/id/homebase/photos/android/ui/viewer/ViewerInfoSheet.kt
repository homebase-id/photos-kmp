@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.photos.domain.PhotoItem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.ExperimentalUuidApi

// UTC like every other date in the app — userDate is EXIF capture millis, not wall-clock local.
private val INFO_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val INFO_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
private val INFO_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

/**
 * Viewer info panel (`viewer-info-sheet`): capture date/time, dimensions + type, and last-modified.
 * No filename / byte-size rows — PhotoItem doesn't carry them (deliberate contract omission).
 */
@Composable
fun ViewerInfoSheet(photo: PhotoItem, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("viewer-info-sheet"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val captured = Instant.ofEpochMilli(photo.userDate).atZone(ZoneOffset.UTC)
            InfoRow(
                icon = Icons.Outlined.CalendarToday,
                label = INFO_DATE_FORMATTER.format(captured),
                support = INFO_TIME_FORMATTER.format(captured),
            )
            InfoRow(
                icon = if (photo.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image,
                label = if (photo.isVideo) "Video" else "Photo",
                support = listOfNotNull(
                    "${photo.pixelWidth} × ${photo.pixelHeight}",
                    photo.payloadContentType,
                ).joinToString(" · "),
            )
            photo.lastModified?.let { modified ->
                InfoRow(
                    icon = Icons.Outlined.Schedule,
                    label = "Modified",
                    support = INFO_DATETIME_FORMATTER
                        .format(Instant.ofEpochMilli(modified).atZone(ZoneOffset.UTC)),
                )
            }
            Spacer(modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

/** One leading-icon detail row: primary label + dimmed supporting line. */
@Composable
private fun InfoRow(icon: ImageVector, label: String, support: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!support.isNullOrEmpty()) {
                Text(
                    text = support,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
