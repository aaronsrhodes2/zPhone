import SwiftUI

/// Wide light-blue LED bar that lives in the macOS menu bar.
/// Pulses gently when speech is detected. Dims when silent or disabled.
struct LEDBarLabel: View {
    let engine: VoiceSubmitEngine

    @State private var pulse: Bool = false

    private var barColor: Color {
        guard engine.isEnabled else { return .gray.opacity(0.3) }
        if engine.didSubmitFlash { return .white }
        return engine.mode == .holdForMore ? .orange : Color(red: 0.4, green: 0.85, blue: 1.0)
    }

    private var glowOpacity: Double {
        guard engine.isEnabled, engine.isListening else { return 0 }
        return engine.detector.isSpeechDetected ? (pulse ? 1.0 : 0.6) : 0.25
    }

    var body: some View {
        ZStack {
            // Glow layer
            Capsule()
                .fill(barColor)
                .blur(radius: 4)
                .opacity(glowOpacity)

            // Core bar
            Capsule()
                .fill(
                    LinearGradient(
                        colors: [
                            barColor.opacity(engine.isEnabled ? 1.0 : 0.2),
                            barColor.opacity(engine.isEnabled ? 0.7 : 0.1),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
        }
        .frame(width: 56, height: 8)
        .padding(.vertical, 7)   // centres in 22pt menu bar height
        .onChange(of: engine.detector.isSpeechDetected) { _, speaking in
            guard speaking else { pulse = false; return }
            withAnimation(.easeInOut(duration: 0.4).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}
