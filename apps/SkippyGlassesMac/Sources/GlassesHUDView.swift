import SwiftUI

/// Full-screen view rendered on the VITURE glasses display.
///
/// Layout (mirrors SkippyDroid's Compositor corner assignments):
///   TOP-RIGHT  — Clock + Compass heading
///   TOP-LEFT   — Battery (Mac %)
///   BOTTOM-LEFT — GPS coordinates
///
/// Background is black — stands in for camera passthrough until the S23 is wired.
struct GlassesHUDView: View {
    @State private var hud = HUDViewModel.shared

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // ── TOP-RIGHT: Clock + Compass ─────────────────────────
            VStack(alignment: .trailing, spacing: 2) {
                ClockRow(date: hud.currentTime)
                CompassRow(degrees: hud.headingDegrees, cardinal: hud.cardinalDirection)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            .padding(.top, 30)
            .padding(.trailing, 20)

            // ── TOP-LEFT: Battery ──────────────────────────────────
            BatteryRow(percent: hud.batteryPercent, charging: hud.batteryCharging)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.top, 30)
                .padding(.leading, 20)

            // ── BOTTOM-LEFT: GPS coordinates ───────────────────────
            if hud.locationAccuracy > 0 {
                GPSBlock(
                    lat: hud.latitude,
                    lon: hud.longitude,
                    accuracy: hud.locationAccuracy
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(.bottom, 20)
                .padding(.leading, 20)
            }
        }
        .onAppear  { hud.start() }
        .onDisappear { hud.stop() }
        .preferredColorScheme(.dark)
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
                .foregroundStyle(degrees >= 0 ? Color.cyan : Color.cyan.opacity(0.35))
            Text(cardinal)
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(degrees >= 0 ? Color.cyan : Color.cyan.opacity(0.35))
        }
    }
}

// MARK: - Battery

private struct BatteryRow: View {
    let percent: Int?
    let charging: Bool

    private var pctText: String {
        guard let p = percent else { return "AC" }
        return "\(p)%"
    }

    private var icon: String {
        guard let p = percent else { return "powerplug.fill" }
        if charging { return "battery.100.bolt" }
        return switch p {
        case 76...: "battery.100"
        case 51...: "battery.75"
        case 26...: "battery.50"
        case 11...: "battery.25"
        default:    "battery.0"
        }
    }

    private var color: Color {
        guard let p = percent else { return .green }
        return switch p {
        case 51...: .green
        case 21...: .yellow
        default:    .red
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundStyle(color)
            Text("Mac \(pctText)")
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(color)
        }
    }
}

// MARK: - GPS block

private struct GPSBlock: View {
    let lat: Double
    let lon: Double
    let accuracy: Double

    private var latStr: String { dms(lat, pos: "N", neg: "S") }
    private var lonStr: String { dms(lon, pos: "E", neg: "W") }

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(latStr)
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(Color.green)
            Text(lonStr)
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(Color.green)
            Text("±\(Int(accuracy)) m")
                .font(.system(size: 9, weight: .regular, design: .monospaced))
                .foregroundStyle(Color.green.opacity(0.5))
        }
    }

    private func dms(_ value: Double, pos: String, neg: String) -> String {
        let dir = value >= 0 ? pos : neg
        let abs = Swift.abs(value)
        let d = Int(abs)
        let mRaw = (abs - Double(d)) * 60
        let m = Int(mRaw)
        let s = Int((mRaw - Double(m)) * 60)
        return "\(d)°\(String(format: "%02d", m))'\(String(format: "%02d", s))\"\(dir)"
    }
}

// MARK: - Preview

#Preview {
    GlassesHUDView()
        .frame(width: 960, height: 600)
}
