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
import java.awt.font.GlyphVector
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants.EXIT_ON_CLOSE

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
    widthInTiles: Int = 80,
    heightInTiles: Int = 24,
    fontSize: Int = 24,
    block: () -> Unit
) = terminal(widthInTiles, heightInTiles, fontSize) { terminal, mainFrame ->
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
    fun getNextPressedKeyEvent(): KeyEvent
}

fun rawTerminal(
    widthInTiles: Int = 80,
    heightInTiles: Int = 24,
    fontSize: Int = 16,
    block: RawTerminalMode.() -> Unit
) = terminal(widthInTiles, heightInTiles, fontSize) { _, mainFrame ->
    val originalStdIn = System.`in`

    val keyQueue = LinkedBlockingQueue<KeyEvent>()

    val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) = keyQueue.put(e)
    }

    try {
        System.setIn(InputStream.nullInputStream().apply { close() })
        mainFrame.addKeyListener(keyListener)
        with(RawTerminalMode(keyQueue::take)) {
            block()
        }
    } finally {
        System.setIn(originalStdIn)
        mainFrame.removeKeyListener(keyListener)
    }
}

fun interface AsyncTerminalMode {
    fun getPressedKeys(): Set<Int>
}

fun asyncTerminal(
    widthInTiles: Int = 80,
    heightInTiles: Int = 24,
    fontSize: Int = 16,
    block: AsyncTerminalMode.() -> Unit
) = terminal(widthInTiles, heightInTiles, fontSize) { _, mainFrame ->
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
    widthInTiles: Int = 80,
    heightInTiles: Int = 24,
    fontSize: Int = 16,
    handle: (terminal: AnsiTerminal, mainFrame: JFrame) -> Unit
) {
    val originalStdOut = System.`out`

    // On some JDKs the font antialiasing is broken without this
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

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

    val terminal = AnsiTerminal(linesCount = heightInTiles) { feed ->
        terminalPane.screen = feed
    }

    try {
        System.setOut(TerminalPrintStream(terminal::onChars))
        handle(terminal, mainFrame)
    } finally {
        System.setOut(originalStdOut)
        mainFrame.title = "[Program zakończony]"
    }
}

private class TerminalPane(
    widthInTiles: Int,
    val heightInTiles: Int,
    fontSize: Int,
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

                val symbolWidth = if (isEmoji) 2 * tileWidth else tileWidth

                if (background != null) {
                    g.color = background
                    g.fillRect(
                        x,
                        y * tileHeight + padding,
                        symbolWidth,
                        tileHeight
                    )
                }

                g.color = symbol.foreground
                g.drawGlyphVector(
                    glyph,
                    x.toFloat(),
                    (y * tileHeight + baselineOffset + padding).toFloat()
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
