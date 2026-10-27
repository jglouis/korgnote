package com.korgnote.server

import io.nayuki.qrcodegen.QrCode

/**
 * A pairing link as a QR code: an SVG for the Devices page, and text for a terminal. Both keep the
 * four-module quiet border scanners need, and draw dark modules on light, whatever the page's or
 * the terminal's own colours — many scanners do not read a code drawn the other way round.
 */
object PairingQr {
    private const val BORDER = 4

    fun svg(text: String, moduleSize: Int = 6): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val side = qr.size + BORDER * 2
        val path = buildString {
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) append("M${x + BORDER},${y + BORDER}h1v1h-1z")
            }
        }
        val px = side * moduleSize
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $side $side" width="$px" height="$px" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }

    /** Two rows of modules per line of half blocks, black on white through ANSI colours. */
    fun terminal(text: String): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        fun dark(x: Int, y: Int) = x in 0 until qr.size && y in 0 until qr.size && qr.getModule(x, y)
        return buildString {
            for (y in -BORDER until qr.size + BORDER step 2) {
                append("\u001b[30;107m")
                for (x in -BORDER until qr.size + BORDER) {
                    append(
                        when {
                            dark(x, y) && dark(x, y + 1) -> '█'
                            dark(x, y) -> '▀'
                            dark(x, y + 1) -> '▄'
                            else -> ' '
                        },
                    )
                }
                append("\u001b[0m\n")
            }
        }
    }
}
