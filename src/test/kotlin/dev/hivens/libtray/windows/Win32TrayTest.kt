package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.solidPng
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
            tray.setIcon(solidPng(16)) shouldBe true
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

    @Test
    fun `close destroys the window and keeps an interrupt flag`() {
        val tray = open()
        val hwnd = (tray as Win32TrayImpl).windowHandle

        Thread.currentThread().interrupt()
        tray.close()
        Thread.interrupted() shouldBe true

        val bindings = Win32Bindings.load() ?: error("user32 not loadable")
        try {
            val posted = bindings.handle("PostMessageW")
                .invokeExact(hwnd, Win32Bindings.WM_TRAY_CALLBACK, 0L, 0L) as Int
            posted shouldBe 0
        } finally {
            bindings.arena.close()
        }
    }

    @Test
    fun `setters from several threads and a racing close never throw`() {
        repeat(5) {
            val tray = open()
            val failure = AtomicReference<Throwable?>()
            // One latch per setter: a shared one could be run down by a single
            // fast setter before the other one had made a call at all.
            val tooltipStarted = CountDownLatch(1)
            val iconStarted = CountDownLatch(1)
            val setters = listOf(
                thread { hammer(tray, failure, tooltipStarted) { tray.setTooltip("racing") } },
                thread { hammer(tray, failure, iconStarted) { tray.setIcon(solidPng(16)) } },
            )
            try {
                // close() has to land while both setters are looping, not before they start.
                tooltipStarted.await(5, TimeUnit.SECONDS) shouldBe true
                iconStarted.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                // Also when the wait failed, so the icon and the setters do not
                // outlive this test.
                tray.close()
            }
            setters.forEach { it.join(5_000) }
            setters.none { it.isAlive } shouldBe true
            failure.get() shouldBe null
        }
    }

    private fun hammer(tray: Tray, failure: AtomicReference<Throwable?>, started: CountDownLatch, call: () -> Unit) {
        try {
            while (tray.isOpen) {
                call()
                started.countDown()
            }
        } catch (t: Throwable) {
            failure.set(t)
        }
    }

    private fun open(): Tray = Tray.create(
        TrayBuilder(
            title = "libtray-win32-test",
            iconBytes = solidPng(32),
            tooltip = "libtray test",
            menu = TrayMenu(TrayMenuItem.Standard("show", "Show"), TrayMenuItem.Standard("exit", "Exit")),
        ),
    ) ?: error("Tray.create returned null: Shell_NotifyIcon is not available in this session")

    private fun post(tray: Tray, mouseEvent: Int) {
        postShellCallback(tray, mouseEvent) shouldBe true
    }
}
