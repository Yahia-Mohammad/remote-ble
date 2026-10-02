package dev.warsha.remoteble.agent

import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

/**
 * The modules of a QR code for [text], row by row, `true` for dark, without the quiet zone a
 * renderer must leave around them ([QUIET_ZONE_MODULES]). Shared by the phone apps, which draw it on a
 * canvas, and the dashboard, which serves it as SVG ([qrSvg]).
 *
 * Error correction M: a pairing URI fits a small code at that level, and a screen photographed at an
 * angle loses less than print does.
 */
internal fun qrModules(text: String): List<BooleanArray> {
    val level = ErrorCorrectionLevel.MEDIUM
    val squares = QRCodeProcessor(text, level).encode(QRCodeProcessor.infoDensityForDataAndECL(text, level))
    return squares.map { row -> BooleanArray(row.size) { row[it].dark } }
}

/** The quiet zone the QR specification requires around the modules, in modules. */
internal const val QUIET_ZONE_MODULES = 4

/**
 * [qrModules] as a standalone SVG, quiet zone included, one unit per module, so it scales without
 * blurring. Each row's runs of dark modules are one rectangle in a single path, which keeps a
 * version 10 code to about ten kilobytes.
 */
internal fun qrSvg(text: String): String {
    val modules = qrModules(text)
    val size = modules.size + 2 * QUIET_ZONE_MODULES
    val path = buildString {
        modules.forEachIndexed { y, row ->
            var x = 0
            while (x < row.size) {
                if (!row[x]) { x++; continue }
                val start = x
                while (x < row.size && row[x]) x++
                append("M${start + QUIET_ZONE_MODULES} ${y + QUIET_ZONE_MODULES}h${x - start}v1h-${x - start}z")
            }
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
        """<rect width="$size" height="$size" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
}
