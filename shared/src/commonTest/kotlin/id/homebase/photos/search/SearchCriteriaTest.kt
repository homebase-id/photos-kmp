package id.homebase.photos.search

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SearchCriteriaTest {

    @Test
    fun default_isEmpty() {
        assertTrue(SearchCriteria().isEmpty)
    }

    @Test
    fun anyNonDefaultField_makesItNotEmpty() {
        assertFalse(SearchCriteria(fromUserDate = 1L).isEmpty)
        assertFalse(SearchCriteria(toUserDate = 1L).isEmpty)
        assertFalse(SearchCriteria(isVideo = false).isEmpty)
        assertFalse(SearchCriteria(albumIds = listOf(Uuid.random())).isEmpty)
    }
}
