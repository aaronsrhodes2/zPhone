import AppKit
import Combine

/// Watches NSWorkspace for frontmost app changes.
/// Publishes the bundle ID of whichever app is currently in focus.
@Observable
class FrontmostAppWatcher {
    static let shared = FrontmostAppWatcher()

    var frontmostBundleID: String? = nil
    var frontmostAppName: String? = nil

    private var observer: Any?

    func start() {
        frontmostBundleID = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        frontmostAppName  = NSWorkspace.shared.frontmostApplication?.localizedName

        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            self?.frontmostBundleID = app?.bundleIdentifier
            self?.frontmostAppName  = app?.localizedName
        }
    }

    func stop() {
        if let observer { NSWorkspace.shared.notificationCenter.removeObserver(observer) }
    }
}
