import SwiftUI

/// Full-screen view rendered on the VITURE glasses display.
/// Black background (simulates camera passthrough), HUD in the top-right corner.
struct GlassesHUDView: View {
    @State private var hud = HUDViewModel.shared

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Layer 0: black canvas — stands in for camera passthrough
            Color.black.ignoresSafeArea()

            // Layer 6: HUD overlay — top-right corner, same corner as SkippyDroid
            HUDPanel(hud: hud)
                .padding(.top, 30)
                .padding(.trailing, 20)
        }
        .onAppear { hud.start() }
        .onDisappear { hud.stop() }
        .preferredColorScheme(.dark)
    }
}

// MARK: - HUD panel

private struct HUDPanel: View {
    let hud: HUDViewModel

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            ClockRow(date: hud.currentTime)
            CompassRow(degrees: hud.headingDegrees, cardinal: hud.cardinalDirection)
            if hud.locationAccuracy > 0 {
                GPSRow(lat: hud.latitude, lon: hud.longitude, accuracy: hud.locationAccuracy)
            }
        }
    }
}

// MARK: - Clock

private struct ClockRow: View {
    let date: Date

    private var timeString: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: date)
    }

    var body: some View {
        Text(timeString)
            .font(.system(size: 18, weight: .bold, design: .monospaced))
            .foregroundStyle(Color.cyan)
    }
}

// MARK: - Compass

private struct CompassRow: View {
    let degrees: Double     // negative = unavailable (Mac has no magnetometer)
    let cardinal: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(degrees >= 0 ? String(format: "%03.0f°", degrees) : "--°")
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(degrees >= 0 ? Color.cyan : Color.cyan.opacity(0.4))
            Text(cardinal)
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(degrees >= 0 ? Color.cyan : Color.cyan.opacity(0.4))
        }
    }
}

// MARK: - GPS (shows only when location is available)

private struct GPSRow: View {
    let lat: Double
    let lon: Double
    let accuracy: Double

    private var latStr: String {
        String(format: "%.4f°%@", abs(lat), lat >= 0 ? "N" : "S")
    }
    private var lonStr: String {
        String(format: "%.4f°%@", abs(lon), lon >= 0 ? "E" : "W")
    }

    var body: some View {
        Text("\(latStr) \(lonStr)")
            .font(.system(size: 10, weight: .regular, design: .monospaced))
            .foregroundStyle(Color.green.opacity(0.7))
    }
}

// MARK: - Preview

#Preview {
    GlassesHUDView()
        .frame(width: 800, height: 500)
}
