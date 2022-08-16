package jerzmanowice.terminal

import okio.Pipe
import okio.buffer
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants.EXIT_ON_CLOSE

fun interface TerminalContext {
    fun log(message: String)
}

fun terminal(
    widthInTiles: Int = 80,
    heightInTiles: Int = 24,
    fontSize: Int = 16,
    block: TerminalContext.() -> Unit
) {
    val originalStdOut = System.`out`
    val originalStdIn = System.`in`

    // On some JDKs the font antialiasing is broken without this
    System.setProperty("awt.useSystemAAFontSettings","on")
    System.setProperty("swing.aatext", "true")

    try {
        val terminalPane = TerminalPane(widthInTiles, heightInTiles, fontSize)

        val mainFrame = JFrame("Terminal")
            .apply {
                defaultCloseOperation = EXIT_ON_CLOSE

                contentPane = terminalPane
                isResizable = false

                pack()
                isVisible = true

                requestFocusInWindow()
            }

        val terminal = AnsiTerminal(linesCount = heightInTiles) { lines, cursorPosition ->
            terminalPane.screen = buildMap {
                lines.forEachIndexed { y, line ->
                    line.asSequence()
                        .take(terminalPane.widthInTiles)
                        .forEachIndexed { x, symbol ->
                            put(Point(x, y), symbol)
                        }
                }

                if (cursorPosition != null) {
                    val cursorSymbol = get(cursorPosition)
                        ?.copy(background = Color.LIGHT_GRAY)
                        ?: Symbol(char = ' ', foreground = Color.WHITE, background = Color.LIGHT_GRAY)

                    put(cursorPosition, cursorSymbol)
                }
            }
        }

        val readlnPipe = Pipe(1024)

        val keyListener = object : KeyListener {
            val readlnSink = readlnPipe.sink.buffer()
            var input = mutableListOf<String>()

            override fun keyTyped(e: KeyEvent) {
                when (e.extendedKeyCode) {
                    0x0a -> {
                        input.forEach(readlnSink::writeUtf8)
                        readlnSink.writeUtf8("\n")
                        readlnSink.flush()
                        input.clear()

                        terminal.onChars("\n")
                    }
                    0x08 -> {
                        if (input.removeLastOrNull() != null) {
                            terminal.onBackspace()
                        }
                    }
                    0x7f -> Unit // delete, we do not support it at the moment
                    else -> {
                        val utfString = "${e.keyChar}"
                        input.add(utfString)
                        terminal.onChars(utfString)
                    }
                }
            }

            override fun keyPressed(e: KeyEvent) = Unit
            override fun keyReleased(e: KeyEvent) = Unit
        }

        System.setIn(readlnPipe.source.buffer().inputStream())
        System.setOut(TerminalPrintStream(terminal::onChars))

        try {
            mainFrame.addKeyListener(keyListener)
            StdOutTerminalContext(originalStdOut).block()
        } finally {
            mainFrame.removeKeyListener(keyListener)
            mainFrame.title = "[Program zakończony]"
        }
    } finally {
        System.setOut(originalStdOut)
        System.setIn(originalStdIn)
    }
}

private class TerminalPane(
    val widthInTiles: Int,
    val heightInTiles: Int,
    fontSize: Int,
) : JPanel() {

    val standardTileFont: Font = Font
        .createFont(Font.TRUETYPE_FONT, javaClass.getResourceAsStream("/fonts/UbuntuMono-Bold.ttf"))
        .deriveFont(Font.BOLD, fontSize.toFloat())

    val tileWidth: Int
    val tileHeight: Int
    val baselineOffset: Int
    val padding: Int

    init {
        getFontMetrics(standardTileFont).run {
            tileWidth = charWidth(' ')
            tileHeight = ascent + descent
            baselineOffset = ascent

            padding = tileWidth / 5
        }

        preferredSize = Dimension(
            widthInTiles * tileWidth + padding * 2,
            heightInTiles * tileHeight + padding * 2
        )
    }

    override fun paintComponent(g: Graphics) {
        (g as Graphics2D).setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
        )
        g.font = standardTileFont

        g.color = Color.BLACK
        g.fillRect(0, 0, width, height)

        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                val symbol = screen[Point(x, y)] ?: continue

                if (symbol.background != null) {
                    g.color = symbol.background
                    g.fillRect(
                        x * tileWidth + padding,
                        y * tileHeight + padding,
                        tileWidth,
                        tileHeight
                    )
                }

                g.color = symbol.foreground
                g.drawString(
                    "${symbol.char}",
                    x * tileWidth + padding,
                    y * tileHeight + baselineOffset + padding
                )
            }
        }
    }

    var screen: Map<Point, Symbol> = emptyMap()
        set(value) {
            field = value
            repaint()
        }
}
