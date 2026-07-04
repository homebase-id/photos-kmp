package id.homebase.photos.auth

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.YouAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory [AuthGateway]: caller-controlled authState + recorded calls + configurable authorize. */
private class FakeAuthGateway(
    initial: YouAuthState = YouAuthState.Unauthenticated,
) : AuthGateway {
    val authStateFlow = MutableStateFlow(initial)
    override val authState: StateFlow<YouAuthState> = authStateFlow

    var authorizeResult: String = "https://frodo.homebase.id/api/owner/v1/youauth/authorize?state=abc"
    var authorizeThrow: Throwable? = null
    val authorizeCalls = mutableListOf<OdinId>()
    val handledCallbacks = mutableListOf<String>()
    var cancelCalls = 0
    var logoutCalls = 0

    override suspend fun authorize(identity: OdinId): String {
        authorizeCalls.add(identity)
        authorizeThrow?.let { throw it }
        return authorizeResult
    }

    override suspend fun handleCallback(url: String) {
        handledCallbacks.add(url)
    }

    override suspend fun cancelAuth() {
        cancelCalls++
        authStateFlow.value = YouAuthState.Unauthenticated
    }

    override suspend fun logout() {
        logoutCalls++
        authStateFlow.value = YouAuthState.Unauthenticated
    }
}

class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun blankIdentityShowsErrorAndDoesNotAuthorize() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        advanceUntilIdle()

        vm.startLogin()
        advanceUntilIdle()

        assertEquals("Enter your Homebase ID", vm.state.value.error)
        assertTrue(gateway.authorizeCalls.isEmpty())
    }

    // The plan's literal "not a domain!" is rescued by cleanDomain (spaces -> dots ->
    // the valid "not.a.domain"); a single-label token survives normalization yet still
    // fails OdinId validation, preserving this case's intent (documented adaptation).
    @Test
    fun invalidIdentityShowsErrorAndDoesNotAuthorize() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        advanceUntilIdle()

        vm.onIdentityChange("not-a-domain")
        vm.startLogin()
        advanceUntilIdle()

        assertEquals("That doesn't look like a Homebase ID", vm.state.value.error)
        assertTrue(gateway.authorizeCalls.isEmpty())
    }

    @Test
    fun startLoginHappyPathAuthorizesAndEmitsOpenUrl() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        val events = mutableListOf<LoginEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { events.add(it) }
        }
        advanceUntilIdle()

        vm.onIdentityChange("frodo.homebase.id")
        vm.startLogin()
        advanceUntilIdle()

        assertEquals(LoginPhase.AwaitingBrowser, vm.state.value.phase)
        assertEquals(1, gateway.authorizeCalls.size)
        assertEquals("frodo.homebase.id", gateway.authorizeCalls.first().domainName)
        assertTrue(events.any { it is LoginEvent.OpenUrl && it.url == gateway.authorizeResult })
    }

    @Test
    fun gatewayAuthenticatedMovesToLoggedIn() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        advanceUntilIdle()

        gateway.authStateFlow.value =
            YouAuthState.Authenticated(OdinId("sam.homebase.id"), "cat", "secret")
        advanceUntilIdle()

        assertEquals(LoginPhase.LoggedIn, vm.state.value.phase)
        assertEquals("sam.homebase.id", vm.state.value.identity)
    }

    @Test
    fun gatewayErrorMovesToLoggedOutWithMessage() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        advanceUntilIdle()

        gateway.authStateFlow.value = YouAuthState.Error("boom")
        advanceUntilIdle()

        assertEquals(LoginPhase.LoggedOut, vm.state.value.phase)
        assertEquals("boom", vm.state.value.error)
    }

    @Test
    fun gatewayUnauthenticatedWhileAwaitingBrowserMovesToLoggedOut() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        advanceUntilIdle()

        vm.onIdentityChange("frodo.homebase.id")
        vm.startLogin()
        advanceUntilIdle()
        assertEquals(LoginPhase.AwaitingBrowser, vm.state.value.phase)

        // Android back-out-of-browser recovery is manager-driven: onAppResumed -> cancelAuth
        // -> authState=Unauthenticated. The VM must leave AwaitingBrowser for LoggedOut rather
        // than stay stuck on "Connecting…". (Authenticating first so the following
        // Unauthenticated is a genuine StateFlow change, not a deduplicated no-op.)
        gateway.authStateFlow.value = YouAuthState.Authenticating
        advanceUntilIdle()
        assertEquals(LoginPhase.AwaitingBrowser, vm.state.value.phase)

        gateway.authStateFlow.value = YouAuthState.Unauthenticated
        advanceUntilIdle()

        assertEquals(LoginPhase.LoggedOut, vm.state.value.phase)
    }

    @Test
    fun browserDismissedDuringAwaitingBrowserCancels() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val vm = LoginViewModel(gateway)
        advanceUntilIdle()

        vm.onIdentityChange("frodo.homebase.id")
        vm.startLogin()
        advanceUntilIdle()
        assertEquals(LoginPhase.AwaitingBrowser, vm.state.value.phase)

        vm.onBrowserDismissed()
        advanceUntilIdle()

        assertEquals(1, gateway.cancelCalls)
        assertEquals(LoginPhase.LoggedOut, vm.state.value.phase)
    }
}
