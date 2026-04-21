import SwiftUI

/// Persistent HUD — top-left corner of the glasses display.
/// Aviation-style compass tape, GPS coordinates, and time.
/// Sized for readability on a 2880×1800 glasses panel.
struct HUDOverlayView: View {
    @State private var hud = HUDViewModel.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CompassTape(heading: hud.headingDegrees)
                .frame(width: 320, height: 44)

            HeadingBadge(degrees: hud.headingDegrees, cardinal: hud.cardinalDirection)
                .padding(.top, 4)

            Divider()
                .background(Color.white.opacity(0.2))
                .padding(.vertical, 6)

            CoordRow(latitude: hud.latitude, longitude: hud.longitude,
                     accuracy: hud.locationAccuracy)
            AltitudeRow(meters: hud.altitudeMeters)
                .padding(.top, 2)
            TimeRow(date: hud.currentTime)
                .padding(.top, 2)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(.black.opacity(0.55))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
                )
        )
        .onAppear { hud.start() }
        .onDisappear { hud.stop() }
    }
}

// MARK: - Compass tape

/// Horizontal compass tape — aviation HUD style.
/// Current heading is pinned at center with a cyan tick.
private struct CompassTape: View {
    let heading: Double

    /// How many degrees are represented by one point of width.
    private let scale: Double = 0.5

    private let majors: [(Double, String)] = [
        (0, "N"), (45, "NE"), (90, "E"), (135, "SE"),
        (180, "S"), (225, "SW"), (270, "W"), (315, "NW"),
        (360, "N")   // wrap
    ]

    var body: some View {
        Canvas { ctx, size in
            let cx = size.width / 2
            let tickBottom = size.height
            let fullTick: Double = 20
            let halfTick: Double = 12
            let smallTick: Double = 7

            // Draw ticks from -90° to +90° around current heading
            for deg in stride(from: -90.0, through: 90.0, by: 5.0) {
                let compassDeg = (heading + deg).truncatingRemainder(dividingBy: 360)
                let x = cx + deg / scale
                guard x >= 0 && x <= size.width else { continue }

                let isMajor   = (compassDeg.truncatingRemainder(dividingBy: 45) < 0.01 ||
                                 compassDeg.truncatingRemainder(dividingBy: 45) > 44.99)
                let isQuarter = (compassDeg.truncatingRemainder(dividingBy: 10) < 0.01 ||
                                 compassDeg.truncatingRemainder(dividingBy: 10) > 9.99)
                let tickH = isMajor ? fullTick : (isQuarter ? halfTick : smallTick)
                let alpha = isMajor ? 0.9 : (isQuarter ? 0.6 : 0.35)

                ctx.stroke(
                    Path { p in
                        p.move(to: CGPoint(x: x, y: tickBottom - tickH))
                        p.addLine(to: CGPoint(x: x, y: tickBottom))
                    },
                    with: .color(.white.opacity(alpha)),
                    lineWidth: isMajor ? 1.5 : 1
                )

                if isMajor {
                    let label = label(for: compassDeg)
                    ctx.draw(
                        Text(label)
                            .font(.system(size: 11, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.85)),
                        at: CGPoint(x: x, y: tickBottom - fullTick - 9)
                    )
                }
            }

            // Center cyan tick = your heading
            ctx.stroke(
                Path { p in
                    p.move(to: CGPoint(x: cx, y: 0))
                    p.addLine(to: CGPoint(x: cx, y: tickBottom))
                },
                with: .color(Color(red: 0, green: 0.85, blue: 1)),
                lineWidth: 2
            )
        }
    }

    private func label(for degrees: Double) -> String {
        let d = (degrees + 0.5).truncatingRemainder(dividingBy: 360)
        switch Int(d) {
        case 0, 360: return "N"
        case 45:     return "NE"
        case 90:     return "E"
        case 135:    return "SE"
        case 180:    return "S"
        case 225:    return "SW"
        case 270:    return "W"
        case 315:    return "NW"
        default:     return ""
        }
    }
}

// MARK: - Heading badge

private struct HeadingBadge: View {
    let degrees: Double
    let cardinal: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(cardinal)
                .font(.system(size: 15, weight: .bold, design: .monospaced))
                .foregroundStyle(Color(red: 0, green: 0.85, blue: 1))
            Text(String(format: "%03.0f°", degrees))
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(.white)
        }
    }
}

// MARK: - Coordinate row

private struct CoordRow: View {
    let latitude: Double
    let longitude: Double
    let accuracy: Double

    private var latString: String {
        let d = abs(latitude)
        return String(format: "%.4f°%@", d, latitude >= 0 ? "N" : "S")
    }
    private var lonString: String {
        let d = abs(longitude)
        return String(format: "%.4f°%@", d, longitude >= 0 ? "E" : "W")
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "mappin.and.ellipse")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.5))
            Text("\(latString)  \(lonString)")
                .font(.system(size: 14, weight: .regular, design: .monospaced))
                .foregroundStyle(.white.opacity(0.85))
            if accuracy > 0 && accuracy < 100 {
                Text(String(format: "±%.0fm", accuracy))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
    }
}

// MARK: - Altitude row

private struct AltitudeRow: View {
    let meters: Double

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "arrow.up.to.line")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.5))
            Text(String(format: "%.0f m", meters))
                .font(.system(size: 14, weight: .regular, design: .monospaced))
                .foregroundStyle(.white.opacity(0.85))
        }
    }
}

// MARK: - Time row

private struct TimeRow: View {
    let date: Date

    private var timeString: String {
        date.formatted(date: .omitted, time: .standard)  // HH:MM:SS
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "clock")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.5))
            Text(timeString)
                .font(.system(size: 18, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white)
        }
    }
}

// MARK: - Preview

#Preview {
    ZStack {
        Color.black.ignoresSafeArea()
        HUDOverlayView()
            .padding(20)
    }
    .preferredColorScheme(.dark)
}
