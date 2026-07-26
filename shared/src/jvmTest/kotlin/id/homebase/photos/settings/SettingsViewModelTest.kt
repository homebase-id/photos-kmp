package id.homebase.photos.settings

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.YouAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SettingsViewModel over raw fixture flows + a recording loadOwner lambda — the narrow-seam
 * constructor exists precisely so no repository fake is needed (OwnerSessionRepository is final).
 * Same dispatcher conventions as SearchViewModelTest, minus its real-DB settle interleaving:
 * nothing here hops off the test scheduler.
 */
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val identity = OdinId("alice.dotyou.cloud")
    private val authState = MutableStateFlow<YouAuthState>(YouAuthState.Unauthenticated)
    private val ownerSession = MutableStateFlow<OwnerSession?>(null)
    private val loadCalls = mutableListOf<OdinId>()

    private fun vm() = SettingsViewModel(
        authState = authState,
        ownerSession = ownerSession,
        loadOwner = { loadCalls += it },
    )

    private fun authenticated(id: OdinId = identity) = YouAuthState.Authenticated(
        identity = id,
        clientAuthToken = "cat",
        sharedSecret = "ss",
    )

    private fun session(
        odinId: OdinId = identity,
        displayName: String? = "Alice Anders",
        firstName: String? = "Alice",
        surName: String? = "Anders",
    ) = OwnerSession(
        odinId = odinId,
        displayName = displayName,
        firstName = firstName,
        surName = surName,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unauthenticated_stateIsAllNull() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.identity)
        assertNull(state.displayName)
        assertNull(state.initials)
    }

    @Test
    fun authenticated_identityMapped_displayNameFromOwnerSession() = runTest(dispatcher) {
        authState.value = authenticated()
        ownerSession.value = session()

        val vm = vm()
        advanceUntilIdle()

        assertEquals("alice.dotyou.cloud", vm.state.value.identity)
        assertEquals("Alice Anders", vm.state.value.displayName)
    }

    @Test
    fun ownerSessionNull_displayNameNull_identityStillSet() = runTest(dispatcher) {
        authState.value = authenticated()

        val vm = vm()
        advanceUntilIdle()

        assertEquals("alice.dotyou.cloud", vm.state.value.identity)
        assertNull(vm.state.value.displayName)
        assertNull(vm.state.value.initials)
    }

    @Test
    fun initials_comeFromOwnerSessionInitials() = runTest(dispatcher) {
        authState.value = authenticated()
        ownerSession.value = session(firstName = "Alice", surName = "Anders")

        val vm = vm()
        advanceUntilIdle()

        assertEquals("AA", vm.state.value.initials)
    }

    @Test
    fun refresh_whenAuthenticated_callsLoadOwnerWithIdentity() = runTest(dispatcher) {
        authState.value = authenticated()

        val vm = vm()
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(identity), loadCalls)
    }

    @Test
    fun refresh_whenOwnerSessionAlreadyLoadedForIdentity_skipsLoad() = runTest(dispatcher) {
        authState.value = authenticated()
        ownerSession.value = session()

        val vm = vm()
        vm.refresh()
        advanceUntilIdle()

        assertTrue(loadCalls.isEmpty(), "already-loaded session for this identity must not reload")
    }

    @Test
    fun refresh_whenUnauthenticated_doesNotCallLoadOwner() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()

        assertTrue(loadCalls.isEmpty(), "unauthenticated refresh must be a no-op")
    }
}
