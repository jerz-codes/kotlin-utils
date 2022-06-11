package jerzmanowice.terminal

import java.io.PrintStream
import java.util.concurrent.TimeUnit

internal class StdOutTerminalContext(private val stdOut: PrintStream) : TerminalContext {
    private val startAt = System.nanoTime()

    override fun log(message: String) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startAt)
        val thread = Thread.currentThread().name

        message.lineSequence().forEach { line ->
            with (stdOut) {
                print("${elapsedMs}ms".padEnd(length = 16))
                print(thread.padEnd(length = 32))
                println(line)
            }
        }
    }
}