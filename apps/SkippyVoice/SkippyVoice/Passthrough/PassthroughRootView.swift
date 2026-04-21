import SwiftUI

/// Root view rendered fullscreen on the VITURE glasses display.
/// Camera feed fills the entire screen — looks transparent.
/// Clock + compass pinned to the upper-right corner only.
struct PassthroughRootView: View {
    let manager: PassthroughManager

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // ── Camera feed — full screen ─────────────────────────
            if let session = manager.captureSession {
                CameraFeedView(session: session)
                    .ignoresSafeArea()
            } else {
                Color.black.ignoresSafeArea()
            }

            // ── HUD — upper-right corner only ────────────────────
            MinimalHUDView(
                heading: manager.headingDegrees,
                cardinal: manager.cardinalDirection,
                time: manager.currentTime
            )
            .padding(20)
        }
    }
}

// MARK: - Minimal HUD

struct MinimalHUDView: View {
    let heading: Double
    let cardinal: String
    let time: Date

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            // Compass
            HStack(spacing: 6) {
                Text("\(cardinal)  \(Int(heading))°")
                    .font(.system(size: 22, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                Image(systemName: "location.north.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color(red: 0, green: 0.85, blue: 1))
                    .rotationEffect(.degrees(-heading))
            }

            // Clock
            Text(time.formatted(date: .omitted, time: .standard))
                .font(.system(size: 20, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(.black.opacity(0.45))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(.white.opacity(0.1), lineWidth: 1)
                )
        )
        .shadow(color: .black.opacity(0.5), radius: 8, y: 2)
    }
}
