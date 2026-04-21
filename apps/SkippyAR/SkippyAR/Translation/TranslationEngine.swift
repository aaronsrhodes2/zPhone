import AVFoundation
import Foundation
import NaturalLanguage
import Speech
import Translation

/// Listens to the microphone via SFSpeechRecognizer.
/// When non-English speech is detected, translates to English and publishes
/// subtitle text for the glasses overlay.
///
/// Entirely on-device. TranslationSession is provided by SubtitleOverlayView
/// via the .translationTask SwiftUI modifier (Apple's required pattern).
@Observable
class TranslationEngine {
    static let shared = TranslationEngine()

    var subtitleText: String = ""
    var isListening: Bool = false
    var detectedLanguage: String = ""

    /// Auto-detect source, always target English.
    /// Observed by SubtitleOverlayView to trigger .translationTask.
    let translationConfig = TranslationSession.Configuration(
        source: nil,
        target: Locale.Language(identifier: "en")
    )

    /// Injected by SubtitleOverlayView once the task fires.
    var session: TranslationSession?

    private let speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private var audioEngine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var debounceTask: Task<Void, Never>?
    private var lastRawText: String = ""

    private init() {}

    func start() {
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            guard status == .authorized else { return }
            DispatchQueue.main.async { self?.startRecognition() }
        }
    }

    func stop() {
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        debounceTask?.cancel()
        isListening = false
        subtitleText = ""
        detectedLanguage = ""
        lastRawText = ""
    }

    // MARK: - Private

    private func startRecognition() {
        recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        guard let request = recognitionRequest else { return }
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        request.taskHint = .dictation

        recognitionTask = speechRecognizer?.recognitionTask(with: request) { [weak self] result, _ in
            guard let self, let result else { return }
            let raw = result.bestTranscription.formattedString
            guard !raw.isEmpty, raw != self.lastRawText else { return }
            self.lastRawText = raw

            self.debounceTask?.cancel()
            self.debounceTask = Task { [raw] in
                try? await Task.sleep(for: .seconds(1.2))
                guard !Task.isCancelled else { return }
                await self.translateIfNeeded(raw)
            }

            if result.isFinal {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    self.startRecognition()
                }
            }
        }

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.recognitionRequest?.append(buffer)
        }

        audioEngine.prepare()
        try? audioEngine.start()
        isListening = true
    }

    @MainActor
    private func translateIfNeeded(_ text: String) async {
        let detector = NLLanguageRecognizer()
        detector.processString(text)

        guard let lang = detector.dominantLanguage,
              lang != .english,
              lang != .undetermined else {
            subtitleText = ""
            detectedLanguage = ""
            return
        }

        detectedLanguage = lang.rawValue

        guard let session else {
            subtitleText = text  // no session yet — show original
            return
        }

        do {
            let response = try await session.translate(text)
            subtitleText = response.targetText
        } catch {
            subtitleText = text
        }
    }
}
