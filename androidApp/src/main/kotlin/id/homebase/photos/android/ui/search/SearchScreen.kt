@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.photos.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import id.homebase.photos.albums.AlbumSummary
import id.homebase.photos.android.ui.components.AlbumPickerSheet
import id.homebase.photos.android.ui.components.EmptyState
import id.homebase.photos.android.ui.components.ErrorState
import id.homebase.photos.android.ui.components.MonthHeader
import id.homebase.photos.android.ui.components.PhotoGridCell
import id.homebase.photos.android.ui.components.SkeletonGrid
import id.homebase.photos.android.ui.components.gridColumnsFor
import id.homebase.photos.domain.AlbumItem
import id.homebase.photos.domain.PhotoItem
import id.homebase.photos.search.SearchUiState
import id.homebase.photos.search.SearchViewModel
import id.homebase.photos.search.TypeFilter
import id.homebase.photos.timeline.TimelineSection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val CHIP_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d")

// DateRangePicker reports the END day's UTC midnight — push it to the day's last millisecond so
// a same-day pick (e.g. "today only") doesn't exclude everything shot after midnight.
private const val END_OF_DAY_OFFSET_MS = 24L * 60 * 60 * 1000 - 1

/**
 * Stateful Search screen: renders the shared [SearchViewModel] over the stateless overload below.
 * [albums] comes from the shell's already-loaded [id.homebase.photos.albums.AlbumsViewModel] state
 * (Collections/album-picker share it) so opening Search doesn't trigger a second album load.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    albums: List<AlbumSummary>,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        albums = albums,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSubmit = viewModel::submit,
        onDateRangeChange = viewModel::setDateRange,
        onTypeFilterChange = viewModel::setTypeFilter,
        onAlbumFilterChange = viewModel::setAlbumFilter,
        onClearFilters = viewModel::clearFilters,
        onRecentClick = { query ->
            viewModel.onQueryChange(query)
            viewModel.submit()
        },
        onPhotoClick = onPhotoClick,
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

/**
 * Stateless Search screen (Batch E). A search field (IME action = submit) over a Date/Type/Album
 * filter chip row; below that: recents while idle, a skeleton while searching, an empty state or
 * the month-sectioned results grid once a search has run. Filter setters re-run the search
 * immediately (see [SearchViewModel]), so the chips alone can drive a search with no text typed.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    albums: List<AlbumSummary> = emptyList(),
    onQueryChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onDateRangeChange: (Long?, Long?) -> Unit = { _, _ -> },
    onTypeFilterChange: (TypeFilter) -> Unit = {},
    onAlbumFilterChange: (AlbumItem?) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onRecentClick: (String) -> Unit = {},
    onPhotoClick: (PhotoItem) -> Unit = {},
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("search-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Search", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("search-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (!state.isIdle) {
                            TextButton(onClick = onClearFilters, modifier = Modifier.testTag("search-clear")) {
                                Text("Clear")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Search your photos") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 8.dp)
                        .testTag("search-field"),
                )
                SearchFilterRow(
                    state = state,
                    albums = albums,
                    imageLoader = imageLoader,
                    onDateRangeChange = onDateRangeChange,
                    onTypeFilterChange = onTypeFilterChange,
                    onAlbumFilterChange = onAlbumFilterChange,
                )
                // A filter change re-runs the search over whatever's already on screen (see
                // SearchViewModel) — without this, a slow/failing re-search over populated
                // results would look identical to success. Only the empty-results case gets the
                // full-screen Skeleton/ErrorState below.
                if (state.isSearching && state.sections.isNotEmpty()) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search-progress"),
                    )
                }
                if (state.error != null && state.sections.isNotEmpty()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("search-error-banner"),
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = gridColumnsFor(maxWidth.value)
                when {
                    state.isSearching && state.sections.isEmpty() ->
                        SkeletonGrid(columns = columns, innerPadding = contentPadding, testTag = "search-skeleton")

                    state.error != null && state.sections.isEmpty() ->
                        ErrorState(
                            message = state.error,
                            onRetry = onSubmit,
                            innerPadding = contentPadding,
                            title = "Search failed",
                            testTag = "search-error",
                        )

                    // Not just state.isIdle: a query becomes non-blank the moment the user types
                    // a character, but nothing has actually run yet until submit(). Gating on
                    // isIdle alone would fall through to an empty results grid — a blank screen —
                    // for the whole time the user is composing a query. Keep showing recents
                    // until a search has actually completed (or is in flight — caught above).
                    !state.hasSearched && !state.isSearching && state.sections.isEmpty() ->
                        RecentSearchesList(
                            recent = state.recent,
                            onRecentClick = onRecentClick,
                            innerPadding = contentPadding,
                        )

                    state.isEmpty ->
                        EmptyState(
                            title = "No results",
                            message = "Try a different date range, type, or album.",
                            innerPadding = contentPadding,
                            testTag = "search-empty",
                        )

                    else ->
                        SearchResultsGrid(
                            sections = state.sections,
                            columns = columns,
                            innerPadding = contentPadding,
                            imageLoader = imageLoader,
                            onPhotoClick = onPhotoClick,
                        )
                }
            }
        }
    }
}

/** Date / Type / Album filter chips (`search-chip-date/type/album`) — each shows its active value. */
@Composable
private fun SearchFilterRow(
    state: SearchUiState,
    albums: List<AlbumSummary>,
    imageLoader: ImageLoader?,
    onDateRangeChange: (Long?, Long?) -> Unit,
    onTypeFilterChange: (TypeFilter) -> Unit,
    onAlbumFilterChange: (AlbumItem?) -> Unit,
) {
    // Plain Row + horizontalScroll, not LazyRow: exactly three fixed chips, never a dynamic
    // list — a long date-range label plus a long album name can still exceed a narrow screen
    // (e.g. 360dp), so the row needs to scroll rather than clip the album chip off-screen.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DateFilterChip(
            fromUserDate = state.fromUserDate,
            toUserDate = state.toUserDate,
            onChange = onDateRangeChange,
        )
        TypeFilterChip(value = state.typeFilter, onChange = onTypeFilterChange)
        AlbumFilterChip(
            album = state.albumFilter,
            albums = albums,
            imageLoader = imageLoader,
            onChange = onAlbumFilterChange,
        )
    }
}

