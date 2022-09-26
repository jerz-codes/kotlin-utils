package jerz.codes.terminal

import java.awt.Color
import kotlin.streams.asSequence

internal class AnsiTerminal(
    val linesCount: Int,
    val onFeedChanged: (feed: TerminalFeed) -> Unit = { }
) {
    private var partialAnsiSequence: StringBuilder? = null

    private var background: Color? = null
    private var foreground: Color = Color.WHITE

    private val lines: MutableList<MutableList<Symbol>> = mutableListOf(mutableListOf())
    private var showCursor: Boolean = true
    private var cursorPosition: Point = Point(0, 0)

    private val standardColors = mapOf(
        30 to Color(0, 0, 0),
        31 to Color(170, 0, 0),
        32 to Color(0, 170, 0),
        33 to Color(170, 85, 0),
        34 to Color(0, 0, 170),
        35 to Color(170, 0, 170),
        36 to Color(0, 170, 170),
        37 to Color(170, 170, 170),
        // bright pallette
        90 to Color(85, 85, 85),
        91 to Color(255, 85, 85),
        92 to Color(85, 255, 85),
        93 to Color(255, 255, 85),
        94 to Color(85, 85, 255),
        95 to Color(255, 85, 255),
        96 to Color(85, 255, 255),
        97 to Color(255, 255, 255),
    )

    init {
        notifyListerners()
    }

    fun lockForRead(block: () -> Unit) = synchronized(this) {
        // prevent readln messing up the ANSI sequence
        if (partialAnsiSequence == null) block()
    }

    @Synchronized
    fun onChars(text: String) {
        text.codePoints()
            .asSequence()
            .forEach { codePoint ->
                val glyph = String(Character.toChars(codePoint))

                val ansiSequence = partialAnsiSequence
                if (ansiSequence != null) {
                    val char = glyph.single()

                    if (ansiSequence.isEmpty()) {
                        check(char == '[') { "Unsupported ANSI control code" }
                        ansiSequence.append(char)
                    } else {
                        ansiSequence.append(char)
                        if (char in '\u0040'..'\u007e') {
                            applyAnsiSequence(ansiSequence.toString().drop(1))
                            partialAnsiSequence = null
                        }
                    }
                } else if (glyph == ESCAPE) {
                    partialAnsiSequence = StringBuilder()
                } else if (glyph == NEWLINE) {
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

    private val ansiSequenceCache = mutableMapOf<String, () -> Unit>()

    private fun applyAnsiSequence(ansiSequence: String) {
        val sequenceAction = ansiSequenceCache.getOrPut(ansiSequence) {
            createAnsiSequenceAction(ansiSequence)
        }

        sequenceAction()
    }

    private fun createAnsiSequenceAction(ansiSequence: String): () -> Unit {
        when(ansiSequence.last()) {
            'm' -> return createSelectGraphicRenditionAction(ansiSequence.dropLast(1))
            'H' -> {
                val (row, col) = ansiSequence.dropLast(1).split(';')
                    .also { check(it.size == 2) { "Provide both coordinates for Cursor Position ANSI sequence" } }
                    .map { it.toInt() }

                return {
                    // position is 1-based
                    cursorPosition = Point(col - 1, row - 1)
                    while (cursorPosition.y >= lines.size) {
                        lines.add(mutableListOf())
                    }
                    lines[cursorPosition.y].safeSet(cursorPosition.x, Symbol(" ", foreground))
                }
            }
            'J' -> when (ansiSequence.dropLast(1)) {
                "", "0" -> return {
                    lines.forEachIndexed { index, symbols ->
                        if (index > cursorPosition.y) {
                            symbols.clear()
                        } else if (index == cursorPosition.y) {
                            for (x in cursorPosition.x until symbols.size) {
                                symbols[x] = Symbol(" ", foreground)
                            }
                        }
                    }
                }
                "1" -> return  {
                    lines.forEachIndexed { index, symbols ->
                        if (index < cursorPosition.y) {
                            symbols.clear()
                        } else if (index == cursorPosition.y) {
                            repeat(cursorPosition.x) { x ->
                                symbols[x] = Symbol(" ", foreground)
                            }
                        }
                    }
                }
                "2", "3" -> return {
                    lines.forEach { it.clear() }
                    cursorPosition = Point(0, 0)
                }
            }
            else -> when (ansiSequence) {
                "?25h" -> return { showCursor = true }
                "?25l" -> return { showCursor = false }
            }
        }

        return { throw IllegalStateException("Unsupported ANSI sequence: $ansiSequence") }
    }

    private fun createSelectGraphicRenditionAction(parametersString: String): () -> Unit {
        val parameters = buildList {
            parametersString
                .split(';')
                .mapTo(this, String::toInt)

            if (isEmpty()) add(0)
        }

        val iterator = parameters.iterator()

        when (val command = iterator.next()) {
            0 -> return {
                foreground = Color.WHITE
                background = null
            }
            // foreground color
            in 30..37 -> return { foreground = standardColors.getValue(command) }
            38 -> {
                val color = parseColor(iterator)
                return { foreground = color }
            }
            39 -> return { foreground = Color.WHITE }

            // background color
            in 40..47 -> return { background = standardColors.getValue(command - 10) }
            48 -> {
                val color = parseColor(iterator)
                return { background = color }
            }
            49 -> return { background = null }

            // 3-bit bright color palette
            in 90..97 -> return { foreground = standardColors.getValue(command) }
            in 100..107 -> return { background = standardColors.getValue(command - 10) }
        }

        return { throw IllegalStateException("Unsupported SGR: $parametersString") }
    }

    private fun parseColor(paramsIterator: Iterator<Int>): Color {
        val mode = paramsIterator.next()

        check(mode == 2) { "Only '2;r;g;b' color syntax is currently supported" }

        val r = paramsIterator.next()
        val g = paramsIterator.next()
        val b = paramsIterator.next()

        return Color(r, g, b)
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

    private fun notifyListerners() = onFeedChanged(
        TerminalFeed(lines.map { it.toList() }, cursorPosition.takeIf { showCursor })
    )
}

internal data class TerminalFeed(
    val lines: List<List<Symbol>> = emptyList(),
    val cursorPosition: Point? = null
)