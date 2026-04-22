import Foundation

/// Decodes a Google Maps encoded polyline string into an array of (lat, lng) coordinates.
///
/// Reference: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
enum PolylineDecoder {

    static func decode(_ encoded: String) -> [(lat: Double, lng: Double)] {
        var result: [(Double, Double)] = []
        var index = encoded.startIndex
        var lat = 0, lng = 0

        while index < encoded.endIndex {
            // Decode one coordinate component (lat or lng)
            func decodeComponent() -> Int {
                var value = 0, shift = 0, b = 0
                repeat {
                    guard index < encoded.endIndex else { break }
                    b = Int(encoded[index].asciiValue ?? 63) - 63
                    index = encoded.index(after: index)
                    value |= (b & 0x1F) << shift
                    shift += 5
                } while b >= 0x20
                return (value & 1) != 0 ? ~(value >> 1) : (value >> 1)
            }

            lat += decodeComponent()
            lng += decodeComponent()
            result.append((Double(lat) / 1e5, Double(lng) / 1e5))
        }

        return result
    }
}
