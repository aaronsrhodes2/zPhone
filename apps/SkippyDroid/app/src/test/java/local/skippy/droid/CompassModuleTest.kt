package local.skippy.droid

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassModuleTest {

    private fun cardinal(deg: Double): String {
        val dirs = listOf("N","NNE","NE","ENE","E","ESE","SE","SSE",
                          "S","SSW","SW","WSW","W","WNW","NW","NNW")
        return dirs[((deg + 11.25) / 22.5).toInt() % 16]
    }

    @Test fun north() = assertEquals("N", cardinal(0.0))
    @Test fun east() = assertEquals("E", cardinal(90.0))
    @Test fun south() = assertEquals("S", cardinal(180.0))
    @Test fun west() = assertEquals("W", cardinal(270.0))
    @Test fun northNorthEast() = assertEquals("NNE", cardinal(22.5))
    @Test fun wrapsAt360() = assertEquals("N", cardinal(359.9))
}
