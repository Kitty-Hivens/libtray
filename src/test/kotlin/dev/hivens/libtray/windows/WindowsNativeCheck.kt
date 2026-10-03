package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.solidPng
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The Win32 backend driven as a plain program, so it can run as a GraalVM
 * native image as well as on the JVM. Same steps as [Win32TrayTest]: create
 * and update a notification area icon, post the tray window the shell's
 * version 4 callbacks, and close. Exit code 0 means every step passed.
 */
fun main() {
    val events = LinkedBlockingQueue<TrayEvent>()
    val tray = Tray.create(
        TrayBuilder(
            title = "libtray-win32-native-check",
            iconBytes = solidPng(32),
            tooltip = "libtray check",
            menu = TrayMenu(TrayMenuItem.Standard("show", "Show"), TrayMenuItem.Standard("exit", "Exit")),
        ),
    ) ?: fail("Tray.create returned null")
    tray.onEvent { events.add(it) }
    step("created")

    if (!tray.setTooltip("updated") || !tray.setIcon(solidPng(16))) fail("update")
    // Past maxIconSize: decoded, drawn smaller through Java2D and re-encoded.
    if (!tray.setIcon(solidPng(300))) fail("setIcon with an icon that needs scaling")
    step("updated")

    post(tray, Win32Bindings.WM_LBUTTONUP)
    expect(events, TrayEvent.Activated)
    post(tray, Win32Bindings.WM_MBUTTONUP)
    expect(events, TrayEvent.MiddleActivated)
    post(tray, Win32Bindings.WM_CONTEXTMENU)
    expect(events, TrayEvent.MenuRequested)

    val start = System.nanoTime()
    tray.close()
    val tookMs = (System.nanoTime() - start) / 1_000_000
    if (tray.isOpen || tookMs >= 2_000) fail("close() took $tookMs ms")
    step("closed in $tookMs ms")
    println("[native-check] PASS")
    exitProcess(0)
}

/** A shell callback in the NOTIFYICON_VERSION_4 layout: event in LOWORD(lParam), icon id in HIWORD. */
private fun post(tray: Tray, mouseEvent: Int) {
    val hwnd = (tray as Win32TrayImpl).windowHandle
    val bindings = Win32Bindings.load() ?: fail("user32 not loadable")
    try {
        val lParam = (mouseEvent.toLong() and 0xffff) or (Win32Bindings.TRAY_ICON_UID.toLong() shl 16)
        val ok = bindings.handle("PostMessageW").invokeExact(hwnd, Win32Bindings.WM_TRAY_CALLBACK, 0L, lParam) as Int
        if (ok == 0) fail("PostMessageW failed")
    } finally {
        bindings.arena.close()
    }
}

private fun expect(events: LinkedBlockingQueue<TrayEvent>, expected: TrayEvent) {
    val got = events.poll(3, TimeUnit.SECONDS)
    if (got != expected) fail("expected $expected, got $got")
    step("$expected reached the listener")
}

private fun step(what: String) = println("[native-check] $what")

private fun fail(why: String): Nothing {
    System.err.println("[native-check] FAIL: $why")
    exitProcess(1)
}
