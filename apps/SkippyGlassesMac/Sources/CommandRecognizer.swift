import Speech
import AVFoundation

/// Listens to the same AVAudioEngine tap as SilenceDetector.
/// Exposes a running transcript (for paste-on-silence) and fires callbacks
/// when it hears known voice commands.
///
/// Commands:
///   "hold for more"  → multi-line mode (Shift+Enter between segments)
///   "send it"        → force-submit and exit multi-line mode
///   "never mind"     → discard accumulated text, reset
@Observable
class CommandRecognizer {

    enum Command {
        case holdForMore
        case sendIt
        case neverMind
    }

    var onCommand: ((Command) -> Void)?

    /// Full transcript since the last clearTranscript(), command phrases stripped out.
    private(set) var currentTranscript: String = ""

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    // Text committed from completed (isFinal) recognition segments.
    private var committedTranscript: String = ""

    func start() {
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            guard status == .authorized else { return }
            DispatchQueue.main.async { self?.setupRequest() }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        request = nil
    }

    func feed(buffer: AVAudioPCMBuffer) {
        request?.append(buffer)
    }

    /// Resets the transcript — call after each paste/submit.
    func clearTranscript() {
        committedTranscript = ""
        currentTranscript = ""
    }

    // MARK: - Private

    private func setupRequest() {
        request = SFSpeechAudioBufferRecognitionRequest()
        request?.shouldReportPartialResults = true
        // .search gives better accuracy for short voice commands (vs .dictation which
        // expects long-form prose).  On-device recognition is intentionally NOT required
        // so server-side recognition acts as fallback when the on-device model is absent.
        request?.taskHint = .search

        task = recognizer?.recognitionTask(with: request!) { [weak self] result, error in
            guard let self else { return }
            if let error {
                NSLog("[CommandRecognizer] recognition error: %@", error.localizedDescription)
                // Auto-restart on recoverable errors (e.g. audio interruptions)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.setupRequest() }
                return
            }
            guard let result else { return }

            let raw = result.bestTranscription.formattedString
            let combined = [self.committedTranscript, raw]
                .filter { !$0.isEmpty }
                .joined(separator: " ")

            DispatchQueue.main.async {
                self.currentTranscript = self.strippingCommands(from: combined)
                self.checkForCommands(in: raw.lowercased())
            }

            // SFSpeechRecognitionTask auto-ends around 60 s — commit and restart.
            if result.isFinal {
                DispatchQueue.main.async { self.committedTranscript = combined }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { self.setupRequest() }
            }
        }
    }

    /// Cancel the current task and spin up a fresh request.
    /// Call after consuming a command so stale audio doesn't bleed into the next utterance.
    func restart() {
        task?.cancel()
        task = nil
        request = nil
        committedTranscript = ""
        currentTranscript = ""
        setupRequest()
    }

    private func checkForCommands(in text: String) {
        let window = String(text.suffix(60))
        if window.contains("hold for more") {
            onCommand?(.holdForMore)
        } else if window.contains("send it") {
            onCommand?(.sendIt)
        } else if window.contains("never mind") {
            onCommand?(.neverMind)
        }
    }

    private func strippingCommands(from text: String) -> String {
        var t = text
        for phrase in ["hold for more", "send it", "never mind"] {
            t = t.replacingOccurrences(of: phrase, with: "", options: .caseInsensitive)
        }
        // Collapse double spaces and trim
        while t.contains("  ") { t = t.replacingOccurrences(of: "  ", with: " ") }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
