import XCTest

/// Strict-TDD gate for the logout affordance (Batch G path: account button → Settings sheet →
/// Sign out → confirmation).
///
/// The Settings sheet's Sign out row opens a `confirmationDialog` carrying a destructive "Log out"
/// option. This test asserts only that the path reaches the confirm option — it deliberately does
/// NOT confirm logout, since the `-uiTestTimeline` seam may share auth state with the running
/// session. If the timeline never appears within 10s (backend-agnostic, mirrors
/// TimelineGridUITest), XCTSkip.
final class LogoutUITest: XCTestCase {
    func testSettingsSignOutShowsLogoutConfirmation() throws {
        let app = XCUIApplication()
        // Bypass the session gate: a fresh install is logged out, so the auth-gated root would
        // otherwise show the login screen instead of the timeline.
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        // The account button lives in the timeline's nav toolbar — its presence is our proxy for
        // "the timeline rendered". No timeline within 10s → skip (test env may be logged out/offline).
        let account = app.buttons["account-button"]
        guard account.waitForExistence(timeout: 10) else {
            throw XCTSkip("Timeline did not appear within 10s — cannot exercise the logout flow.")
        }

        account.tap()

        // The account button now opens the Settings sheet; logout lives behind its Sign out row.
        let signOut = app.descendants(matching: .any)["settings-signout"]
        guard signOut.waitForExistence(timeout: 5) else {
            XCTFail("Settings sheet did not present a Sign out row")
            return
        }
        signOut.firstMatch.tap()

        // The confirmation dialog surfaces a destructive "Log out" option. Match by identifier, with
        // a label fallback in case the action sheet does not propagate the accessibility identifier.
        let confirm = app.buttons["logout-confirm"]
        let confirmByLabel = app.buttons["Log out"]
        XCTAssertTrue(
            confirm.waitForExistence(timeout: 5) || confirmByLabel.waitForExistence(timeout: 5),
            "Logout confirmation option did not appear after tapping Sign out"
        )

        // Do NOT confirm logout — dismiss the sheet so the test leaves the session untouched.
        let cancel = app.buttons["Cancel"]
        if cancel.exists { cancel.tap() }
    }
}
