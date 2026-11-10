package dev.hivens.libtray.macos

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.fail
import dev.hivens.libtray.solidPng
import dev.hivens.libtray.startWatchdog
import dev.hivens.libtray.step
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The tray next to AWT on macOS, under the threads a Compose or Swing app has:
 * AWT owns the Cocoa main thread and runs its loop there, and the app calls
 * from a background thread or from the event dispatch thread. The JVM runs
 * without -XstartOnFirstThread, as such an app does. [MacSmokeCheckKt] creates
 * its trays on the main thread itself and never covers this.
 *
 * From each of the two threads: Tray.create returns a tray, a setter works,
 * and close() returns within its budget. Exit code 0 means every step passed.
 */
fun main() {
    startWatchdog()
    if (GraphicsEnvironment.isHeadless()) fail("AWT came up headless, so there is no AWT main thread to check against")

    lateinit var window: Frame
    EventQueue.invokeAndWait {
        window = Frame("libtray AWT check")
        window.setSize(200, 100)
        window.isVisible = true
    }
    step("AWT is up and its window is showing")

    thread { createUpdateClose("a background thread") }.join()
    EventQueue.invokeAndWait { createUpdateClose("the event dispatch thread") }

    EventQueue.invokeAndWait { window.dispose() }
    step("PASS")
    exitProcess(0)
}

private fun createUpdateClose(where: String) {
    val tray = Tray.create(
        TrayBuilder(
            title = "libtray-awt-check",
            iconBytes = solidPng(32),
            tooltip = "libtray AWT check",
            menu = TrayMenu(TrayMenuItem.Standard("noop", "Noop")),
        ),
    ) ?: fail("Tray.create on $where returned null")
    if (!tray.setTooltip("updated from $where")) fail("setTooltip on $where")

    val start = System.nanoTime()
    tray.close()
    val tookMs = (System.nanoTime() - start) / 1_000_000
    if (tray.isOpen) fail("still open after close() on $where")
    if (tookMs >= 2_000) fail("close() on $where fell back to the timeout ($tookMs ms)")
    step("created, updated and closed from $where, close() took $tookMs ms")
}
