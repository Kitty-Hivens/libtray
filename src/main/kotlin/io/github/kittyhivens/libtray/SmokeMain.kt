package io.github.kittyhivens.libtray

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Manual smoke for the tray backend. Run via `./gradlew runSmoke` —
 * shows a 32x32 tray icon, prints click events to stdout. Ctrl-C to exit.
 *
 * Used by the maintainer to eyeball the Linux backend on Hyprland / KDE
 * before we have CI infrastructure that can probe the desktop's tray
 * host. Not a unit test — it's interactive.
 */
fun main() {
    println("libtray smoke — building 32x32 PNG icon in memory...")
    val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        try {
            // Solid magenta circle on transparent background — easy to
            // spot in any tray theme.
            g.color = Color(0xBB, 0x86, 0xFC)  // matches Aura's primary
            g.fillOval(2, 2, 28, 28)
            g.color = Color.BLACK
            g.fillRect(14, 12, 4, 8)
            g.fillRect(14, 22, 4, 4)
        } finally {
            g.dispose()
        }
    }
    val pngBytes = ByteArrayOutputStream().also { ImageIO.write(img, "PNG", it) }.toByteArray()

    println("libtray smoke — opening tray as 'libtray-smoke'...")
    val tray = Tray.create(
        TrayBuilder(
            title = "libtray-smoke",
            iconBytes = pngBytes,
            tooltip = "libtray smoke test",
            menu = TrayMenu(listOf(
                TrayMenuItem.Standard(id = "noop", label = "Click me (noop)"),
                TrayMenuItem.Separator,
                TrayMenuItem.Standard(id = "exit", label = "Exit"),
            )),
        ),
    ) ?: run {
        System.err.println("Tray.create returned null. " +
            "On Linux this means libdbus + session bus + StatusNotifierWatcher must all be reachable. " +
            "Check that you are running inside a desktop session (DISPLAY / WAYLAND_DISPLAY set, " +
            "DBUS_SESSION_BUS_ADDRESS set), and that some component provides the SNI watcher " +
            "(KDE plasmashell, GNOME's StatusNotifierItem extension, waybar's tray module on Hyprland).")
        kotlin.system.exitProcess(1)
    }

    val exitFlag = java.util.concurrent.CountDownLatch(1)

    tray.onEvent { event ->
        // SNI dispatch happens on the libtray pump thread — printing is
        // safe; if you're going to touch UI, hop yourself.
        when (event) {
            is TrayEvent.Activated         -> println("[smoke] event: Activated (left click)")
            is TrayEvent.MiddleActivated   -> println("[smoke] event: MiddleActivated")
            is TrayEvent.MenuRequested     -> println("[smoke] event: MenuRequested (right click — host should show DBusMenu, not implemented yet)")
            is TrayEvent.MenuItemSelected  -> {
                println("[smoke] event: MenuItemSelected id=${event.id}")
                if (event.id == "exit") exitFlag.countDown()
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("[smoke] shutdown hook → tray.close()")
        runCatching { tray.close() }
    })

    println("[smoke] tray live. Click / right-click / Ctrl-C to test. Tray will stay until SIGINT.")
    exitFlag.await()  // Ctrl-C handler in the JDK delivers SIGINT → JVM exit → shutdown hook
    tray.close()
}
