package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.expect
import dev.hivens.libtray.fail
import dev.hivens.libtray.solidPng
import dev.hivens.libtray.startWatchdog
import dev.hivens.libtray.step
import java.util.concurrent.LinkedBlockingQueue
import kotlin.system.exitProcess

/**
 * The Win32 backend driven as a plain program, so it can run as a GraalVM
 * native image as well as on the JVM. Same steps as [Win32TrayTest]: create
 * and update a notification area icon, post the tray window the shell's
 * version 4 callbacks, and close. Exit code 0 means every step passed.
 */
fun main() {
    startWatchdog()
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
    // Past maxIconSize: decoded, averaged down and re-encoded. The backend
    // rejects anything over 256, so this fails if scaling did not happen.
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
    step("PASS")
    exitProcess(0)
}

private fun post(tray: Tray, mouseEvent: Int) {
    if (!postShellCallback(tray, mouseEvent)) fail("PostMessageW failed")
}
