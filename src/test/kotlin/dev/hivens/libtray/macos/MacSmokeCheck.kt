package dev.hivens.libtray.macos

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Non-interactive macOS check for CI, run by `./gradlew macSmokeCheck`. A
 * status item can only be created on the Cocoa main thread, which a JUnit
 * worker is not, so this is a program launched with -XstartOnFirstThread.
 *
 * Clicks are built as `NSEvent`s in-process and sent straight to the click
 * overlay's mouse handlers, the path a real click takes once AppKit has hit
 * tested it. Posting them through the window server instead would need an
 * accessibility grant the runner does not have. Covered:
 *
 *  - create, update, and fire a menu item through AppKit
 *  - primary press and release with macosMenuOnPrimaryClick false: Activated
 *  - middle release: MiddleActivated
 *  - a press on the button itself, as VoiceOver does: Activated
 *  - right press opens the menu, and close() from the MenuRequested listener
 *    tears the item down while the menu is still tracking
 *  - close() from a background thread while the main thread runs the loop
 *
 * Exit code 0 means every step passed.
 */
fun main() {
    val watchdog = Thread {
        Thread.sleep(90_000)
        fail("timed out")
    }.apply { isDaemon = true; start() }

    val bindings = ObjcBindings.load() ?: fail("ObjcBindings.load returned null")
    val clicks = SyntheticClicks(bindings)

    val events = LinkedBlockingQueue<TrayEvent>()
    val tray = openTray(menuOnPrimaryClick = false)
    tray.onEvent { events.add(it) }
    step("created")

    check(tray.setTooltip("updated")) { "setTooltip" }
    check(tray.setIcon(icon(22))) { "setIcon" }
    check(tray.setMenu(menu())) { "setMenu" }
    step("updated")

    // [menu performActionForItemAtIndex:0] sends the item's action to its
    // target, which is the path a real click on the item takes.
    val nsMenu = field<MemorySegment>(tray, "currentMenu")
    if (nsMenu.address() == 0L) fail("no NSMenu was built")
    bindings.handle("objc_msgSend_void_long")
        .invokeExact(nsMenu, bindings.sel("performActionForItemAtIndex:"), 0L) as Unit
    expect(events, TrayEvent.MenuItemSelected("noop"), "menu item action")

    val overlay = field<MemorySegment>(tray, "statusView")
    if (overlay.address() == 0L) fail("no click overlay was installed")
    clicks.send(overlay, "mouseDown:", NS_LEFT_MOUSE_DOWN)
    clicks.send(overlay, "mouseUp:", NS_LEFT_MOUSE_UP)
    expect(events, TrayEvent.Activated, "primary click")

    clicks.sendMiddleUp(overlay)
    expect(events, TrayEvent.MiddleActivated, "middle click")

    // Button actions this soon after overlay activity are dropped on purpose.
    Thread.sleep(700)
    val button = field<MemorySegment>(tray, "statusButton")
    bindings.handle("objc_msgSend_void_id").invokeExact(button, bindings.sel("performClick:"), MemorySegment.NULL) as Unit
    expect(events, TrayEvent.Activated, "button action (VoiceOver path)")

    // Right press: MenuRequested fires, then performClick: tracks the menu on
    // this thread. The listener closes the tray from the event thread, so the
    // teardown has to run from the main queue inside the menu's tracking loop.
    val closeTookMs = AtomicLong(-1)
    val closed = CountDownLatch(1)
    tray.onEvent {
        if (it == TrayEvent.MenuRequested) {
            Thread.sleep(300)  // let the menu come up
            val start = System.nanoTime()
            tray.close()
            closeTookMs.set((System.nanoTime() - start) / 1_000_000)
            closed.countDown()
        }
    }
    clicks.send(overlay, "rightMouseDown:", NS_RIGHT_MOUSE_DOWN)
    if (!closed.await(10, TimeUnit.SECONDS)) fail("close() from the MenuRequested listener did not return")
    if (tray.isOpen) fail("still open after close() during menu tracking")
    if (closeTookMs.get() >= 2_000) fail("close() during menu tracking fell back to the timeout (${closeTookMs.get()} ms)")
    step("closed during menu tracking in ${closeTookMs.get()} ms")

    // close() from a thread other than main has to get its teardown run by
    // the main queue, so the Cocoa loop has to be running for it.
    val second = openTray(menuOnPrimaryClick = true)
    Thread({
        Thread.sleep(500)
        val start = System.nanoTime()
        second.close()
        val tookMs = (System.nanoTime() - start) / 1_000_000
        if (second.isOpen) fail("still open after close()")
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

/**
 * Builds mouse `NSEvent`s and sends them to a view. Needs one `objc_msgSend`
 * shape the backend has no use for, the class method
 * `mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:clickCount:pressure:`,
 * which takes an `NSPoint` by value. That method has no button argument and
 * reports button 0 for every other-button event, so the middle click is
 * built as a `CGEvent` with the centre button and wrapped by
 * `[NSEvent eventWithCGEvent:]`. Creating a `CGEvent` does not post it, so
 * no permission is involved.
 */
private class SyntheticClicks(private val bindings: ObjcBindings) {
    private val arena = Arena.ofShared()
    private val point = MemoryLayout.structLayout(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE)
    private val mouseEvent: MethodHandle = Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("libobjc.A.dylib", arena).find("objc_msgSend").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,  // NSEvent class, SEL
            ValueLayout.JAVA_LONG,                     // NSEventType
            point,                                     // NSPoint location
            ValueLayout.JAVA_LONG,                     // NSEventModifierFlags
            ValueLayout.JAVA_DOUBLE,                   // NSTimeInterval timestamp
            ValueLayout.JAVA_LONG,                     // NSInteger windowNumber
            ValueLayout.ADDRESS,                       // NSGraphicsContext *context
            ValueLayout.JAVA_LONG,                     // NSInteger eventNumber
            ValueLayout.JAVA_LONG,                     // NSInteger clickCount
            ValueLayout.JAVA_FLOAT,                    // float pressure
        ),
    )

    fun event(type: Long): MemorySegment {
        val location = arena.allocate(point)
        return mouseEvent.invokeExact(
            bindings.cls("NSEvent"),
            bindings.sel("mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:clickCount:pressure:"),
            type, location, 0L, 0.0, 0L, MemorySegment.NULL, 0L, 1L, 1.0f,
        ) as MemorySegment
    }

    private val cgMouseEvent: MethodHandle = Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", arena)
            .find("CGEventCreateMouseEvent").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,     // CGEventRef
            ValueLayout.ADDRESS,     // CGEventSourceRef, NULL
            ValueLayout.JAVA_INT,    // CGEventType
            point,                   // CGPoint
            ValueLayout.JAVA_INT,    // CGMouseButton
        ),
    )

    fun sendMiddleUp(view: MemorySegment) {
        val cgEvent = cgMouseEvent.invokeExact(
            MemorySegment.NULL, NS_OTHER_MOUSE_UP.toInt(), arena.allocate(point), CG_MOUSE_BUTTON_CENTER,
        ) as MemorySegment
        if (cgEvent.address() == 0L) fail("CGEventCreateMouseEvent returned NULL")
        val event = bindings.handle("objc_msgSend_id_id")
            .invokeExact(bindings.cls("NSEvent"), bindings.sel("eventWithCGEvent:"), cgEvent) as MemorySegment
        if (event.address() == 0L) fail("eventWithCGEvent: returned nil")
        val button = bindings.handle("objc_msgSend_long").invokeExact(event, bindings.sel("buttonNumber")) as Long
        if (button != 2L) fail("the wrapped middle-button event reports buttonNumber $button")
        bindings.handle("objc_msgSend_void_id").invokeExact(view, bindings.sel("otherMouseUp:"), event) as Unit
    }

    fun send(view: MemorySegment, selector: String, type: Long) {
        val event = event(type)
        if (event.address() == 0L) fail("could not build an NSEvent of type $type")
        bindings.handle("objc_msgSend_void_id").invokeExact(view, bindings.sel(selector), event) as Unit
    }
}

