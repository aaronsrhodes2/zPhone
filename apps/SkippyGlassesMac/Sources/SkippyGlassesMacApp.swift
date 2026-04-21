import SwiftUI

/// Menu-bar-only app (LSUIElement = YES — no Dock icon).
/// The glasses window is created by GlassesWindowManager from AppDelegate.
/// This scene just provides the Quit item so the Captain can exit cleanly.
@main
struct SkippyGlassesMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        MenuBarExtra("Skippy", systemImage: "eye.fill") {
            MenuBarContent()
        }
    }
}

private struct MenuBarContent: View {
    @State private var connected = GlassesWindowManager.shared.glassesConnected

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(
                connected ? "VITURE — HUD active" : "VITURE — not found",
                systemImage: connected ? "checkmark.circle.fill" : "exclamationmark.circle"
            )
            .foregroundStyle(connected ? .green : .secondary)
            .padding(.horizontal, 8)
            .padding(.top, 6)

            Divider()

            Button("Quit Skippy Glasses") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
            .padding(.bottom, 4)
        }
        .onReceive(
            NotificationCenter.default.publisher(
                for: NSApplication.didChangeScreenParametersNotification
            )
        ) { _ in
            connected = GlassesWindowManager.shared.glassesConnected
        }
    }
}
