package jerzmanowice.terminal

import java.awt.Color

internal class AnsiTerminal(
    val linesCount: Int,
    val onLinesChanged: (lines: Sequence<List<Symbol>>, cursorPosition: Point?) -> Unit = { _, _ -> }
) {
    private var background: Color? = null
    private var foreground: Color = Color.WHITE

    private val lines: MutableList<MutableList<Symbol>> = mutableListOf(mutableListOf())
    private var cursorPosition: Point = Point(0, 0)

    fun onChars(text: String) {
        for (c in text) {
            if (c == NEWLINE) {
                if (cursorPosition.y == linesCount - 1) {
                    cursorPosition = cursorPosition.copy(x = 0)
                    lines.removeAt(0)
                    lines.add(mutableListOf())
                } else {
                    cursorPosition = cursorPosition.copy(x = 0, y = cursorPosition.y + 1)
                    if (cursorPosition.y == lines.size) {
                        lines.add(mutableListOf())
                    }
                }
            } else {
                val symbol = Symbol(c, foreground, background)
                with(lines[cursorPosition.y]) {
                    if (cursorPosition.x < size) {
                        set(cursorPosition.x, symbol)
                    } else {
                        repeat(cursorPosition.x - size) {
                            add(Symbol(' ', foreground, background))
                        }
                        add(symbol)
                    }
                }

                cursorPosition = cursorPosition.copy(x = cursorPosition.x + 1)
            }
        }

        onLinesChanged(lines.asSequence(), cursorPosition)
    }
}