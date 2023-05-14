package jerz.codes.terminal

import okio.Pipe
import okio.buffer
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.GlyphVector
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants.EXIT_ON_CLOSE

private const val DEFAULT_TERMINAL_WIDTH = 80
private const val DEFAULT_TERMINAL_HEIGHT = 24
private const val DEFAULT_TERMINAL_FONT_SIZE = 24

private val stdOut = System.`out`
private val startAt = System.nanoTime()
fun log(message: String) {
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startAt)
    val thread = Thread.currentThread().name

    message.lineSequence().forEach { line ->
        with(stdOut) {
            print("${elapsedMs}ms".padEnd(length = 16))
            print(thread.padEnd(length = 32))
            println(line)
        }
    }
}

fun terminal(
    widthInTiles: Int = DEFAULT_TERMINAL_WIDTH,
    heightInTiles: Int = DEFAULT_TERMINAL_HEIGHT,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    squareTiles: Boolean = false,
    block: () -> Unit
) = terminal(widthInTiles, heightInTiles, fontSize, squareTiles) { terminal, mainFrame, _ ->
    val originalStdIn = System.`in`

    val readlnPipe = Pipe(1024)

    val keyListener = object : KeyListener {
        val readlnSink = readlnPipe.sink.buffer()
        var input = mutableListOf<String>()

        override fun keyTyped(e: KeyEvent) {
            terminal.lockForRead {
                when (e.keyChar.code) {
                    0x1b -> Unit // escape, ignore this because it messes up with ANSI sequences handling
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
        }

        override fun keyPressed(e: KeyEvent) = Unit
        override fun keyReleased(e: KeyEvent) = Unit
    }

    try {
        System.setIn(readlnPipe.source.buffer().inputStream())
        mainFrame.addKeyListener(keyListener)
        block()
    } finally {
        System.setIn(originalStdIn)
        mainFrame.removeKeyListener(keyListener)
    }
}

fun interface RawTerminalMode {
    fun getNextEvent(): RawTerminalEvent
}

tailrec fun RawTerminalMode.getNextKeyEvent(): KeyEvent = when (val e = getNextEvent()) {
    is RawTerminalEvent.KeyPressed -> e.keyEvent
    is RawTerminalEvent.MouseClicked,
    is RawTerminalEvent.MouseMoved -> getNextKeyEvent()
}

data class TileCoords(val x: Int, val y: Int)

sealed class RawTerminalEvent {
    data class KeyPressed(val keyEvent: KeyEvent) : RawTerminalEvent()
    data class MouseMoved(val tile: TileCoords?) : RawTerminalEvent()
    data class MouseClicked(val tile: TileCoords, val mouseEvent: MouseEvent) : RawTerminalEvent()
}

fun rawTerminal(
    widthInTiles: Int = DEFAULT_TERMINAL_WIDTH,
    heightInTiles: Int = DEFAULT_TERMINAL_HEIGHT,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    squareTiles: Boolean = false,
    block: RawTerminalMode.() -> Unit
) = terminal(widthInTiles, heightInTiles, fontSize, squareTiles) { _, mainFrame, terminalPane ->
    val originalStdIn = System.`in`

    val keyQueue = LinkedBlockingQueue<RawTerminalEvent>()

    val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) = keyQueue.put(RawTerminalEvent.KeyPressed(e))
    }

    val mouseListener = object : MouseAdapter() {
        private var mousePosition: TileCoords? = null

        override fun mouseClicked(e: MouseEvent) {
            val clickCoords = terminalPane.mapCoords(e.x, e.y)
            if (clickCoords != null) {
                keyQueue.put(RawTerminalEvent.MouseClicked(clickCoords, e))
            }
        }

        override fun mouseMoved(e: MouseEvent) {
            val moveCoords = terminalPane.mapCoords(e.x, e.y)
            if (moveCoords != null && moveCoords != mousePosition) {
                mousePosition = moveCoords
                keyQueue.put(RawTerminalEvent.MouseMoved(moveCoords))
            }
        }

        override fun mouseEntered(e: MouseEvent) {
            val moveCoords = terminalPane.mapCoords(e.x, e.y)
            if (moveCoords != null && moveCoords != mousePosition) {
                mousePosition = moveCoords
                keyQueue.put(RawTerminalEvent.MouseMoved(moveCoords))
            }
        }

        override fun mouseExited(e: MouseEvent) {
            mousePosition = null
            keyQueue.put(RawTerminalEvent.MouseMoved(null))
        }
    }

    try {
        System.setIn(InputStream.nullInputStream().apply { close() })
        mainFrame.addKeyListener(keyListener)
        terminalPane.addMouseListener(mouseListener)
        terminalPane.addMouseMotionListener(mouseListener)
        terminalPane.addMouseWheelListener(mouseListener)
        with(RawTerminalMode(keyQueue::take)) {
            block()
        }
    } finally {
        System.setIn(originalStdIn)
        mainFrame.removeKeyListener(keyListener)
        terminalPane.removeMouseListener(mouseListener)
        terminalPane.removeMouseMotionListener(mouseListener)
        terminalPane.removeMouseWheelListener(mouseListener)
    }
}

fun interface AsyncTerminalMode {
    fun getPressedKeys(): Set<Int>
}

