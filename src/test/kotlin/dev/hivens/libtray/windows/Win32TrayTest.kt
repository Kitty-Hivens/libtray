package dev.hivens.libtray.windows

import dev.hivens.libtray.ICON_FIXTURES
import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.hex
import dev.hivens.libtray.samplePoints
import dev.hivens.libtray.solidPng
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
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
            tray.setIcon(ByteArray(0)) shouldBe false
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
            // tracks is cancelled by close() below, which this only proves once
            // the pump thread is inside TrackPopupMenu.
            post(tray, Win32Bindings.WM_CONTEXTMENU)
            events.poll(2, TimeUnit.SECONDS) shouldBe TrayEvent.MenuRequested
            val hwnd = (tray as Win32TrayImpl).windowHandle
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!PopupMenuMode.isTracking(hwnd) && System.nanoTime() < deadline) Thread.sleep(20)
            PopupMenuMode.isTracking(hwnd) shouldBe true
        } finally {
            val start = System.nanoTime()
            tray.close()
            ((System.nanoTime() - start) / 1_000_000 < 2_000) shouldBe true
        }
    }

    @Test
    fun `the icon comes back when the taskbar is recreated`() {
        val tray = open()
        val hwnd = (tray as Win32TrayImpl).windowHandle
        val bindings = Win32Bindings.load() ?: error("user32 not loadable")
        try {
            tray.setTooltip("before") shouldBe true
            // What an Explorer crash leaves behind: the shell no longer knows
            // the icon, and NIM_MODIFY fails until it is added again.
            deleteIcon(bindings, hwnd) shouldBe true
            tray.setTooltip("gone") shouldBe false

            // A restarted Explorer broadcasts TaskbarCreated to every top-level
            // window. Other tray icons in this session get it too and add
            // themselves again, which is what they do after a real restart.
            val taskbarCreated = Arena.ofConfined().use { tmp ->
                bindings.handle("RegisterWindowMessageW").invokeExact(tmp.allocateFrom("TaskbarCreated", StandardCharsets.UTF_16LE)) as Int
            }
            (taskbarCreated != 0) shouldBe true
            val posted = bindings.handle("PostMessageW")
                .invokeExact(Win32Bindings.HWND_BROADCAST, taskbarCreated, 0L, 0L) as Int
            (posted != 0) shouldBe true

            val deadline = System.nanoTime() + 2_000_000_000L
            var back = false
            while (!back && System.nanoTime() < deadline) {
                back = tray.setTooltip("back")
                if (!back) Thread.sleep(50)
            }
            back shouldBe true
        } finally {
            bindings.arena.close()
            tray.close()
        }
    }

    @Test
    fun `a WM_CLOSE from another process leaves the tray working`() {
        val tray = open()
        val events = LinkedBlockingQueue<TrayEvent>()
        tray.onEvent { events.add(it) }
        val bindings = Win32Bindings.load() ?: error("user32 not loadable")
        try {
            // The tray window is top-level, so anything that closes a process's
            // windows politely, such as taskkill without /F, reaches it too.
            val posted = bindings.handle("PostMessageW")
                .invokeExact((tray as Win32TrayImpl).windowHandle, Win32Bindings.WM_CLOSE, 0L, 0L) as Int
            (posted != 0) shouldBe true
            Thread.sleep(300)

            tray.isOpen shouldBe true
            post(tray, Win32Bindings.WM_LBUTTONUP)
            events.poll(2, TimeUnit.SECONDS) shouldBe TrayEvent.Activated
        } finally {
            bindings.arena.close()
            tray.close()
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

    /** NIM_DELETE for the tray's icon, from outside the tray, as the shell forgetting it. */
    private fun deleteIcon(bindings: Win32Bindings, hwnd: MemorySegment): Boolean = Arena.ofConfined().use { tmp ->
        val layout = bindings.notifyIconDataLayout
        val data = tmp.allocate(layout)
        fun off(name: String) = layout.byteOffset(MemoryLayout.PathElement.groupElement(name))
        data.set(ValueLayout.JAVA_INT, off("cbSize"), layout.byteSize().toInt())
        data.set(ValueLayout.ADDRESS, off("hWnd"), hwnd)
        data.set(ValueLayout.JAVA_INT, off("uID"), Win32Bindings.TRAY_ICON_UID)
        val state = tmp.allocate(Win32Bindings.CALL_STATE_LAYOUT)
        (bindings.handle("Shell_NotifyIconW").invokeExact(state, Win32Bindings.NIM_DELETE, data) as Int) != 0
    }

    @Test
    fun `a failed call reports its own last error`() {
        // cbSize 0 is the documented way to make RegisterClassExW fail with
        // ERROR_INVALID_PARAMETER. Read from the captured state, the code is
        // the one the call left, whatever the JVM did on the thread afterwards.
        val bindings = Win32Bindings.load() ?: error("user32 not loadable")
        try {
            Arena.ofConfined().use { tmp ->
                val wc = tmp.allocate(bindings.wndClassExWLayout)
                val state = tmp.allocate(Win32Bindings.CALL_STATE_LAYOUT)
                val atom = bindings.handle("RegisterClassExW").invokeExact(state, wc) as Short
                atom.toInt() shouldBe 0
                Win32Bindings.lastError(state) shouldBe ERROR_INVALID_PARAMETER
            }
        } finally {
            bindings.arena.close()
        }
    }

    @Test
    fun `every test icon reaches the shell the right way up`() {
        // Read back from the HICON the shell got, the step where icons once
        // came out upside down: CreateIcon takes rows top-down, unlike a DIB.
        // RGB only, as CreateIcon is free to treat alpha its own way.
        val tray = open() as Win32TrayImpl
        try {
            val problems = ICON_FIXTURES.mapNotNull { icon ->
                if (!tray.setIcon(icon.bytes())) return@mapNotNull "${icon.name} was refused"
                val handle = tray.currentIconHandle ?: return@mapNotNull "${icon.name} left no icon"
                val size = icon.shownSize
                val pixels = IconReadback.pixels(handle, size)
                val shown = samplePoints(size, size).map { (x, y) -> pixels[y * size + x] and 0xFFFFFF }
                val expected = listOf(icon.corner, icon.opposite).map { it and 0xFFFFFF }
                if (shown == expected) null else "${icon.name} is held as ${hex(shown)}, expected ${hex(expected)}"
            }
            problems shouldBe emptyList()
        } finally {
            tray.close()
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

    private companion object {
        const val ERROR_INVALID_PARAMETER = 87
    }
}

/** The two user32 calls that tell whether a window's thread is tracking a popup menu it owns. */
private object PopupMenuMode {
    private val arena = Arena.ofShared()
    private val user32 = SymbolLookup.libraryLookup("user32", arena)
    private val linker = Linker.nativeLinker()

    // DWORD GetWindowThreadProcessId(HWND, LPDWORD)
    private val windowThread = linker.downcallHandle(
        user32.find("GetWindowThreadProcessId").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )

    // BOOL GetGUIThreadInfo(DWORD, PGUITHREADINFO)
    private val guiThreadInfo = linker.downcallHandle(
        user32.find("GetGUIThreadInfo").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

    // GUITHREADINFO on x86_64: cbSize, flags, six HWNDs, then a RECT.
    private const val GUI_THREAD_INFO_SIZE = 72
    private const val FLAGS_OFFSET = 4L
    private const val MENU_OWNER_OFFSET = 32L
    private const val GUI_POPUPMENUMODE = 0x10

    fun isTracking(hwnd: MemorySegment): Boolean = Arena.ofConfined().use { tmp ->
        val thread = windowThread.invokeExact(hwnd, MemorySegment.NULL) as Int
        if (thread == 0) return false
        val info = tmp.allocate(GUI_THREAD_INFO_SIZE.toLong(), 8)
        info.set(ValueLayout.JAVA_INT, 0, GUI_THREAD_INFO_SIZE)
        if ((guiThreadInfo.invokeExact(thread, info) as Int) == 0) return false
        val flags = info.get(ValueLayout.JAVA_INT, FLAGS_OFFSET)
        val menuOwner = info.get(ValueLayout.ADDRESS, MENU_OWNER_OFFSET)
        (flags and GUI_POPUPMENUMODE) != 0 && menuOwner.address() == hwnd.address()
    }
}
