import SwiftUI

/// Full-screen view rendered on the VITURE glasses display.
///
/// Layout (mirrors SkippyDroid's Compositor corner assignments):
///   TOP-RIGHT   — Clock + Compass heading
///   TOP-LEFT    — Battery (Mac %)
///   BOTTOM-LEFT — GPS coordinates
///   FULL-SCREEN — Direction dots overlay when navigating (glasses-only: no text)
///
/// Background is black — stands in for camera passthrough until the S23 is wired.
struct GlassesHUDView: View {
    @State private var hud = HUDViewModel.shared

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // ── Direction dots — drawn behind everything else ──────
            // Glasses see ONLY the dots (no instruction text — that lives on the phone).
            DirectionDotsOverlay(nav: hud.navEngine, hud: hud)

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

            // ── Debug nav trigger — keyboard shortcut only ─────────
            // ⌘N = start test walk to Johnny's Market, Boulder Creek
            // ⌘. = cancel navigation
            // These hidden buttons capture menu-bar keyboard shortcuts.
            // They are invisible on the glasses display.
            Color.clear
                .overlay(
                    Button("") {
                        if let loc = hud.currentLocation {
                            Task {
                                await hud.navEngine.navigateTo(
                                    "Johnny's Market Boulder Creek CA",
                                    mode: .walking,
                                    from: loc
                                )
                            }
                        }
                    }
                    .keyboardShortcut("n", modifiers: .command)
                    .opacity(0)
                )
                .overlay(
                    Button("") { hud.navEngine.cancel() }
                        .keyboardShortcut(".", modifiers: .command)
                        .opacity(0)
                )
        }
        .onAppear   { hud.start() }
        .onDisappear { hud.stop() }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Direction dots overlay (glasses — no text)

private struct DirectionDotsOverlay: View {
    let nav: MacNavigationEngine
    let hud: HUDViewModel

    var body: some View {
        if nav.isNavigating,
           let step = nav.state?.currentStep,
           let loc  = hud.currentLocation
        {
            let relBearing = MacNavigationEngine.relativeBearing(
                from: loc,
                endLat: step.endLat,
                endLng: step.endLng,
                // Mac has no compass — heading 0 = north. Dots point to absolute bearing.
                // On S23 with real compass the heading updates and dots sweep as you turn.
                headingDeg: max(hud.headingDegrees, 0)
            )

            Canvas { ctx, size in
                let rad = relBearing * .pi / 180
                let dx  =  sin(rad)
                let dy  = -cos(rad)   // negative: y grows downward in screen space

                let originX = size.width  / 2
                let originY = size.height

                let dotCount = 8
                for i in 0..<dotCount {
                    let t     = Double(i) / Double(dotCount - 1)
                    let dist  = Double(50 + i * i * 14)    // quadratic spacing = perspective
                    let x     = originX + dx * dist
                    let y     = originY + dy * dist
                    let r     = 24 - 18 * t                // shrinks with distance
                    let alpha = 0.92 - 0.74 * t            // fades with distance

                    ctx.fill(
                        Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
                        with: .color(Color(red: 0, green: 0.67, blue: 1).opacity(alpha))
                    )
                }
            }
            .allowsHitTesting(false)
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
