package dev.hivens.libtray.macos

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.solidPng
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Reports what Tray.create does next to AWT on macOS, under the threads a
 * Compose or Swing app has: AWT owns the Cocoa main thread, and the app calls
 * from the event dispatch thread or a background thread. [MacSmokeCheckKt]
 * never covers this, since it creates its trays on the main thread itself.
 *
 * It reports rather than judges, so a null from Tray.create still exits 0.
 * Exit code 3 means something hung and 4 means AWT came up headless, which
 * says nothing about the question.
 */
fun main() {
    thread(isDaemon = true) {
        Thread.sleep(TIMEOUT_MS)
        report("TIMED OUT after $TIMEOUT_MS ms")
        // Not exitProcess: its shutdown hooks could wait on whatever hung.
        Runtime.getRuntime().halt(3)
    }

    if (GraphicsEnvironment.isHeadless()) {
        report("INCONCLUSIVE: AWT is headless")
        exitProcess(4)
    }

    lateinit var window: Frame
    EventQueue.invokeAndWait {
        window = Frame("libtray probe")
        window.setSize(200, 100)
        window.isVisible = true
    }
    report("AWT is up and its window is showing")

    thread { tryCreate("a background thread") }.join()
    EventQueue.invokeAndWait { tryCreate("the event dispatch thread") }

    EventQueue.invokeAndWait { window.dispose() }
    report("DONE")
    exitProcess(0)
}

private fun tryCreate(where: String) {
    val tray = Tray.create(TrayBuilder(title = "libtray-awt-probe", iconBytes = solidPng(32)))
    report("Tray.create on $where returned ${if (tray == null) "null" else "a tray"}")
    tray?.close()
}

private fun report(what: String) = println("[awt-probe] $what")

private const val TIMEOUT_MS = 60_000L
