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

            // ── BOTTOM-CENTRE: Live voice transcript (debug) ───────
            // Shows real-time recognition + last-parsed command so the Captain can
            // see exactly what the speech recognizer heard.  Remove once voice is stable.
            VoiceTranscriptView()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 20)

            // ── BOTTOM-RIGHT: Diagnostics panel (Layer 0-1 observability) ────
            // Shows the end-to-end pipeline: voice → parse → nav → net → dots.
            // Any broken link is immediately visible here.
            DiagnosticsPanel()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(.bottom, 20)
                .padding(.trailing, 20)

            // ── Navigation fetching indicator ──────────────────────
            if hud.navEngine.isFetchingRoute {
                NavFetchingPin()
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

// MARK: - World-anchored navigation overlay

/// Renders GPS-pinned direction dots and a green end-of-trail arrow.
///
/// Each dot has a fixed real-world GPS coordinate (set when the route is painted).
/// The `worldProject` function converts those coordinates to screen positions each
/// frame using current GPS + compass heading — making the dots appear glued to the
/// physical ground as the Captain moves and turns.
///
/// When Mac has no compass (headingDeg = -1) dots appear going straight up from
/// their GPS positions, confirming navigation is active.  On S23 + VITURE IMU the
/// dots will sweep left/right correctly as the Captain's head turns.
private struct DirectionDotsOverlay: View {
    let nav: MacNavigationEngine
    let hud: HUDViewModel

    /// Real compass when available (Android + VITURE IMU).
    /// On Mac (no magnetometer) we pretend to face toward the current step endpoint
    /// so the dots render in front of us rather than behind.
    private func effectiveHeading(fromLat: Double, fromLng: Double) -> Double {
        if hud.headingDegrees >= 0 { return hud.headingDegrees }
        guard let step = nav.state?.currentStep else { return 0 }
        return MacNavigationEngine.bearing(
            fromLat: fromLat, fromLng: fromLng,
            toLat: step.endLat, toLng: step.endLng
        )
    }

    var body: some View {
        if nav.isNavigating, !nav.trail.dots.isEmpty,
           let loc = hud.currentLocation
        {
            let fromLat = loc.coordinate.latitude
            let fromLng = loc.coordinate.longitude
            let headingDeg = effectiveHeading(fromLat: fromLat, fromLng: fromLng)

            Canvas { ctx, size in
                var lastVisiblePoint: CGPoint? = nil

                for dot in nav.trail.dots {
                    guard let (pt, scale) = MacNavigationEngine.worldProject(
                        dotLat: dot.lat, dotLng: dot.lng,
                        fromLat: fromLat, fromLng: fromLng,
                        headingDeg: headingDeg,
                        screenSize: size
                    ) else { continue }

                    // Radius and alpha taper with distance (scale = 1/forward_metres)
                    let r     = min(max(scale * 55, 6), 30)
                    let alpha = min(max(scale * 8,  0.2), 0.95)

                    ctx.fill(
                        Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r,
                                               width: r * 2, height: r * 2)),
                        with: .color(Color(red: 0, green: 0.67, blue: 1).opacity(alpha))
                    )

                    lastVisiblePoint = pt
                }

                // ── Green arrow at the end of the trail ──────────────────────
                // Points toward the step endpoint — indicates the turn direction.
                if let tip = lastVisiblePoint,
                   let step = nav.state?.currentStep
                {
                    drawArrow(ctx: ctx, at: tip,
                              towardLat: step.endLat, towardLng: step.endLng,
                              fromLat: fromLat, fromLng: fromLng,
                              heading: headingDeg, size: size)
                }
            }
            .allowsHitTesting(false)
        }
    }

    /// Draws a filled triangle arrow at `at`, pointing toward the step endpoint.
    private func drawArrow(
        ctx: GraphicsContext,
        at tip: CGPoint,
        towardLat: Double, towardLng: Double,
        fromLat: Double, fromLng: Double,
        heading: Double,
        size: CGSize
    ) {
        // Bearing of the arrow on screen (relative to up = 0°)
        let dNorth = (towardLat - fromLat) * 111_139
        let dEast  = (towardLng - fromLng) * 111_139 * cos(fromLat * .pi / 180)
        let h = heading * .pi / 180
        let fwd = dNorth * cos(h) + dEast * sin(h)
        let rgt = -dNorth * sin(h) + dEast * cos(h)
        let angle = atan2(rgt, fwd)    // screen angle: 0 = up

        let arrowLen: Double = 28
        let arrowWidth: Double = 14

        // Tip of arrow, then two base corners
        let tipX = tip.x + sin(angle) * arrowLen
        let tipY = tip.y - cos(angle) * arrowLen
        let baseAngle = angle + .pi          // opposite direction
        let perpAngle = angle + .pi / 2
        let baseX = tip.x + sin(baseAngle) * (arrowLen * 0.3)
        let baseY = tip.y - cos(baseAngle) * (arrowLen * 0.3)
        let lx = baseX + sin(perpAngle) * arrowWidth
        let ly = baseY - cos(perpAngle) * arrowWidth
        let rx = baseX - sin(perpAngle) * arrowWidth
        let ry = baseY + cos(perpAngle) * arrowWidth

        var path = Path()
        path.move(to: CGPoint(x: tipX, y: tipY))
        path.addLine(to: CGPoint(x: lx, y: ly))
        path.addLine(to: CGPoint(x: rx, y: ry))
        path.closeSubpath()

        ctx.fill(path, with: .color(Color(red: 0.2, green: 1.0, blue: 0.4).opacity(0.90)))
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

// MARK: - Voice transcript debug view

/// Live speech recognition display — shows what the recognizer is hearing in real time
/// and the last-parsed command.  Helps distinguish "mic didn't pick it up" from
/// "parser missed the phrase" without needing `make mac-logs`.
///
/// States:
///   ◉ listening…        — mic armed, nothing transcribed yet
///   ∿ live transcript   — recognizer producing partial results as Captain speaks
///   ✓ heard: …          — silence detected, last-parsed command frozen
private struct VoiceTranscriptView: View {
    @State private var voice = VoiceEngine.shared

    var body: some View {
        let live    = voice.recognizer.currentTranscript
        let last    = voice.lastHeard
        let hasLive = !live.isEmpty

        HStack(spacing: 8) {
            Image(systemName: hasLive ? "waveform" : (last.isEmpty ? "mic.fill" : "checkmark.circle.fill"))
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(hasLive ? Color.white : Color.cyan.opacity(0.8))

            Text(
                hasLive
                    ? live
                    : (last.isEmpty ? "listening…" : "heard: \(last)")
            )
            .font(.system(size: 13, weight: hasLive ? .semibold : .regular, design: .monospaced))
            .foregroundStyle(hasLive ? Color.white : Color.cyan.opacity(0.75))
            .lineLimit(2)
            .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(Color.black.opacity(0.55))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.cyan.opacity(0.35), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Diagnostics panel (Layer 0-1 observability)

/// Renders the last ~10 events from `DiagnosticsLog` — voice, nav, net, errors.
/// Shown bottom-right on the glasses so the Captain can watch the full pipeline
/// flow end-to-end without opening a terminal.
///
/// Color coding:
///   VOICE — purple  (speech recognition events)
///   NAV   — yellow  (navigation engine lifecycle)
///   NET   — green   (network requests / responses)
///   ERR   — red     (errors at any layer)
///   SYS   — cyan    (general system events)
private struct DiagnosticsPanel: View {
    @State private var diag = DiagnosticsLog.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(diag.events.suffix(10)) { event in
                HStack(alignment: .top, spacing: 6) {
                    Text(event.kind.rawValue)
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundStyle(color(for: event.kind))
                        .frame(width: 38, alignment: .leading)
                    Text(event.message)
                        .font(.system(size: 9, weight: .regular, design: .monospaced))
                        .foregroundStyle(Color.white.opacity(0.9))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(maxWidth: 360, alignment: .leading)
        .background(Color.black.opacity(0.55))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.white.opacity(0.15), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func color(for kind: DiagnosticsLog.Kind) -> Color {
        switch kind {
        case .voice: return Color(red: 0.7, green: 0.45, blue: 1.0)   // purple
        case .nav:   return Color.yellow
        case .net:   return Color.green
        case .error: return Color.red
        case .sys:   return Color.cyan
        }
    }
}

// MARK: - Navigation fetching indicator

/// Pulsing map-pin shown in the centre of the glasses display while the
/// Directions API request is in flight.  Disappears the moment dots appear.
private struct NavFetchingPin: View {
    @State private var beating = false

    var body: some View {
        Image(systemName: "mappin.and.ellipse")
            .font(.system(size: 64, weight: .bold))
            .foregroundStyle(Color.cyan)
            .shadow(color: .cyan.opacity(0.8), radius: beating ? 20 : 6)
            .scaleEffect(beating ? 1.25 : 1.0)
            .opacity(beating ? 1.0 : 0.55)
            .animation(
                .easeInOut(duration: 0.65).repeatForever(autoreverses: true),
                value: beating
            )
            .onAppear { beating = true }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Preview

#Preview {
    GlassesHUDView()
        .frame(width: 960, height: 600)
}
