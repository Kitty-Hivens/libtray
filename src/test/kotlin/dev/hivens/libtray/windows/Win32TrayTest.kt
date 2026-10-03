package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The Win32 backend on a real Windows session. Clicks are simulated by
 * posting the tray window the same callback messages the shell sends, so
 * no input injection is needed.
 */
@EnabledOnOs(OS.WINDOWS)
class Win32TrayTest {

    @Test
    fun `creates, updates and closes a notification area icon`() {
        val tray = open()
        try {
            tray.setTooltip("updated") shouldBe true
            tray.setIcon(icon(16)) shouldBe true
            tray.setMenu(TrayMenu(TrayMenuItem.Standard("only", "Only"))) shouldBe true
            tray.setMenu(null) shouldBe true
        } finally {
            tray.close()
        }
        tray.isOpen shouldBe false
    }

    @Test
    fun `shell callbacks reach the listener as events`() {
        val tray = open()
        val events = LinkedBlockingQueue<TrayEvent>()
        tray.onEvent { events.add(it) }
        try {
            post(tray, Win32Bindings.WM_LBUTTONUP)
            events.poll(2, TimeUnit.SECONDS) shouldBe TrayEvent.Activated
            post(tray, Win32Bindings.WM_MBUTTONUP)
            events.poll(2, TimeUnit.SECONDS) shouldBe TrayEvent.MiddleActivated
            // A version 4 shell opens the menu on WM_CONTEXTMENU. The popup it
            // tracks is cancelled by close() below.
            post(tray, Win32Bindings.WM_CONTEXTMENU)
            events.poll(2, TimeUnit.SECONDS) shouldBe TrayEvent.MenuRequested
        } finally {
            val start = System.nanoTime()
            tray.close()
            ((System.nanoTime() - start) / 1_000_000 < 2_000) shouldBe true
        }
    }

    private fun open(): Tray = Tray.create(
        TrayBuilder(
            title = "libtray-win32-test",
            iconBytes = icon(32),
            tooltip = "libtray test",
            menu = TrayMenu(TrayMenuItem.Standard("show", "Show"), TrayMenuItem.Standard("exit", "Exit")),
        ),
    ) ?: error("Tray.create returned null: Shell_NotifyIcon is not available in this session")

    /** Post the tray window a callback in the NOTIFYICON_VERSION_4 layout: event in LOWORD(lParam), icon id in HIWORD. */
    private fun post(tray: Tray, mouseEvent: Int) {
        val hwnd = (tray as Win32TrayImpl).windowHandle
        val bindings = Win32Bindings.load() ?: error("user32 not loadable")
        try {
            val lParam = (mouseEvent.toLong() and 0xffff) or (Win32Bindings.TRAY_ICON_UID.toLong() shl 16)
            val ok = bindings.handle("PostMessageW")
                .invokeExact(hwnd, Win32Bindings.WM_TRAY_CALLBACK, 0L, lParam) as Int
            (ok != 0) shouldBe true
        } finally {
            bindings.arena.close()
        }
    }

    private fun icon(size: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
    }
}
