package id.homebase.photos.auth

import id.homebase.api.youauth.DrivePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the YouAuth drive request: alias/type must be sent DASHED-canonical
 * (Uuid.toString()) so the owner console matches the EXISTING Photos drive. Undashed 32-hex
 * strings silently fail to match and offer to create a new drive.
 */
class DriveAccessRequestTest {

    // 6483b7b1f71bd43eb6896c86148668cc -> dashed 8-4-4-4-12, lowercase
    private val expectedAlias = "6483b7b1-f71b-d43e-b689-6c86148668cc"
    // 2af68fe72fb84896f39f97c59d60813a -> dashed 8-4-4-4-12, lowercase
    private val expectedType = "2af68fe7-2fb8-4896-f39f-97c59d60813a"

    @Test fun aliasIsDashedCanonicalUuid() {
        assertEquals(expectedAlias, photosDriveAccessRequest().alias)
    }

    @Test fun typeIsDashedCanonicalUuid() {
        assertEquals(expectedType, photosDriveAccessRequest().type)
    }

    @Test fun requestsReadAndWrite() {
        val permissions = photosDriveAccessRequest().permissions
        assertTrue(DrivePermission.Read in permissions, "must request Read")
        assertTrue(DrivePermission.Write in permissions, "must request Write")
    }

    // Format regression guard: undashed hex is exactly the bug we are fixing.
    @Test fun aliasAndTypeContainDashes() {
        val req = photosDriveAccessRequest()
        assertTrue('-' in req.alias, "alias must be dashed-canonical, was ${req.alias}")
        assertTrue('-' in req.type, "type must be dashed-canonical, was ${req.type}")
    }
}
