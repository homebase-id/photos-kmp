package id.homebase.api.browser

// Adapted from chat-kmp pin e67130cd: photos-specific scheme so Chat and Photos don't claim each other's redirects.
actual object RedirectConfig {
    actual val scheme: String = "homebase-photos"

    actual fun buildRedirectUri(clientId: String): String {
        return "homebase-photos://$clientId/authorization-code-callback"
    }
}
