package jerzmanowice.terminal

import java.io.PrintStream
import java.util.Locale

internal class TerminalPrintStream(private val onChars: (String) -> Unit) : PrintStream(nullOutputStream()) {
    override fun write(b: Int) = throw UnsupportedOperationException()
    override fun write(buf: ByteArray?, off: Int, len: Int) = throw UnsupportedOperationException()
    override fun write(b: ByteArray?) = throw UnsupportedOperationException()

    override fun append(csq: CharSequence?): PrintStream = throw UnsupportedOperationException()
    override fun append(csq: CharSequence?, start: Int, end: Int): PrintStream = throw UnsupportedOperationException()
    override fun append(c: Char): PrintStream = throw UnsupportedOperationException()

    override fun printf(format: String?, vararg args: Any?): PrintStream = throw UnsupportedOperationException()
    override fun printf(l: Locale?, format: String?, vararg args: Any?): PrintStream = throw UnsupportedOperationException()
    override fun format(format: String?, vararg args: Any?): PrintStream = throw UnsupportedOperationException()
    override fun format(l: Locale?, format: String?, vararg args: Any?): PrintStream = throw UnsupportedOperationException()

    override fun print(s: CharArray?) = throw UnsupportedOperationException()
    override fun println(x: CharArray?) = throw UnsupportedOperationException()

    override fun println() = newLine()
    override fun println(x: Boolean) = println(x.toString())
    override fun println(x: Char) = println(x.toString())
    override fun println(x: Int) = println(x.toString())
    override fun println(x: Long) = println(x.toString())
    override fun println(x: Float) = println(x.toString())
    override fun println(x: Double) = println(x.toString())
    override fun println(x: Any?) = println(x.toString())
    override fun println(x: String?) = run { print(x); newLine() }

    override fun print(b: Boolean) = print(b.toString())
    override fun print(c: Char) = print(c.toString())
    override fun print(i: Int) = print(i.toString())
    override fun print(l: Long) = print(l.toString())
    override fun print(f: Float) = print(f.toString())
    override fun print(d: Double) = print(d.toString())
    override fun print(obj: Any?) = print(obj.toString())

    override fun print(s: String?) = onChars(s.toString())
    private fun newLine(): Unit = onChars(NEWLINE)
}