private const val NS_LEFT_MOUSE_DOWN = 1L
private const val NS_LEFT_MOUSE_UP = 2L
private const val NS_RIGHT_MOUSE_DOWN = 3L
private const val NS_OTHER_MOUSE_UP = 26L  // also kCGEventOtherMouseUp
private const val CG_MOUSE_BUTTON_CENTER = 2  // kCGMouseButtonCenter

private fun openTray(menuOnPrimaryClick: Boolean): Tray = Tray.create(
    TrayBuilder(
        title = "libtray-mac-check",
        iconBytes = icon(32),
        tooltip = "libtray check",
        menu = menu(),
        macosMenuOnPrimaryClick = menuOnPrimaryClick,
    ),
) ?: fail("Tray.create returned null")

private fun menu() = TrayMenu(TrayMenuItem.Standard("noop", "Noop"), TrayMenuItem.Standard("exit", "Exit"))

private inline fun <reified T> field(tray: Tray, name: String): T =
    AppKitTrayImpl::class.java.getDeclaredField(name).apply { isAccessible = true }.get(tray) as T

private fun expect(events: LinkedBlockingQueue<TrayEvent>, expected: TrayEvent, what: String) {
    val got = events.poll(5, TimeUnit.SECONDS)
    if (got != expected) fail("$what delivered $got, expected $expected")
    step("$what reached the listener")
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
