package dev.hivens.libtray

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Manual smoke for the tray backend. Run via `./gradlew runSmoke` —
 * shows a 32x32 tray icon, prints click events to stdout. Ctrl-C to exit.
 *
 * Used by the maintainer to eyeball each platform's backend before we
 * have CI infrastructure that can probe the OS's tray host. Not a unit
 * test — it's interactive.
 *
 * **Validation checklist** (use this as a script when you bring up a
 * fresh platform or sanity-check after a backend change):
 *
 *   1. Run `./gradlew runSmoke` (or `gradlew.bat runSmoke` on Windows).
 *      Stdout should print "tray live" within a couple of seconds.
 *   2. Look at your system tray / notification area. A purple circle
 *      icon should appear with a black "i"-shaped glyph.
 *   3. Hover the icon. Tooltip should read "libtray smoke test".
 *   4. Left-click the icon. Stdout should print
 *      `[smoke] event: Activated (left click)`.
 *   5. Right-click the icon. A two-item menu should appear:
 *      "Click me (noop)" + "Exit", separated by a horizontal line.
 *   6. Click "Click me (noop)". Stdout should print
 *      `[smoke] event: MenuItemSelected id=noop`.
 *   7. Right-click again, click "Exit". Stdout should print
 *      `id=exit` and the program should terminate cleanly.
 *
 * Per-OS prerequisites covered in the failure message below.
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
        val os = System.getProperty("os.name", "").lowercase()
        val hint = when {
            os.contains("linux") || os.contains("bsd") ->
                "Linux: libdbus + session bus + StatusNotifierWatcher must all be reachable. " +
                "Check DISPLAY / WAYLAND_DISPLAY and DBUS_SESSION_BUS_ADDRESS are set, and that " +
                "some component provides the SNI watcher (KDE plasmashell, GNOME's SNI extension, " +
                "waybar's tray module on Hyprland)."
            os.contains("windows") ->
                "Windows: kernel32/user32/shell32/gdi32 must load (they always do on Windows; null " +
                "here means a Panama upcall stub or RegisterClassExW failed — check the JVM has " +
                "--enable-native-access=ALL-UNNAMED and is JDK 22+)."
            os.contains("mac") || os.contains("darwin") ->
                "macOS: NSStatusItem backend not yet implemented (Phase 4)."
            else -> "Unrecognised OS: $os. libtray supports Linux, Windows, macOS."
        }
        System.err.println("Tray.create returned null. $hint")
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
