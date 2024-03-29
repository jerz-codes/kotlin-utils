package jerz.codes.terminal

import java.awt.Color

internal const val NEWLINE = "\n"
const val ESCAPE = "\u001b"

internal data class Point(val x: Int, val y: Int)

internal data class Symbol(
    val glyph: String,
    val foreground: Color,
    val background: Color? = null,
)