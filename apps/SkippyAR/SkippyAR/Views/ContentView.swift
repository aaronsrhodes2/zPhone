import SwiftUI

/// iPad control surface — what you see on the device while the glasses show GlassesRootView.
struct ContentView: View {
    @State private var glasses = GlassesDisplayManager.shared
    @State private var appState = GlassesAppState.shared
    @State private var showHUDPreview = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {

                // ── Connection banner ───────────────────────────────
                ConnectionBanner(isConnected: glasses.isConnected)
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 12)

                Divider()

                // ── App launcher ────────────────────────────────────
                ScrollView {
                    VStack(spacing: 12) {
                        AppButton(title: "HUD Only", icon: "square.grid.2x2", color: .blue) {
                            showHUDPreview = true
                        }
                        AppButton(title: "Translation", icon: "translate",
                                  color: appState.activeApp == .translation ? .white : .purple,
                                  active: appState.activeApp == .translation) {
                            appState.launch(appState.activeApp == .translation ? .none : .translation)
                        }
                        AppButton(title: "Pac-Man Nav", icon: "map.fill", color: .yellow) {
                            // TODO: wire up Pac-Man layer
                        }
                        AppButton(title: "Sun Block", icon: "sun.max.fill", color: .orange) {
                            // TODO: wire up Sun Block layer
                        }
                        AppButton(title: "YeyBuddy", icon: "person.2.fill", color: .green) {
                            // TODO: wire up YeyBuddy layer
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 20)
                }
            }
            .navigationTitle("Skippy AR")
            .navigationBarTitleDisplayMode(.inline)
        }
        .sheet(isPresented: $showHUDPreview) {
            HUDPreviewSheet()
        }
    }
}

// MARK: - Connection banner

private struct ConnectionBanner: View {
    let isConnected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(isConnected ? Color.green : Color.orange)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(isConnected ? "VITURE Luma Ultra — Connected" : "VITURE Not Connected")
                    .font(.headline)
                if !isConnected {
                    Text("Connect via USB-C to send the display to your glasses.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - HUD preview sheet (dev / demo without glasses)

private struct HUDPreviewSheet: View {
    @Environment(\.dismiss) private var dismiss

    // VITURE Luma Ultra native resolution
    private let glassesWidth: CGFloat  = 2880
    private let glassesHeight: CGFloat = 1800

    var body: some View {
        GeometryReader { geo in
            let scale = min(geo.size.width / glassesWidth,
                           geo.size.height / glassesHeight)
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()

                GlassesRootView()
                    .frame(width: glassesWidth, height: glassesHeight)
                    .scaleEffect(scale, anchor: .topLeading)
                    .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)

                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title)
                        .foregroundStyle(.white.opacity(0.6))
                        .padding(20)
                }
            }
        }
        .background(Color.black)
        .preferredColorScheme(.dark)
        .presentationDetents([.large])
    }
}

// MARK: - App launcher button

struct AppButton: View {
    let title: String
    let icon: String
    let color: Color
    var active: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                    .font(.title2)
                    .frame(width: 32)
                Text(title)
                    .font(.headline)
                Spacer()
                Image(systemName: active ? "stop.circle.fill" : "chevron.right")
                    .foregroundStyle(active ? AnyShapeStyle(color) : AnyShapeStyle(.tertiary))
            }
            .padding()
            .foregroundStyle(color)
            .background(
                active
                    ? AnyShapeStyle(color.opacity(0.15))
                    : AnyShapeStyle(.regularMaterial),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(active ? color.opacity(0.4) : .clear, lineWidth: 1)
            )
        }
    }
}

#Preview {
    ContentView()
}
