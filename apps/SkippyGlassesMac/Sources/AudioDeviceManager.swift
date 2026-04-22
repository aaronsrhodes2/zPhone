import AVFoundation
import CoreAudio
import Foundation

struct AudioDevice: Identifiable, Equatable {
    let id: AudioDeviceID
    let name: String
}

/// Enumerates macOS input devices (mics) and applies the selected one to an AVAudioEngine.
@Observable
class AudioDeviceManager {

    private(set) var inputDevices: [AudioDevice] = []
    var selectedDeviceID: AudioDeviceID = 0 {
        didSet { UserDefaults.standard.set(Int(selectedDeviceID), forKey: "selectedMicDeviceID") }
    }

    var selectedDevice: AudioDevice? {
        inputDevices.first { $0.id == selectedDeviceID }
    }

    init() {
        inputDevices = Self.allInputDevices()
        let saved = UserDefaults.standard.integer(forKey: "selectedMicDeviceID")
        let savedID = AudioDeviceID(saved)
        // Use saved device if it still exists, otherwise fall back to system default
        if saved != 0 && inputDevices.contains(where: { $0.id == savedID }) {
            selectedDeviceID = savedID
        } else {
            selectedDeviceID = Self.systemDefaultInputDeviceID()
        }
    }

    func refresh() {
        inputDevices = Self.allInputDevices()
    }

    /// Sets the selected device on the provided AVAudioEngine's input node.
    /// Call this before audioEngine.start(), after prepare().
    func apply(to engine: AVAudioEngine) {
        guard selectedDeviceID != 0 else { return }
        guard let audioUnit = engine.inputNode.audioUnit else { return }
        var deviceID = selectedDeviceID
        AudioUnitSetProperty(
            audioUnit,
            kAudioOutputUnitProperty_CurrentDevice,
            kAudioUnitScope_Global,
            0,
            &deviceID,
            UInt32(MemoryLayout<AudioDeviceID>.size)
        )
    }

    // MARK: - CoreAudio helpers

    private static func allInputDevices() -> [AudioDevice] {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size
        ) == noErr else { return [] }

        let count = Int(size) / MemoryLayout<AudioDeviceID>.size
        var ids = [AudioDeviceID](repeating: 0, count: count)
        AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &ids
        )

        return ids.compactMap { id in
            guard hasInputStreams(deviceID: id), let name = deviceName(id) else { return nil }
            return AudioDevice(id: id, name: name)
        }
    }

    private static func hasInputStreams(deviceID: AudioDeviceID) -> Bool {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreams,
            mScope: kAudioDevicePropertyScopeInput,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(deviceID, &address, 0, nil, &size) == noErr && size > 0
    }

    private static func deviceName(_ deviceID: AudioDeviceID) -> String? {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioObjectPropertyName,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var name = "" as CFString
        var size = UInt32(MemoryLayout<CFString>.size)
        guard AudioObjectGetPropertyData(deviceID, &address, 0, nil, &size, &name) == noErr else { return nil }
        return name as String
    }

    private static func systemDefaultInputDeviceID() -> AudioDeviceID {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var deviceID: AudioDeviceID = 0
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &deviceID
        )
        return deviceID
    }
}
