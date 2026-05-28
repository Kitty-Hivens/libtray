package dev.hivens.libtray

import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Group
import javafx.scene.Scene
import javafx.stage.Stage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * JavaFX repro harness for issue #5 (macOS tray crash inside a JavaFX
 * host). Run via `./gradlew runJavaFxSmoke`. macOS-only in intent; on
 * Linux/Windows it just brings up a JavaFX window + a tray and exits
 * cleanly, which is still a useful "does libtray coexist with JavaFX"
 * check.
 *
 * Why this reproduces #5 where [SmokeMain] does not: SmokeMain owns the
 * NSApplication bootstrap itself and runs its own `[NSApp run]`, so at
 * Tray.create time `[NSApp isRunning]` is false. A JavaFX app is the
 * opposite: by the time [start] runs, Glass has already created
 * NSApplication and is driving the Cocoa run loop (the CVDisplayLink
 * pulse timer that crashed in the upstream report). Creating the tray
 * from the FX Application Thread here lands in exactly that state.
 *
 * Expected outcome:
 *   * Unpatched libtray re-runs `finishLaunching` + `setActivationPolicy:`
 *     on the Glass-owned NSApp and the JVM dies with a SIGSEGV in
 *     `objc_msgSend` (via MacTimer/CVDisplayLink) shortly after startup.
 *   * Patched libtray (guarded on `[NSApp isRunning]`) leaves NSApp alone;
 *     the window stays up, the tray appears, no crash. Pick "Exit" from
 *     the tray menu (or close the window) to quit.
 */
class JavaFxSmokeApp : Application() {

    override fun start(stage: Stage) {
        stage.title = "libtray javafx repro"
        stage.scene = Scene(Group(), 360.0, 160.0)
        stage.show()
        println("[fx-repro] JavaFX stage shown; Glass toolkit up + run loop live.")
        println("[fx-repro] creating libtray tray on the FX Application Thread...")

        val tray = Tray.create(
            TrayBuilder(
                title = "libtray-fx-repro",
                iconBytes = buildIcon(),
                tooltip = "libtray javafx repro",
                menu = TrayMenu(
                    listOf(
                        TrayMenuItem.Standard(id = "noop", label = "Click me (noop)"),
                        TrayMenuItem.Separator,
                        TrayMenuItem.Standard(id = "exit", label = "Exit"),
                    ),
                ),
            ),
        )

        if (tray == null) {
            println("[fx-repro] Tray.create returned null (no backend reachable, or main-thread guard tripped)")
            return
        }

        println("[fx-repro] tray live: reading this line without a crash means #5 is fixed.")
        tray.onEvent { event ->
            if (event is TrayEvent.MenuItemSelected) {
                println("[fx-repro] menu: ${event.id}")
                if (event.id == "exit") {
                    runCatching { tray.close() }
                    Platform.exit()
                }
            }
        }

        // Closing the window also quits, so the harness never hangs a CI
        // shell that has no one to click the tray.
        stage.setOnCloseRequest {
            runCatching { tray.close() }
            Platform.exit()
        }
    }

    private fun buildIcon(): ByteArray {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color(0xBB, 0x86, 0xFC)
            g.fillOval(2, 2, 28, 28)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "PNG", it) }.toByteArray()
    }
}

fun main() {
    Application.launch(JavaFxSmokeApp::class.java)
}
