package id.homebase.photos.android.ui.nav

/**
 * Typed destinations for the app-shell [androidx.navigation.compose.NavHost]. String routes (not
 * kotlinx type-safe nav) so androidApp stays out of the serialization plugin. Push destinations
 * carry their arg in the path; the shell keeps the un-Bundle-able viewer payload in a side channel.
 */
sealed class Route(val path: String) {
    data object Photos : Route("photos")
    data object Collections : Route("collections")
    data object Search : Route("search")

    data object AlbumDetail : Route("album/{albumId}") {
        const val ARG = "albumId"
        fun path(albumId: String) = "album/$albumId"
    }

    data object Viewer : Route("viewer/{index}") {
        const val ARG = "index"
        fun path(index: Int) = "viewer/$index"
    }
}