fun asyncTerminal(
    widthInTiles: Int = DEFAULT_TERMINAL_WIDTH,
    heightInTiles: Int = DEFAULT_TERMINAL_HEIGHT,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    squareTiles: Boolean = false,
    block: AsyncTerminalMode.() -> Unit
) = terminal(widthInTiles, heightInTiles, fontSize, squareTiles) { _, mainFrame, _ ->
    val originalStdIn = System.`in`

    val pressedKeys = mutableSetOf<Int>()

    val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            synchronized(pressedKeys) { pressedKeys.add(e.keyCode) }
        }

        override fun keyReleased(e: KeyEvent) {
            synchronized(pressedKeys) { pressedKeys.remove(e.keyCode) }
        }
    }

    try {
        System.setIn(InputStream.nullInputStream().apply { close() })
        mainFrame.addKeyListener(keyListener)
        with(AsyncTerminalMode { synchronized(pressedKeys) { pressedKeys.toSet() } }) {
            block()
        }
    } finally {
        System.setIn(originalStdIn)
        mainFrame.removeKeyListener(keyListener)
    }
}

private fun terminal(
    widthInTiles: Int = DEFAULT_TERMINAL_WIDTH,
    heightInTiles: Int = DEFAULT_TERMINAL_HEIGHT,
    fontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    squareTiles: Boolean = false,
    handle: (terminal: AnsiTerminal, mainFrame: JFrame, terminalPane: TerminalPane) -> Unit
) {
    val originalStdOut = System.`out`

    // On some JDKs the font antialiasing is broken without this
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    val terminalPane = TerminalPane(widthInTiles, heightInTiles, fontSize, squareTiles)

    val mainFrame = JFrame("Terminal")
        .apply {
            defaultCloseOperation = EXIT_ON_CLOSE

            contentPane = terminalPane
            isResizable = false

            pack()
            isVisible = true

            requestFocusInWindow()
        }

    val terminal = AnsiTerminal(linesCount = heightInTiles) { feed ->
        terminalPane.screen = feed
    }

    try {
        System.setOut(TerminalPrintStream(terminal::onChars))
        handle(terminal, mainFrame, terminalPane)
        mainFrame.title = "[Program zakończony]"
    } catch (e: Throwable) {
        mainFrame.title = "☠️ CRASH!!! ☠️"
        throw e
    } finally {
        System.setOut(originalStdOut)
    }
}

private class TerminalPane(
    val widthInTiles: Int,
    val heightInTiles: Int,
    fontSize: Int,
    val squareTiles: Boolean,
) : JPanel() {

    val standardTileFont: Font = Font
        .createFont(Font.TRUETYPE_FONT, javaClass.getResourceAsStream("/fonts/UbuntuMono-Bold.ttf"))
        .deriveFont(Font.BOLD, fontSize.toFloat())

    // 0.8f is an experimentally chosen scaling factor which makes the emojis rougly twice as wide as the regular text
    val emojiTileFont: Font = Font
        .createFont(Font.TRUETYPE_FONT, javaClass.getResourceAsStream("/fonts/NotoEmoji-Bold.ttf"))
        .deriveFont(Font.BOLD, fontSize.toFloat() * 0.8f)

    val tileWidth: Int
    val tileHeight: Int
    val textWidth: Int
    val baselineOffset: Int
    val padding: Int

    init {
        getFontMetrics(standardTileFont).run {
            tileHeight = ascent + descent
            textWidth = charWidth(' ')
            tileWidth = if (squareTiles) tileHeight else textWidth
            baselineOffset = ascent

            padding = tileWidth / 5
        }

        preferredSize = Dimension(
            widthInTiles * tileWidth + padding * 2,
            heightInTiles * tileHeight + padding * 2
        )
    }

    fun mapCoords(x: Int, y: Int): TileCoords? {
        val tileX = ((x - padding) / tileWidth + 1).takeIf { it in 1..widthInTiles } ?: return null
        val tileY = ((y - padding) / tileHeight + 1).takeIf { it in 1..heightInTiles } ?: return null

        return TileCoords(tileX, tileY)
    }

    val glyphsCache = mutableMapOf<String, Pair<GlyphVector, Boolean>>()

    private fun Font.canRenderText(text: String) = canDisplayUpTo(text) == -1

    override fun paintComponent(g: Graphics) {
        (g as Graphics2D).setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
        )

        g.color = Color.BLACK
        g.fillRect(0, 0, width, height)

        for (y in 0 until heightInTiles) {
            val line = screen.lines.getOrNull(y) ?: continue
            var x = padding

            line.forEachIndexed { i, symbol ->
                val (glyph, isEmoji) = glyphsCache.getOrPut(symbol.glyph) {
                    val isEmoji = !standardTileFont.canRenderText(symbol.glyph)

                    val font = if (isEmoji) emojiTileFont else standardTileFont

                    val glyph = font.createGlyphVector(
                        g.fontRenderContext,
                        symbol.glyph
                    )

                    glyph to isEmoji
                }

                val background = when {
                    screen.cursorPosition?.y == y && screen.cursorPosition?.x == i -> Color.LIGHT_GRAY
                    else -> symbol.background
                }

                val symbolWidth = when {
                    squareTiles -> tileWidth
                    isEmoji -> 2 * tileWidth
                    else -> tileWidth
                }

                if (background != null) {
                    g.color = background
                    g.fillRect(
                        x,
                        y * tileHeight + padding,
                        symbolWidth,
                        tileHeight
                    )
                }

                val xOffset = if (isEmoji) 0f else (tileWidth - textWidth) / 2f
                val yOffset = if (isEmoji) tileHeight * (-0.05f) else 0f

                g.color = symbol.foreground
                g.drawGlyphVector(
                    glyph,
                    x + xOffset,
                    y * tileHeight + baselineOffset + yOffset + padding
                )

                x += symbolWidth
            }

            if (screen.cursorPosition != null && screen.cursorPosition?.y == y && screen.cursorPosition?.x == line.size) {
                g.color = Color.LIGHT_GRAY
                g.fillRect(
                    x,
                    y * tileHeight + padding,
                    tileWidth,
                    tileHeight
                )
            }
        }
    }

    var screen: TerminalFeed = TerminalFeed()
        set(value) {
            field = value
            repaint()
        }
}
