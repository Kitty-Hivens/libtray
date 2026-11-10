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
import java.lang.foreign.MemorySegment
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The tray next to AWT on macOS, under the threads a Compose or Swing app has:
 * AWT owns the Cocoa main thread and runs its loop there, and the app calls
 * from a background thread or from the event dispatch thread. The JVM runs
 * without -XstartOnFirstThread, as such an app does. [MacSmokeCheckKt] creates
 * its trays on the main thread itself and never covers this.
 *
 * Two orders, picked by the first argument:
 *
 *  - default: AWT is up first. From each of the two threads, Tray.create
 *    returns a tray, a setter works, and close() returns within its budget.
 *  - `before-awt`: Tray.create runs before AWT starts, as in an app that sets
 *    the tray up in main() or from a coroutine before its first window. The
 *    application object belongs to AWT, so this create has to return null and
 *    leave it alone: AWT must still come up as its own NSApplicationAWT.
 *
 * Exit code 0 means every step passed.
 */
fun main(args: Array<String>) {
    startWatchdog()
    if (GraphicsEnvironment.isHeadless()) fail("AWT came up headless, so there is no AWT main thread to check against")
    if (args.firstOrNull() == "before-awt") checkBeforeAwt() else checkWithAwtUp()
    step("PASS")
    exitProcess(0)
}

private fun checkWithAwtUp() {
    val window = showWindow()
    thread { createUpdateClose("a background thread") }.join()
    EventQueue.invokeAndWait { createUpdateClose("the event dispatch thread") }
    EventQueue.invokeAndWait { window.dispose() }
}

private fun checkBeforeAwt() {
    var early: Tray? = null
    thread { early = Tray.create(builder()) }.join()
    step("Tray.create before AWT started returned ${if (early == null) "null" else "a tray"}")

    val window = showWindow()
    val applicationClass = applicationClassName()
    step("NSApp is a $applicationClass")
    EventQueue.invokeAndWait { window.dispose() }
    early?.close()

    if (early != null) fail("Tray.create before AWT started returned a tray, it has to return null")
    if (applicationClass != "NSApplicationAWT") fail("AWT came up with NSApp as $applicationClass, not its own NSApplicationAWT")
}

private fun showWindow(): Frame {
    lateinit var window: Frame
    EventQueue.invokeAndWait {
        window = Frame("libtray AWT check")
        window.setSize(200, 100)
        window.isVisible = true
    }
    step("AWT is up and its window is showing")
    return window
}

/** `[[NSApplication sharedApplication] className]`. AWT is up by now, so this does not create one. */
private fun applicationClassName(): String {
    val bindings = ObjcBindings.load() ?: fail("ObjcBindings.load returned null")
    val msgSendId = bindings.handle("objc_msgSend_id")
    val app = msgSendId.invokeExact(bindings.cls("NSApplication"), bindings.sel("sharedApplication")) as MemorySegment
    val name = msgSendId.invokeExact(app, bindings.sel("className")) as MemorySegment
    val utf8 = msgSendId.invokeExact(name, bindings.sel("UTF8String")) as MemorySegment
    return utf8.reinterpret(Long.MAX_VALUE).getString(0)
}

private fun builder() = TrayBuilder(
    title = "libtray-awt-check",
    iconBytes = solidPng(32),
    tooltip = "libtray AWT check",
    menu = TrayMenu(TrayMenuItem.Standard("noop", "Noop")),
)

private fun createUpdateClose(where: String) {
    val tray = Tray.create(builder()) ?: fail("Tray.create on $where returned null")
    if (!tray.setTooltip("updated from $where")) fail("setTooltip on $where")

    val start = System.nanoTime()
    tray.close()
    val tookMs = (System.nanoTime() - start) / 1_000_000
    if (tray.isOpen) fail("still open after close() on $where")
    if (tookMs >= 2_000) fail("close() on $where fell back to the timeout ($tookMs ms)")
    step("created, updated and closed from $where, close() took $tookMs ms")
}
