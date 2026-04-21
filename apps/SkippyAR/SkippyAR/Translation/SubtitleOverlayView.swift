import SwiftUI
import Translation

/// Subtitle bar — bottom-center of the glasses display.
/// Fades in when non-English speech is detected, fades out when silent or English.
struct SubtitleOverlayView: View {
    @State private var engine = TranslationEngine.shared

    var body: some View {
        VStack {
            Spacer()
            if !engine.subtitleText.isEmpty {
                SubtitleBar(text: engine.subtitleText, language: engine.detectedLanguage)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .padding(.bottom, 48)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: engine.subtitleText)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .translationTask(engine.translationConfig) { session in
            engine.session = session
        }
    }
}

private struct SubtitleBar: View {
    let text: String
    let language: String

    var body: some View {
        VStack(spacing: 4) {
            if !language.isEmpty {
                Text(languageLabel)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.5))
            }
            Text(text)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .shadow(color: .black.opacity(0.8), radius: 4, y: 2)
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 14)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(.black.opacity(0.65))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(.white.opacity(0.1), lineWidth: 1)
                )
        )
        .padding(.horizontal, 60)
    }

    private var languageLabel: String {
        let locale = Locale.current
        return locale.localizedString(forLanguageCode: language)?.uppercased() ?? language.uppercased()
    }
}

#Preview {
    ZStack {
        Color.black.ignoresSafeArea()
        SubtitleOverlayView()
    }
    .preferredColorScheme(.dark)
}
