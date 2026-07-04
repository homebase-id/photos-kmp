import XCTest

/// Strict-TDD gate for the §5.1 iOS login screen (auth T8).
///
/// A fresh simulator install has no stored session, so the session-gated root renders the login
/// screen (NOT the timeline). These launch WITHOUT the `-uiTestTimeline` bypass to exercise that
/// gate. They stop short of tapping submit, which would open a real `ASWebAuthenticationSession`
/// consent sheet.
final class LoginScreenUITest: XCTestCase {
    func testLoginScreenRendersOnFreshInstall() {
        let app = XCUIApplication()
        app.launch()

        // The login root sits on a drawn ZStack carrying the id (same lesson as timeline-root:
        // a non-drawing container would be collapsed out of the AX tree).
        let root = app.otherElements["login-root"]
        XCTAssertTrue(
            root.waitForExistence(timeout: 15),
            "Login root did not render on a fresh (logged-out) install"
        )

        XCTAssertTrue(
            app.textFields["login-id-field"].waitForExistence(timeout: 5),
            "Homebase ID field did not render"
        )
        XCTAssertTrue(
            app.buttons["login-submit"].waitForExistence(timeout: 5),
            "Sign-in button did not render"
        )
    }

    func testSubmitEnablesAfterTypingIdentity() {
        let app = XCUIApplication()
        app.launch()

        let field = app.textFields["login-id-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 15), "Homebase ID field did not render")

        let submit = app.buttons["login-submit"]
        XCTAssertTrue(submit.waitForExistence(timeout: 5), "Sign-in button did not render")
        XCTAssertFalse(submit.isEnabled, "Submit should be disabled with no identity entered")

        field.tap()
        field.typeText("sam.homebase.id")

        // The identity round-trips through the shared ViewModel; once state echoes it back the
        // button enables. Do NOT tap submit — it would open a real browser consent sheet.
        let enabled = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: submit)
        wait(for: [enabled], timeout: 5)
    }
}
