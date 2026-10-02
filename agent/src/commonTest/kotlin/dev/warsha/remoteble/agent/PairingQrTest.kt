package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The QR code's shape. Whether it decodes was checked against an independent decoder (macOS Vision,
 * on the PNG and on the dashboard's SVG); these pin what such a check relies on.
 */
class PairingQrTest {
    private val uri = "remoteble://192.168.1.20:8080?token=" + "t".repeat(43) + "&fp=sha256:" + "ab".repeat(32)

    @Test
    fun theCodeIsASquareOfAValidVersionWithItsThreeFinderPatterns() {
        val modules = qrModules(uri)
        val size = modules.size

        assertTrue(modules.all { it.size == size })
        assertEquals(0, (size - 17) % 4, "a QR code is 17 + 4 × version modules wide, got $size")
        for ((top, left) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            for (y in 0 until 7) for (x in 0 until 7) {
                val ring = y == 0 || y == 6 || x == 0 || x == 6
                val core = y in 2..4 && x in 2..4
                assertEquals(ring || core, modules[top + y][left + x], "finder at ($top,$left), module ($y,$x)")
            }
        }
    }

    @Test
    fun theSvgLeavesTheQuietZoneAndDrawsOnlyDarkRuns() {
        val size = qrModules(uri).size + 2 * QUIET_ZONE_MODULES
        val svg = qrSvg(uri)

        assertTrue("viewBox=\"0 0 $size $size\"" in svg, svg.take(200))
        // The first dark module of the top-left finder sits one quiet zone in from each edge.
        assertTrue("M$QUIET_ZONE_MODULES ${QUIET_ZONE_MODULES}h7v1h-7z" in svg)
    }
}
