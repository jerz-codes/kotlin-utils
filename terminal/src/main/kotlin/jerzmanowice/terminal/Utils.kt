package jerzmanowice.terminal

import java.awt.Color

internal const val NEWLINE = '\n'

internal data class Point(val x: Int, val y: Int)

internal data class Symbol(
    val char: Char,
    val foreground: Color,
    val background: Color? = null,
)