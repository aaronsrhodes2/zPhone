import SwiftUI

/// Root view rendered on the VITURE glasses display.
/// HUD is always visible top-left. Active app layer sits beneath it.
struct GlassesRootView: View {
    @State private var appState = GlassesAppState.shared

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()

            // ── Active app layer ─────────────────────────────────
            switch appState.activeApp {
            case .none:
                idlePlaceholder
            case .translation:
                SubtitleOverlayView()
            }

            // ── Persistent HUD — always on top ───────────────────
            HUDOverlayView()
                .padding(16)
        }
        .preferredColorScheme(.dark)
    }

    private var idlePlaceholder: some View {
        VStack {
            Spacer()
            Text("SKIPPY AR")
                .font(.system(size: 48, weight: .black, design: .monospaced))
                .foregroundStyle(.white.opacity(0.1))
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    GlassesRootView()
}

#Preview {
    GlassesRootView()
}
