import AVFoundation
import Combine

/// Monitors the microphone for speech activity.
/// Fires onSilenceThresholdReached after silenceDuration seconds of continuous silence
/// following at least one detected speech event.
@Observable
class SilenceDetector {

    // MARK: - Configuration
    var silenceDuration: TimeInterval = 2.5   // seconds of silence before firing
    var silenceThreshold: Float = 0.01         // RMS below this = silence
    var isEnabled: Bool = true

    // MARK: - State
    var currentRMS: Float = 0
    var isSpeechDetected: Bool = false         // true while voice is above threshold
    var isArmed: Bool = false                  // true after first speech event in session

    var onSilenceThresholdReached: (() -> Void)?
    var onBuffer: ((AVAudioPCMBuffer) -> Void)?   // shared tap for CommandRecognizer

    // MARK: - Private
    private let audioEngine = AVAudioEngine()
    private var silenceStartTime: Date?
    private var silenceCheckTimer: Timer?
    private let queue = DispatchQueue(label: "com.skippy.silence", qos: .userInteractive)

    func start(deviceManager: AudioDeviceManager? = nil) throws {
        guard isEnabled else { return }

        deviceManager?.apply(to: audioEngine)

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)

        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.onBuffer?(buffer)   // share with CommandRecognizer
            self?.process(buffer: buffer)
        }

        audioEngine.prepare()
        try audioEngine.start()

        // Check silence every 100ms
        silenceCheckTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            self?.checkSilenceDuration()
        }
    }

    func stop() {
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        silenceCheckTimer?.invalidate()
        silenceCheckTimer = nil
        reset()
    }

    func reset() {
        isArmed = false
        isSpeechDetected = false
        silenceStartTime = nil
    }

    // MARK: - Private

    private func process(buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData?[0] else { return }
        let frameLength = Int(buffer.frameLength)

        // Compute RMS
        var sum: Float = 0
        for i in 0..<frameLength { sum += channelData[i] * channelData[i] }
        let rms = sqrt(sum / Float(frameLength))

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.currentRMS = rms

            if rms > self.silenceThreshold {
                self.isSpeechDetected = true
                self.isArmed = true            // latch: we've heard speech this session
                self.silenceStartTime = nil    // reset silence clock on every speech frame
            } else {
                self.isSpeechDetected = false
                if self.isArmed && self.silenceStartTime == nil {
                    self.silenceStartTime = Date()  // start silence clock
                }
            }
        }
    }

    private func checkSilenceDuration() {
        guard isArmed, let start = silenceStartTime else { return }
        if Date().timeIntervalSince(start) >= silenceDuration {
            silenceStartTime = nil
            reset()
            onSilenceThresholdReached?()
        }
    }
}
