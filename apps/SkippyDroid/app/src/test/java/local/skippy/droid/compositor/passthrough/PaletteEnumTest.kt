package local.skippy.droid.compositor.passthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip tests for [PaletteEnum.fromWire] / [PaletteEnum.wireName].
 *
 * The wire names are case-sensitive (PROTOCOL §5 enforces the literal strings
 * "cyan"/"amber"/etc.). Any rename here is a breaking protocol change.
 */
class PaletteEnumTest {

    @Test fun `fromWire recognizes every canonical name`() {
        for (p in PaletteEnum.entries) {
            val decoded = PaletteEnum.fromWire(p.wireName)
            assertEquals(p, decoded)
        }
    }

    @Test fun `fromWire returns null for unknown name`() {
        assertNull(PaletteEnum.fromWire("chartreuse"))
        assertNull(PaletteEnum.fromWire("#FF00AA"))
        assertNull(PaletteEnum.fromWire(""))
    }

    @Test fun `fromWire is case sensitive`() {
        // Spec says literal lowercase tokens. Uppercase must not resolve.
        assertNull(PaletteEnum.fromWire("CYAN"))
        assertNull(PaletteEnum.fromWire("Amber"))
    }
}
