package id.homebase.photos.android.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.photos.android.ui.components.LeafGlyph
import id.homebase.photos.android.ui.theme.PhotosTheme
import id.homebase.photos.auth.LoginPhase
import id.homebase.photos.auth.LoginUiState
import id.homebase.photos.auth.LoginViewModel

// Content column never grows past this — keeps the field/button readable on tablets (design §5.1).
private val MAX_CONTENT_WIDTH = 420.dp

/**
 * Stateful login entry point. Collects the shared [LoginViewModel]'s [LoginUiState] and binds the
 * identity + submit callbacks. It deliberately does NOT collect [LoginViewModel.events]; the
 * Activity owns event handling (opening the YouAuth browser) so the redirect can re-enter the same
 * task (see MainActivity).
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onIdentityChange = viewModel::onIdentityChange,
        onSubmit = viewModel::startLogin,
        modifier = modifier,
    )
}

/**
 * Stateless login screen (design-system §5.1). A full-bleed neutral ground carries a centered column —
 * leaf glyph, wordmark, one line of subtext, the Homebase-ID field, and the primary pill — with a
 * quiet caption pinned to the bottom. The four [LoginPhase] states each render distinctly:
 *  - [LoginPhase.LoggedOut]      → editable field, "Sign in with Homebase" (enabled iff id present).
 *  - [LoginPhase.AwaitingBrowser]/[LoginPhase.Authenticating] → field locked, inline spinner + "Connecting…".
 *  - [LoginPhase.LoggedIn]       → "Signed in" (transitional — the root swaps to the timeline).
 * [state]-driven so UI tests can drive every phase without the Koin graph.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onIdentityChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isLoggedOut = state.phase == LoginPhase.LoggedOut

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .testTag("login-root"),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = LeafGlyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Homebase Photos",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your photos, your server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = state.identity,
                onValueChange = onIdentityChange,
                enabled = isLoggedOut,
                singleLine = true,
                label = { Text("Homebase ID") },
                placeholder = { Text("your.identity.id") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login-id-field"),
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onSubmit,
                enabled = isLoggedOut && state.identity.isNotBlank(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("login-submit"),
            ) {
                when (state.phase) {
                    LoginPhase.AwaitingBrowser, LoginPhase.Authenticating -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…")
                    }
                    LoginPhase.LoggedIn -> Text("Signed in")
                    LoginPhase.LoggedOut -> Text("Sign in with Homebase")
                }
            }

            // Errors state the fix, never apologize (design-system §5.1).
            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("login-error"),
                )
            }
        }

        Text(
            text = "You sign in on your own server. This app never sees a password.",
            style = MaterialTheme.typography.bodySmall,
            color = PhotosTheme.extended.onSurfaceVariantDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        )
    }
}
