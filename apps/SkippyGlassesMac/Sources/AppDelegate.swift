import AppKit

class AppDelegate: NSObject, NSApplicationDelegate {

    func applicationDidFinishLaunching(_ notification: Notification) {
        GlassesWindowManager.shared.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        GlassesWindowManager.shared.stop()
    }
}
