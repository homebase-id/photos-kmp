package id.homebase.photos.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The two home destinations. */
enum class HomeTab { Photos, Collections }

/** Two-destination bottom bar: Photos and Collections. Hidden by callers during selection. */
@Composable
fun HomeBottomBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier.testTag("bottom-nav")) {
        NavigationBarItem(
            selected = selectedTab == HomeTab.Photos,
            onClick = { onTabSelected(HomeTab.Photos) },
            icon = { Icon(Icons.Outlined.Photo, contentDescription = null) },
            label = { Text("Photos") },
            modifier = Modifier.testTag("tab-photos"),
        )
        NavigationBarItem(
            selected = selectedTab == HomeTab.Collections,
            onClick = { onTabSelected(HomeTab.Collections) },
            icon = { Icon(Icons.Outlined.Collections, contentDescription = null) },
            label = { Text("Collections") },
            modifier = Modifier.testTag("tab-collections"),
        )
    }
}
