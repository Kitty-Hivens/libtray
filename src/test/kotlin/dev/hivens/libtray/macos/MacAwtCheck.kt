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
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
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
 *    The same from the event dispatch thread while the Cocoa main thread
 *    waits for that thread inside AWT, and close() while the main thread is
 *    blocked leaves the teardown to it.
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
    val mainThread = CocoaMainThread()
    EventQueue.invokeAndWait { checkWhileAwtWaitsForThisThread(mainThread) }
    thread { checkCloseWhileMainIsBlocked(mainThread) }.join()
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
    val tooltip = "updated from $where"
    if (!tray.setTooltip(tooltip)) fail("setTooltip on $where")
    // setTooltip only queues the change on the main queue, and close() turns
    // a queued one into a no-op, so read back what the button shows first.
    val shown = (tray as AppKitTrayImpl).shownTooltip()
    if (shown != tooltip) fail("setTooltip on $where left the button showing \"$shown\"")

    val start = System.nanoTime()
    tray.close()
    val tookMs = (System.nanoTime() - start) / 1_000_000
    if (tray.isOpen) fail("still open after close() on $where")
    if (tookMs >= 2_000) fail("close() on $where fell back to the timeout ($tookMs ms)")
    step("created, updated and closed from $where, close() took $tookMs ms")
}

/**
 * AppKit asks an AWT view for its focused element, for accessibility and for
 * the Services menu alike, and AWT gets the answer from the event dispatch
 * thread, waiting for it in a run loop mode of its own. With that thread in
 * turn waiting for libtray's work on the main thread, neither would move.
 * Runs on the event dispatch thread.
 */
private fun checkWhileAwtWaitsForThisThread(mainThread: CocoaMainThread) {
    val tray = Tray.create(builder()) ?: fail("Tray.create before the focus query returned null")
    mainThread.perform(mainThread.awtView(), "accessibilityFocusedUIElement")
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!mainThread.waitsInAwt()) {
        if (System.nanoTime() > deadline) fail("the main thread never waited for the event dispatch thread")
        Thread.sleep(10)
    }

    val tooltip = "updated while AWT waits"
    if (!tray.setTooltip(tooltip)) fail("setTooltip while AWT waits")
    val shown = (tray as AppKitTrayImpl).shownTooltip()
    if (shown != tooltip) fail("setTooltip while AWT waits left the button showing \"$shown\"")
    val second = Tray.create(builder()) ?: fail("Tray.create while AWT waits returned null")
    if (!mainThread.waitsInAwt()) fail("the main thread stopped waiting for the event dispatch thread")
    for (each in listOf(tray, second)) {
        val start = System.nanoTime()
        each.close()
        val tookMs = (System.nanoTime() - start) / 1_000_000
        if (tookMs >= 2_000) fail("close() while AWT waits fell back to the timeout ($tookMs ms)")
        if (!(each as AppKitTrayImpl).isTornDown) fail("close() while AWT waits left the status item up")
    }
    step("created, updated and closed while AWT waits for the event dispatch thread")
}

/** A blocked main thread must not make close() touch AppKit on the caller's thread, which ends the process. */
private fun checkCloseWhileMainIsBlocked(mainThread: CocoaMainThread) {
    val tray = Tray.create(builder()) as AppKitTrayImpl? ?: fail("Tray.create before the main thread blocked returned null")
    val lock = mainThread.newLockHeldHere()
    mainThread.perform(lock, "lock")
    Thread.sleep(300)

    val start = System.nanoTime()
    tray.close()
    val tookMs = (System.nanoTime() - start) / 1_000_000
    if (tookMs < 1_500) fail("close() took $tookMs ms, so the main thread was not blocked")
    if (tray.isTornDown) fail("close() tore the status item down while the main thread was blocked")

    mainThread.unlockHere(lock)
    mainThread.perform(lock, "unlock")
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!tray.isTornDown) {
        if (System.nanoTime() > deadline) fail("the main thread never tore the status item down after it was free again")
        Thread.sleep(10)
    }
    step("close() returned after $tookMs ms and the main thread tore the status item down later")
}

/** Puts the Cocoa main thread where the checks above need it. */
private class CocoaMainThread {
    private val bindings = ObjcBindings.load() ?: fail("ObjcBindings.load returned null")
    private val msgSendId = bindings.handle("objc_msgSend_id")
    private val msgSendVoid = bindings.handle("objc_msgSend_void")

    // void msgSend(id, SEL, SEL, id, BOOL): performSelectorOnMainThread:withObject:waitUntilDone:
    private val msgSendPerform = Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("libobjc.A.dylib", Arena.global()).find("objc_msgSend").orElseThrow(),
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN,
        ),
    )

    /** Send [selector] to [target] on the main thread, without waiting for it. */
    fun perform(target: MemorySegment, selector: String) {
        msgSendPerform.invokeExact(
            target, bindings.sel("performSelectorOnMainThread:withObject:waitUntilDone:"),
            bindings.sel(selector), MemorySegment.NULL, false,
        ) as Unit
    }

    /** The content view of the AWT window. */
    fun awtView(): MemorySegment {
        val app = msgSendId.invokeExact(bindings.cls("NSApplication"), bindings.sel("sharedApplication")) as MemorySegment
        val windows = msgSendId.invokeExact(app, bindings.sel("windows")) as MemorySegment
        val each = msgSendId.invokeExact(windows, bindings.sel("objectEnumerator")) as MemorySegment
        while (true) {
            val window = msgSendId.invokeExact(each, bindings.sel("nextObject")) as MemorySegment
            if (window.address() == 0L) fail("no window has an AWTView as its content view")
            val view = msgSendId.invokeExact(window, bindings.sel("contentView")) as MemorySegment
            if (view.address() == 0L) continue
            val name = msgSendId.invokeExact(view, bindings.sel("className")) as MemorySegment
            val utf8 = msgSendId.invokeExact(name, bindings.sel("UTF8String")) as MemorySegment
            if (utf8.reinterpret(Long.MAX_VALUE).getString(0) == "AWTView") return view
        }
    }

    /** Whether the main thread is inside AWT's wait for the event dispatch thread. */
    fun waitsInAwt(): Boolean = Thread.getAllStackTraces().any { (thread, frames) ->
        thread.name == "AppKit Thread" && frames.any { it.methodName.startsWith("doAWTRunLoop") }
    }

    /** A new NSLock this thread holds. */
    fun newLockHeldHere(): MemorySegment {
        val allocated = msgSendId.invokeExact(bindings.cls("NSLock"), bindings.sel("alloc")) as MemorySegment
        val lock = msgSendId.invokeExact(allocated, bindings.sel("init")) as MemorySegment
        msgSendVoid.invokeExact(lock, bindings.sel("lock")) as Unit
        return lock
    }

    fun unlockHere(lock: MemorySegment) {
        msgSendVoid.invokeExact(lock, bindings.sel("unlock")) as Unit
    }
}
