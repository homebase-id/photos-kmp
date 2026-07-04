import XCTest

/// Strict-TDD gate for the logout affordance on the iOS timeline (account button → confirmation).
///
/// The account toolbar button opens a `confirmationDialog` carrying a destructive "Log out" option.
/// This test asserts only that the button and the confirm option appear — it deliberately does NOT
/// confirm logout, since the `-uiTestTimeline` seam may share auth state with the running session.
/// If the timeline never appears within 10s (backend-agnostic, mirrors TimelineGridUITest), XCTSkip.
final class LogoutUITest: XCTestCase {
    func testAccountButtonShowsLogoutConfirmation() throws {
        let app = XCUIApplication()
        // Bypass the session gate: a fresh install is logged out, so the auth-gated root would
        // otherwise show the login screen instead of the timeline.
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        // The account button lives in the timeline's nav toolbar — its presence is our proxy for
        // "the timeline rendered". No timeline within 10s → skip (test env may be logged out/offline).
        let account = app.buttons["account-button"]
        guard account.waitForExistence(timeout: 10) else {
            throw XCTSkip("Timeline did not appear within 10s — cannot exercise the logout dialog.")
        }

        account.tap()

        // The confirmation dialog surfaces a destructive "Log out" option. Match by identifier, with
        // a label fallback in case the action sheet does not propagate the accessibility identifier.
        let confirm = app.buttons["logout-confirm"]
        let confirmByLabel = app.buttons["Log out"]
        XCTAssertTrue(
            confirm.waitForExistence(timeout: 5) || confirmByLabel.waitForExistence(timeout: 5),
            "Logout confirmation option did not appear after tapping the account button"
        )

        // Do NOT confirm logout — dismiss the sheet so the test leaves the session untouched.
        let cancel = app.buttons["Cancel"]
        if cancel.exists { cancel.tap() }
    }
}
