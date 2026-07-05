package id.homebase.photos.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Hairline gap between grid cells — reads as the background (white/black) showing through.
internal val GRID_GAP: Dp = 1.5.dp

// Vertical space cleared below the timeline grid so the last row isn't hidden by the backup card.
internal val CARD_CLEARANCE: Dp = 96.dp

/** Columns for the current viewport width (design-system §4.4 breakpoints). */
internal fun gridColumnsFor(widthDp: Float): Int = when {
    widthDp < 360f -> 3
    widthDp < 600f -> 4
    widthDp < 840f -> 6
    widthDp < 1200f -> 8
    else -> 10
}

/** Photos-shaped skeleton for a first load — no per-cell spinner (design-system §5.2). */
@Composable
fun SkeletonGrid(
    columns: Int,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    cellCount: Int = columns * 12,
    gap: Dp = GRID_GAP,
    cellShape: Shape = RectangleShape,
    testTag: String = "timeline-skeleton",
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + CARD_CLEARANCE,
        ),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        userScrollEnabled = false,
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
    ) {
        items(count = cellCount) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(cellShape)
                    .background(placeholderColor()),
            )
        }
    }
}

/** Centered nothing-here state (design-system §5.2). Callers supply the copy. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    testTag: String = "timeline-empty",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Failed-first-load state with a retry action (design-system §5.2). */
@Composable
fun ErrorState(
    message: String?,
    onRetry: () -> Unit,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    title: String = "Couldn't load photos",
    testTag: String = "timeline-error",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 32.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message ?: "Please check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}

/** Footer row shown while the next page loads (AUI-08). */
@Composable
fun FooterLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("timeline-footer-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
