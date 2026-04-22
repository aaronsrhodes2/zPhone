package com.skippy.droid.features.navigation

/**
 * Decodes a Google Maps encoded polyline string into a list of (lat, lng) pairs.
 *
 * Reference: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 *
 * Algorithm summary:
 *   - Each coordinate component is split into 5-bit chunks with a continuation bit
 *   - Characters are ASCII-63 of those chunks
 *   - Zigzag decoding restores sign: `(v & 1) != 0 ? v.inv() shr 1 : v shr 1`
 *   - Values are accumulated deltas × 1e-5
 *
 * Mirror of `apps/SkippyGlassesMac/Sources/PolylineDecoder.swift` — keep logic in sync.
 */
object PolylineDecoder {

    fun decode(encoded: String): List<Pair<Double, Double>> {
        val result = ArrayList<Pair<Double, Double>>(encoded.length / 6 + 4)
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            // Lat
            var shift = 0
            var value = 0
            var b: Int
            do {
                if (index >= encoded.length) break
                b = encoded[index].code - 63
                index++
                value = value or ((b and 0x1F) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (value and 1 != 0) (value shr 1).inv() else (value shr 1)
            lat += dLat

            // Lng
            shift = 0
            value = 0
            do {
                if (index >= encoded.length) break
                b = encoded[index].code - 63
                index++
                value = value or ((b and 0x1F) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (value and 1 != 0) (value shr 1).inv() else (value shr 1)
            lng += dLng

            result.add(lat / 1e5 to lng / 1e5)
        }

        return result
    }
}
