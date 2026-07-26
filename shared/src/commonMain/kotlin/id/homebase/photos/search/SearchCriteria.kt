package id.homebase.photos.search

import kotlin.uuid.Uuid

/**
 * Metadata search predicate — date range, type (photo/video), album membership. No filename,
 * no ML (Batch E scope ruling). `null`/empty fields mean "unconstrained" on that axis.
 */
data class SearchCriteria(
    val fromUserDate: Long? = null,
    val toUserDate: Long? = null,
    val isVideo: Boolean? = null, // null = any
    val albumIds: List<Uuid> = emptyList(), // empty = no album constraint
) {
    val isEmpty: Boolean
        get() = fromUserDate == null && toUserDate == null && isVideo == null && albumIds.isEmpty()
}
