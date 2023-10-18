package jerz.codes.pixelart

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_ARGB
import java.io.InputStream
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants.EXIT_ON_CLOSE

fun pixelArt(
    widthInTiles: Int,
    heightInTiles: Int,
    tileSize: Int,
    block: PixelArtMode.() -> Unit
) {
    // On some JDKs the font antialiasing is broken without this
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    val pixelArtPane = PixelArtPane(widthInTiles, heightInTiles, tileSize)

    val mainFrame = JFrame("Terminal")
        .apply {
            defaultCloseOperation = EXIT_ON_CLOSE

            contentPane = pixelArtPane
            isResizable = false

            pack()
            isVisible = true

            requestFocusInWindow()
        }

    val originalStdIn = System.`in`

    val keyListener = object : KeyAdapter() {
        private val pressedKeys = mutableSetOf<Int>()

        fun getPressedKeys(): Set<Int> {
            return synchronized(pressedKeys) { pressedKeys }
        }

        override fun keyPressed(e: KeyEvent) {
            synchronized(pressedKeys) { pressedKeys.add(e.keyCode) }
        }

        override fun keyReleased(e: KeyEvent) {
            synchronized(pressedKeys) { pressedKeys.remove(e.keyCode) }
        }
    }

    val mouseListener = object : MouseAdapter() {
        private var mousePosition: Point? = null
        private val mouseButtons = mutableSetOf<Int>()

        fun getMouseState(): MouseState? = mousePosition?.let { MouseState(it, mouseButtons) }

        override fun mousePressed(e: MouseEvent) {
            synchronized(this) { mouseButtons.add(e.button) }
        }

        override fun mouseReleased(e: MouseEvent) {
            synchronized(this) { mouseButtons.remove(e.button) }
        }

        override fun mouseMoved(e: MouseEvent) {
            val moveCoords = pixelArtPane.mapCoords(e.x, e.y)
            if (moveCoords != mousePosition) {
                synchronized(this) { mousePosition = moveCoords }
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            val moveCoords = pixelArtPane.mapCoords(e.x, e.y)
            if (moveCoords != mousePosition) {
                synchronized(this) { mousePosition = moveCoords }
            }
        }

        override fun mouseEntered(e: MouseEvent) {
            val moveCoords = pixelArtPane.mapCoords(e.x, e.y)
            if (moveCoords != null && moveCoords != mousePosition) {
                synchronized(this) { mousePosition = moveCoords }
            }
        }

        override fun mouseExited(e: MouseEvent) {
            synchronized(this) { mousePosition = null }
        }
    }

    mainFrame.addKeyListener(keyListener)
    pixelArtPane.addMouseListener(mouseListener)
    pixelArtPane.addMouseWheelListener(mouseListener)
    pixelArtPane.addMouseMotionListener(mouseListener)

    try {
        System.setIn(InputStream.nullInputStream().apply { close() })

        val drawer = PixelDrawer(widthInTiles, heightInTiles)

        with(
            object : PixelArtMode {
                override fun getPressedKeys(): Set<Int> = keyListener.getPressedKeys()
                override fun getMouseState(): MouseState? = mouseListener.getMouseState()
                override fun drawScreen(block: PixelDrawer.() -> Unit) {
                    pixelArtPane.screen = drawer
                        .apply {
                            synchronized(offscreenBuffer) {
                                block()
                            }
                        }
                        .swapActiveBuffer()
                }
            },
            block
        )
        mainFrame.title = "[Program zakończony]"
    } catch (e: Throwable) {
        mainFrame.title = "☠️ CRASH!!! ☠️"
        throw e
    } finally {
        System.setIn(originalStdIn)
        mainFrame.removeKeyListener(keyListener)
        pixelArtPane.removeMouseListener(mouseListener)
        pixelArtPane.removeMouseWheelListener(mouseListener)
        pixelArtPane.removeMouseMotionListener(mouseListener)
    }
}

data class MouseState(
    val coords: Point,
    val pressedButtons: Set<Int>,
)

interface PixelArtMode {
    fun getPressedKeys(): Set<Int>
    fun getMouseState(): MouseState?
    fun drawScreen(block: PixelDrawer.() -> Unit)
}

class PixelDrawer(
    private val width: Int,
    private val height: Int,
) {
    private var screenBuffer = BufferedImage(width, height, TYPE_INT_ARGB)
    internal var offscreenBuffer = BufferedImage(width, height, TYPE_INT_ARGB)

    internal fun swapActiveBuffer(): BufferedImage {
        return offscreenBuffer.also {
            offscreenBuffer = screenBuffer
            screenBuffer = it
        }
    }

    fun clearScreen(color: Color) {
        with(offscreenBuffer.createGraphics()) {
            setColor(color)
            fillRect(0, 0, width, height)
        }
    }

    fun drawImage(image: Image, x: Int, y: Int) {
        with(offscreenBuffer.createGraphics()) {
            drawImage(image, x, y, null)
        }
    }

    fun putPixel(x: Int, y: Int, color: Color) {
        if (x < 0) return
        if (x >= width) return
        if (y < 0) return
        if (y >= height) return
        offscreenBuffer.setRGB(x, y, color.rgb)
    }
}

private class PixelArtPane(
    val widthInTiles: Int,
    val heightInTiles: Int,
    val tileSize: Int,
) : JPanel() {

    init {
        preferredSize = Dimension(
            widthInTiles * tileSize,
            heightInTiles * tileSize
        )
    }

    fun mapCoords(x: Int, y: Int): Point? {
        val tileX = (x / tileSize).takeIf { it in 0 until widthInTiles } ?: return null
        val tileY = (y / tileSize).takeIf { it in 0 until heightInTiles } ?: return null

        return Point(tileX, tileY)
    }

    override fun paintComponent(g: Graphics) {
        val screen = screen ?: return

        synchronized(screen) {
            (g as Graphics2D).drawImage(screen, AffineTransform().apply { setToScale(tileSize.toDouble(), tileSize.toDouble()) }, null)
        }
    }

    var screen: BufferedImage? = null
        set(value) {
            field = value
            repaint()
        }
}
