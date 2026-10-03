package dev.hivens.libtray.macos

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.foreign.MemorySegment
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Non-interactive macOS check for CI, run by `./gradlew macSmokeCheck`. A
 * status item can only be created on the Cocoa main thread, which a JUnit
 * worker is not, so this is a program launched with -XstartOnFirstThread.
 *
 * It creates the tray, updates it, fires the first menu item through AppKit
 * the way a click on it would, and closes the tray from another thread while
 * the main thread runs the Cocoa loop. Exit code 0 means every step passed.
 * Clicks on the icon itself are not covered: posting them would need an
 * accessibility grant the runner does not have.
 */
fun main() {
    val watchdog = Thread {
        Thread.sleep(60_000)
        fail("timed out")
    }.apply { isDaemon = true; start() }

    val tray = Tray.create(
        TrayBuilder(
            title = "libtray-mac-check",
            iconBytes = icon(32),
            tooltip = "libtray check",
            menu = TrayMenu(TrayMenuItem.Standard("noop", "Noop"), TrayMenuItem.Standard("exit", "Exit")),
        ),
    ) ?: fail("Tray.create returned null")
    step("created")

    val events = LinkedBlockingQueue<TrayEvent>()
    tray.onEvent { events.add(it) }

    check(tray.setTooltip("updated")) { "setTooltip" }
    check(tray.setIcon(icon(22))) { "setIcon" }
    check(tray.setMenu(TrayMenu(TrayMenuItem.Standard("noop", "Noop"), TrayMenuItem.Standard("exit", "Exit")))) { "setMenu" }
    step("updated")

    val bindings = ObjcBindings.load() ?: fail("ObjcBindings.load returned null")
    // [menu performActionForItemAtIndex:0] sends the item's action to its
    // target, which is the path a real click on the item takes.
    val menu = AppKitTrayImpl::class.java.getDeclaredField("currentMenu")
        .apply { isAccessible = true }
        .get(tray) as MemorySegment
    if (menu.address() == 0L) fail("no NSMenu was built")
    bindings.handle("objc_msgSend_void_long")
        .invokeExact(menu, bindings.sel("performActionForItemAtIndex:"), 0L) as Unit
    val selected = events.poll(5, TimeUnit.SECONDS)
    if (selected != TrayEvent.MenuItemSelected("noop")) fail("menu item action delivered $selected")
    step("menu item action reached the listener")

    // close() from a thread other than main has to get its teardown run by
    // the main queue, so the Cocoa loop has to be running for it.
    Thread({
        Thread.sleep(500)
        val start = System.nanoTime()
        tray.close()
        val tookMs = (System.nanoTime() - start) / 1_000_000
        if (tray.isOpen) fail("still open after close()")
        if (tookMs >= 2_000) fail("close() fell back to the timeout ($tookMs ms)")
        step("closed from a background thread in $tookMs ms")
        watchdog.interrupt()
        println("[mac-check] PASS")
        exitProcess(0)
    }, "mac-check-closer").start()

    val app = bindings.handle("objc_msgSend_id")
        .invokeExact(bindings.cls("NSApplication"), bindings.sel("sharedApplication")) as MemorySegment
    bindings.handle("objc_msgSend_void").invokeExact(app, bindings.sel("run")) as Unit
    fail("[NSApp run] returned")
}

private fun step(what: String) = println("[mac-check] $what")

private fun fail(why: String): Nothing {
    System.err.println("[mac-check] FAIL: $why")
    exitProcess(1)
}

private fun icon(size: Int): ByteArray {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    return ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
}
