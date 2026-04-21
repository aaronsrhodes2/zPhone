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
    @State private var hud = HUDViewModel.shared

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

            // ── Navigation ─────────────────────────────────────────────────────
            if hud.navEngine.isNavigating {
                if let step = hud.navEngine.state?.currentStep {
                    Label(
                        "\(MacNavigationEngine.maneuverArrow(step.maneuver))  \(step.instruction)",
                        systemImage: "arrow.triangle.turn.up.right.circle"
                    )
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.cyan)
                    .padding(.horizontal, 8)
                }
                Button("■ Stop Navigation") {
                    hud.navEngine.cancel()
                }
                .keyboardShortcut(".", modifiers: .command)
            } else {
                if let err = hud.navEngine.error {
                    Label(err, systemImage: "exclamationmark.triangle")
                        .font(.system(size: 10))
                        .foregroundStyle(.red)
                        .padding(.horizontal, 8)
                }
                Button("▶ Navigate: Johnny's Market, Boulder Creek") {
                    guard let loc = hud.currentLocation else { return }
                    Task {
                        await hud.navEngine.navigateTo(
                            "Johnny's Market Boulder Creek CA",
                            mode: .walking,
                            from: loc
                        )
                    }
                }
                .keyboardShortcut("n", modifiers: .command)
                .disabled(hud.currentLocation == nil)
            }

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
