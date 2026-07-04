import SwiftUI
import Shared

/// §5.1 login screen. Renders entirely from `LoginUiState`; the ViewModel owns logic and the
/// YouAuth browser handoff (via `LoginModel`). No password ever reaches this app — the owner
/// authenticates on their own identity server.
struct LoginView: View {
    @Environment(\.colorScheme) private var scheme
    @StateObject private var model = LoginModel()
    @FocusState private var fieldFocused: Bool

    private var phase: LoginPhase { model.uiState?.phase ?? .loggedOut }
    private var identity: String { model.uiState?.identity ?? "" }

    /// Proxies the field to the ViewModel: the shared state is the single source of truth, so the
    /// binding reads `uiState.identity` and writes through `onIdentityChange`.
    private var identityBinding: Binding<String> {
        Binding(
            get: { model.uiState?.identity ?? "" },
            set: { model.vm.onIdentityChange(value: $0) }
        )
    }

    var body: some View {
        ZStack {
            PhotosColor.background(scheme)
                .ignoresSafeArea(.container)

            VStack(spacing: PhotosMetrics.space16) {
                Spacer()

                Image(systemName: "leaf.fill")
                    .font(.system(size: 36))
                    .foregroundColor(PhotosColor.primary(scheme))

                Text("Homebase Photos")
                    .font(PhotosFont.display)
                    .foregroundColor(PhotosColor.onBackground(scheme))

                Text("Your photos, your server.")
                    .font(PhotosFont.bodyMedium)
                    .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                    .multilineTextAlignment(.center)

                identityField
                    .padding(.top, PhotosMetrics.space8)

                submitButton

                if let error = model.uiState?.error {
                    Text(error)
                        .font(PhotosFont.caption)
                        .foregroundColor(PhotosColor.error(scheme))
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("login-error")
                }

                Spacer()

                Text("You sign in on your own server. This app never sees a password.")
                    .font(PhotosFont.caption)
                    .foregroundColor(PhotosColor.onSurfaceVariantDim(scheme))
                    .multilineTextAlignment(.center)
                    .padding(.bottom, PhotosMetrics.space16)
            }
            .padding(.horizontal, PhotosMetrics.screenEdge)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("login-root")
        .task { model.start() }
    }

    // MARK: - Identity field

    private var identityField: some View {
        TextField("your.identity.id", text: identityBinding)
            .font(PhotosFont.body)
            .foregroundColor(PhotosColor.onSurface(scheme))
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            .autocorrectionDisabled()
            .submitLabel(.go)
            .focused($fieldFocused)
            .onSubmit { model.vm.startLogin() }
            .disabled(phase != .loggedOut)
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: PhotosMetrics.radiusMd)
                    .fill(PhotosColor.surface(scheme))
            )
            .overlay(
                RoundedRectangle(cornerRadius: PhotosMetrics.radiusMd)
                    .stroke(
                        fieldFocused ? PhotosColor.primary(scheme) : PhotosColor.outline(scheme),
                        lineWidth: 1
                    )
            )
            .accessibilityIdentifier("login-id-field")
    }

    // MARK: - Submit button

    private var submitButton: some View {
        Button {
            fieldFocused = false
            model.vm.startLogin()
        } label: {
            ZStack {
                RoundedRectangle(cornerRadius: PhotosMetrics.radiusXl)
                    .fill(PhotosColor.primary(scheme))
                submitLabel
                    .font(PhotosFont.label)
                    .foregroundColor(PhotosColor.onPrimary(scheme))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 52)
        }
        .buttonStyle(.plain)
        .disabled(submitDisabled)
        .opacity(submitDisabled ? 0.6 : 1)
        .accessibilityIdentifier("login-submit")
    }

    @ViewBuilder
    private var submitLabel: some View {
        switch phase {
        case .loggedOut:
            Text("Sign in with Homebase")
        case .awaitingBrowser, .authenticating:
            HStack(spacing: PhotosMetrics.space8) {
                ProgressView().tint(PhotosColor.onPrimary(scheme))
                Text("Connecting…")
            }
        case .loggedIn:
            Text("Signed in")
        }
    }

    private var submitDisabled: Bool {
        switch phase {
        case .loggedOut:
            return identity.isEmpty
        case .awaitingBrowser, .authenticating, .loggedIn:
            return true
        }
    }
}
