package id.homebase.photos.android.ui.login

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.auth.LoginPhase
import id.homebase.photos.auth.LoginUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI flow-test for the login screen. Drives the stateless [LoginScreen] overload with fixed
 * [LoginUiState] values so each [LoginPhase] and the error line assert distinctly without the shared
 * ViewModel / Koin graph.
 */
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loggedOutWithBlankIdentity_submitDisabled() {
        composeRule.setContent {
            PhotosTheme {
                LoginScreen(
                    state = LoginUiState(phase = LoginPhase.LoggedOut, identity = "", error = null),
                )
            }
        }

        composeRule.onNodeWithTag("login-submit")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun loggedOutWithIdentity_submitEnabledAndClickInvokesOnSubmit() {
        var submitted = false
        composeRule.setContent {
            PhotosTheme {
                LoginScreen(
                    state = LoginUiState(phase = LoginPhase.LoggedOut, identity = "sam.homebase.id", error = null),
                    onSubmit = { submitted = true },
                )
            }
        }

        composeRule.onNodeWithTag("login-submit")
            .assertIsEnabled()
            .performClick()

        assertTrue(submitted)
    }

    @Test
    fun awaitingBrowser_showsConnectingAndLocksField() {
        composeRule.setContent {
            PhotosTheme {
                LoginScreen(
                    state = LoginUiState(phase = LoginPhase.AwaitingBrowser, identity = "sam.homebase.id", error = null),
                )
            }
        }

        composeRule.onNodeWithText("Connecting…").assertExists()
        composeRule.onNodeWithTag("login-id-field").assertIsNotEnabled()
    }

    @Test
    fun error_showsInlineErrorLine() {
        composeRule.setContent {
            PhotosTheme {
                LoginScreen(
                    state = LoginUiState(
                        phase = LoginPhase.LoggedOut,
                        identity = "sam.homebase.id",
                        error = "Couldn't reach your identity",
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("login-error").assertExists()
        composeRule.onNodeWithText("Couldn't reach your identity").assertExists()
    }

    @Test
    fun typingIdentity_invokesOnIdentityChange() {
        var changed = ""
        composeRule.setContent {
            PhotosTheme {
                LoginScreen(
                    state = LoginUiState(phase = LoginPhase.LoggedOut, identity = "", error = null),
                    onIdentityChange = { changed = it },
                )
            }
        }

        composeRule.onNodeWithTag("login-id-field").performTextInput("sam")

        assertEquals("sam", changed)
    }
}
