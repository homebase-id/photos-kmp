package id.homebase.photos.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

// Day-header label ("Wed, Jun 21") — UTC to match the shared month bucketing.
internal val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

/** Month-boundary header ("March 2022") — bold plain text directly on the background, no band. */
@Composable
fun MonthHeader(
    title: String,
    modifier: Modifier = Modifier,
    testTag: String = "timeline-month-header",
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall, // monthHeader slot (Theme.kt)
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)
            .testTag(testTag),
    )
}

/** Day header ("Wed, Mar 30") — the primary header: semibold, full onSurface, plain text. */
@Composable
fun DaySubhead(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall, // dateSubhead slot (Theme.kt)
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
            .testTag("timeline-day-header"),
    )
}
