package jerzmanowice.terminal

import java.awt.Color
import kotlin.streams.asSequence

internal class AnsiTerminal(
    val linesCount: Int,
    val onFeedChanged: (feed: TerminalFeed) -> Unit = { }
) {
    private var background: Color? = null
    private var foreground: Color = Color.WHITE

    private val lines: MutableList<MutableList<Symbol>> = mutableListOf(mutableListOf())
    private var cursorPosition: Point = Point(0, 0)

    init {
        notifyListerners()
    }

    @Synchronized
    fun onChars(text: String) {
        text.codePoints()
            .asSequence()
            .forEach { codePoint ->
                val glyph = String(Character.toChars(codePoint))

                if (glyph == NEWLINE) {
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
                    lines[cursorPosition.y].safeSet(cursorPosition.x, Symbol(glyph, foreground, background))
                    cursorPosition = cursorPosition.copy(x = cursorPosition.x + 1)
                }
            }

        notifyListerners()
    }

    private fun MutableList<Symbol>.safeSet(index: Int, symbol: Symbol) {
        if (index < size) {
            set(index, symbol)
        } else {
            repeat(index - size) {
                add(Symbol(" ", foreground, null))
            }
            add(symbol)
        }
    }

    @Synchronized
    fun onBackspace() {
        if (cursorPosition.x == 0) return

        lines[cursorPosition.y].removeLast()
        cursorPosition = cursorPosition.copy(x = cursorPosition.x - 1)

        notifyListerners()
    }

    private fun notifyListerners() = onFeedChanged(TerminalFeed(lines, cursorPosition))
}

internal data class TerminalFeed(
    val lines: List<List<Symbol>> = emptyList(),
    val cursorPosition: Point? = null
)