@Composable
private fun DateFilterChip(fromUserDate: Long?, toUserDate: Long?, onChange: (Long?, Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val active = fromUserDate != null || toUserDate != null
    val label = if (active) formatDateRange(fromUserDate, toUserDate) else "Date"

    FilterChip(
        selected = active,
        onClick = { showPicker = true },
        label = { Text(label) },
        trailingIcon = if (active) {
            {
                IconButton(onClick = { onChange(null, null) }, modifier = Modifier.size(FilterChipDefaults.IconSize)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear date filter")
                }
            }
        } else {
            null
        },
        modifier = Modifier.testTag("search-chip-date"),
    )

    if (showPicker) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = fromUserDate,
            initialSelectedEndDateMillis = toUserDate,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val end = pickerState.selectedEndDateMillis?.plus(END_OF_DAY_OFFSET_MS)
                        onChange(pickerState.selectedStartDateMillis, end)
                        showPicker = false
                    },
                    modifier = Modifier.testTag("search-date-confirm"),
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
            modifier = Modifier.testTag("search-date-dialog"),
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
}

private fun TypeFilter.chipLabel(): String = when (this) {
    TypeFilter.ALL -> "Type"
    TypeFilter.PHOTOS -> "Photos"
    TypeFilter.VIDEOS -> "Videos"
}

@Composable
private fun TypeFilterChip(value: TypeFilter, onChange: (TypeFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val active = value != TypeFilter.ALL

    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(value.chipLabel()) },
            trailingIcon = if (active) {
                {
                    IconButton(
                        onClick = { onChange(TypeFilter.ALL) },
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Clear type filter") }
                }
            } else {
                null
            },
            modifier = Modifier.testTag("search-chip-type"),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TypeFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.chipLabel()) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    modifier = Modifier.testTag("search-type-option-${option.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun AlbumFilterChip(
    album: AlbumItem?,
    albums: List<AlbumSummary>,
    imageLoader: ImageLoader?,
    onChange: (AlbumItem?) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    FilterChip(
        selected = album != null,
        onClick = { showSheet = true },
        label = { Text(album?.name ?: "Album") },
        trailingIcon = if (album != null) {
            {
                IconButton(onClick = { onChange(null) }, modifier = Modifier.size(FilterChipDefaults.IconSize)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear album filter")
                }
            }
        } else {
            null
        },
        modifier = Modifier.testTag("search-chip-album"),
    )

    if (showSheet) {
        AlbumPickerSheet(
            albums = albums,
            onAlbumSelected = { picked ->
                onChange(picked)
                showSheet = false
            },
            // No "create from search" flow — the picker's new-album row just dismisses (C3's
            // create-album affordance lives in the Create sheet, not here).
            onNewAlbum = { showSheet = false },
            onDismiss = { showSheet = false },
            sheetState = sheetState,
            imageLoader = imageLoader,
        )
    }
}

/** Recent queries (`search-recent`), most-recent-first — tap re-runs that query. */
@Composable
private fun RecentSearchesList(recent: List<String>, onRecentClick: (String) -> Unit, innerPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .testTag("search-recent"),
    ) {
        if (recent.isEmpty()) {
            item {
                Text(
                    text = "Search by date, type, or album — or type an album name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            item {
                Text(
                    text = "Recent searches",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(items = recent, key = { it }) { query ->
                ListItem(
                    headlineContent = { Text(query) },
                    leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRecentClick(query) }
                        .testTag("search-recent-item"),
                )
            }
        }
    }
}

/** Month-sectioned results grid (`search-results-grid`) — tap opens the viewer, same as Favorites. */
@Composable
private fun SearchResultsGrid(
    sections: List<TimelineSection>,
    columns: Int,
    innerPadding: PaddingValues,
    imageLoader: ImageLoader?,
    onPhotoClick: (PhotoItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("search-results-grid"),
    ) {
        sections.forEach { section ->
            item(
                key = "month-${section.title}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "month-header",
            ) {
                MonthHeader(title = section.title, modifier = Modifier.semantics { heading() })
            }
            items(
                items = section.items,
                key = { it.fileId.toString() },
                contentType = { "cell" },
            ) { photo ->
                PhotoGridCell(photo = photo, imageLoader = imageLoader, onClick = { onPhotoClick(photo) })
            }
        }
    }
}

private fun formatDateRange(fromUserDate: Long?, toUserDate: Long?): String {
    fun fmt(ms: Long) = CHIP_DATE_FORMATTER.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))
    return when {
        fromUserDate != null && toUserDate != null -> "${fmt(fromUserDate)} – ${fmt(toUserDate)}"
        fromUserDate != null -> "From ${fmt(fromUserDate)}"
        toUserDate != null -> "Until ${fmt(toUserDate)}"
        else -> "Date"
    }
}
