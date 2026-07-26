import XCTest

/// Batch G: the Settings sheet (account button → sheet → rows → Backup push / sign-out confirm).
/// Mirrors `LogoutUITest`'s bypass + skip pattern: `-uiTestTimeline` seams past the auth gate; if
/// the timeline never renders within 10s the test skips rather than fails. Sign-out is asserted up
/// to the confirmation only — confirming would tear down the session for the whole run.
final class SettingsUITest: XCTestCase {

    /// Launches past the auth gate and opens the Settings sheet; skips if the timeline never renders.
    private func openSettings() throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        let account = app.buttons["account-button"]
        guard account.waitForExistence(timeout: 10) else {
            throw XCTSkip("Timeline did not appear within 10s — cannot open Settings.")
        }
        account.tap()

        let root = app.descendants(matching: .any)["settings-root"]
        XCTAssertTrue(root.waitForExistence(timeout: 5), "Settings sheet did not appear")
        return app
    }

    func testAccountButtonOpensSettingsWithRows() throws {
        let app = try openSettings()

        XCTAssertTrue(app.descendants(matching: .any)["settings-account"].exists, "Account header missing")
        XCTAssertTrue(app.descendants(matching: .any)["settings-backup"].exists, "Backup row missing")
        XCTAssertTrue(app.descendants(matching: .any)["settings-about"].exists, "About row missing")
        XCTAssertTrue(app.descendants(matching: .any)["settings-signout"].exists, "Sign out row missing")
    }

    func testBackupRowOpensBackupScreen() throws {
        let app = try openSettings()

        app.descendants(matching: .any)["settings-backup"].firstMatch.tap()

        let backup = app.descendants(matching: .any)["backup-screen"]
        XCTAssertTrue(backup.waitForExistence(timeout: 5), "Backup screen did not appear from Settings")
    }

    func testSignOutShowsConfirmation() throws {
        let app = try openSettings()

        app.descendants(matching: .any)["settings-signout"].firstMatch.tap()

        // Identifier first, label fallback — action sheets don't always propagate the a11y id.
        let confirm = app.buttons["logout-confirm"]
        let confirmByLabel = app.buttons["Log out"]
        XCTAssertTrue(
            confirm.waitForExistence(timeout: 5) || confirmByLabel.waitForExistence(timeout: 5),
            "Logout confirmation did not appear after tapping Sign out"
        )

        // Do NOT confirm — leave the session untouched.
        let cancel = app.buttons["Cancel"]
        if cancel.exists { cancel.tap() }
    }
}
