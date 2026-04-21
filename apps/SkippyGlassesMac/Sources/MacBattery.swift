import Foundation
import IOKit.ps

/// Reads the Mac's battery percentage via IOKit PowerSources.
/// Returns nil if running on AC with no battery (Mac mini, Mac Pro, etc.)
/// or if the battery state cannot be read.
enum MacBattery {

    static func percent() -> Int? {
        let snapshot = IOPSCopyPowerSourcesInfo().takeRetainedValue()
        let sources  = IOPSCopyPowerSourcesList(snapshot).takeRetainedValue() as [CFTypeRef]

        for source in sources {
            guard let info = IOPSGetPowerSourceDescription(snapshot, source)
                    .takeUnretainedValue() as? [String: Any] else { continue }

            // Skip sources that are not internal batteries
            guard let type = info[kIOPSTypeKey] as? String,
                  type == kIOPSInternalBatteryType else { continue }

            let capacity    = info[kIOPSCurrentCapacityKey] as? Int ?? 0
            let maxCapacity = info[kIOPSMaxCapacityKey]     as? Int ?? 0

            guard maxCapacity > 0 else { continue }
            return Int(Double(capacity) / Double(maxCapacity) * 100.0)
        }
        return nil  // no battery (desktop Mac) or reading failed
    }

    /// True if the Mac is currently charging (on AC power).
    static func isCharging() -> Bool {
        let snapshot = IOPSCopyPowerSourcesInfo().takeRetainedValue()
        let sources  = IOPSCopyPowerSourcesList(snapshot).takeRetainedValue() as [CFTypeRef]

        for source in sources {
            guard let info = IOPSGetPowerSourceDescription(snapshot, source)
                    .takeUnretainedValue() as? [String: Any] else { continue }
            guard let type = info[kIOPSTypeKey] as? String,
                  type == kIOPSInternalBatteryType else { continue }
            return (info[kIOPSPowerSourceStateKey] as? String) == kIOPSACPowerValue
        }
        return true  // assume AC if no battery found
    }
}
