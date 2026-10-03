package dev.hivens.libtray.linux

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.solidPng
import dev.hivens.libtray.linux.DBusTestClient.Arg
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The Linux backend driven end to end as a plain program, so it can run as a
 * GraalVM native image as well as on the JVM: no JUnit, no assertion
 * library. It needs a session bus nobody else uses, which the
 * `linuxNativeCheck` task provides.
 *
 * Covers what a native image is most likely to break: loading libdbus and
 * binding every downcall, building the icon pixmap through ImageIO, the
 * watcher registration, host clicks and a menu click reaching the listener,
 * and close(). Exit code 0 means every step passed.
 */
fun main() {
    val name = "org.libtray.NativeCheck.Item"
    val watcher = DBusTestClient.Watcher()
    val host = DBusTestClient()
    val events = LinkedBlockingQueue<TrayEvent>()

    val tray = Tray.create(
        TrayBuilder(
            title = "libtray native check",
            iconBytes = solidPng(32),
            menu = TrayMenu(TrayMenuItem.Standard("show", "Show"), TrayMenuItem.Standard("exit", "Exit")),
            linuxBusName = name,
        ),
    ) ?: fail("Tray.create returned null")
    tray.onEvent { events.add(it) }
    step("created")

    if (watcher.registrations.poll(3, TimeUnit.SECONDS) != name) fail("item did not register with the watcher")
    step("registered with the watcher")

    if (host.getStringProperty(name, "Title") != "libtray native check") fail("Title property")
    if (host.firstIconPixmapSize(name) != (32 to 32)) fail("IconPixmap property")
    step("properties read")

    host.callNoResult(name, DBusTestClient.ITEM_PATH, DBusTestClient.ITEM_IFACE, "Activate", listOf(Arg.Int32(0), Arg.Int32(0)))
    expect(events, TrayEvent.Activated)
    host.callNoResult(name, DBusTestClient.MENU_PATH, "com.canonical.dbusmenu", "Event",
        listOf(Arg.Int32(2), Arg.Str("clicked"), Arg.VariantInt32(0), Arg.UInt32(0)))
    expect(events, TrayEvent.MenuItemSelected("exit"))

    tray.close()
    if (tray.isOpen) fail("still open after close()")
    step("closed")

    host.close()
    watcher.close()
    println("[native-check] PASS")
    exitProcess(0)
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
