package dev.hivens.libtray.windows

import dev.hivens.libtray.EventDispatcher
import dev.hivens.libtray.IconScaling
import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayEventListener
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.TraySubscription
import dev.hivens.libtray.UpcallTarget
import dev.hivens.libtray.awaitPastInterrupt
import dev.hivens.libtray.joinPastInterrupt
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Windows tray backend on top of `Shell_NotifyIcon` + a hidden
 * `HWND_MESSAGE` window. Project Panama bindings only — no JNA, no
 * COM, no GDI+ — and one Panama upcall stub for the WndProc.
 *
 * Structural mirror of [dev.hivens.libtray.linux.SniTrayImpl]:
 *
 *   1. Bind [Win32Bindings] (kernel32 / user32 / shell32 / gdi32).
 *   2. Register a unique window class — name suffixed with PID + counter
 *      so multiple Tray instances in the same process don't collide on
 *      `RegisterClassExW`.
 *   3. Create an `HWND_MESSAGE` window. Message-only window: no screen
 *      presence, no Z-order, but receives `WM_USER+1` dispatches from
 *      Shell_NotifyIcon all the same. Costs nothing visually.
 *   4. Spin a daemon pump thread that drains the window's message queue
 *      via `GetMessageW` + `DispatchMessageW`.
 *   5. WndProc routes `WM_DESTROY` / `WM_USER+1` / `WM_COMMAND` to
 *      Kotlin-side handlers; everything else gets `DefWindowProcW`.
 *   6. `Shell_NotifyIcon` NIM_ADD registers the icon (built from the
 *      builder's PNG via [iconPixels] and [createHicon]), NIM_SETVERSION opts into the
 *      v4 mouse-message format, NIM_MODIFY carries later icon / tooltip
 *      changes, and NIM_DELETE removes the entry on [close].
 *
 * Menu: no HMENU is held across calls. [setMenu] only stores the
 * [TrayMenu]; the popup is built, tracked and destroyed inside
 * [showContextMenu] on each right-click, which is what keeps repeated
 * setMenu calls from leaking GDI handles.
 */
internal class Win32TrayImpl private constructor(
    private val bindings: Win32Bindings,
    private val classNameSeg: MemorySegment,
    private val hInstance: MemorySegment,
    private val initial: TrayBuilder,
) : Tray {

    private val log = LoggerFactory.getLogger("libtray.Win32Tray")

    private val open = AtomicBoolean(true)
    private val events = EventDispatcher("libtray-events-${ProcessHandle.current().pid()}")

    /**
     * The HWND for the message-only window. Set by the pump thread once
     * `CreateWindowExW` returns. Read by the main thread after the
     * [readyLatch] fires; written and read on the pump thread thereafter.
     *
     * Must be created on the pump thread because Win32 messages
     * (including the WM_USER+1 callbacks Shell_NotifyIcon dispatches for
     * mouse events) are posted to the *creating thread's* message queue.
     * If we created the window on the main thread but pumped on a
     * separate daemon, mouse callbacks would queue up on main forever
     * and the pump would see nothing — exactly the "icon visible, no
     * events" symptom seen on the first Windows test.
     */
    @Volatile private var hwnd: MemorySegment = MemorySegment.NULL

    /** The message window, for tests that post it the callbacks the shell would. */
    internal val windowHandle: MemorySegment get() = hwnd

    /**
     * Signals "pump thread has either succeeded in creating the window
     * + registering the icon, or failed and is exiting". `await`'d in
     * [Companion.create] so the public `Tray.create` doesn't return a
     * Tray that's still mid-init.
     */
    private val readyLatch = CountDownLatch(1)

    /** Set true by the pump thread after a successful NIM_ADD. */
    @Volatile private var creationSucceeded: Boolean = false

    /** Whether NIM_SETVERSION took, which decides how a right click arrives. Pump thread only. */
    private var version4: Boolean = false

    /**
     * Reusable NOTIFYICONDATAW struct. Allocated once on the bindings
     * arena, mutated for each NIM_ADD / NIM_MODIFY / NIM_DELETE call.
     * The shell takes a snapshot of the struct contents during each
     * Shell_NotifyIcon call, so reuse is safe.
     */
    private val notifyIconData: MemorySegment = bindings.arena.allocate(bindings.notifyIconDataLayout)

    /**
     * Guards [notifyIconData], [iconHandle] and the closing of the arena. The
     * setters may run on any thread and check [open] under it, and close()
     * clears [open] before taking it, so a setter holding it can rely on the
     * arena being open.
     */
    private val shellLock = Any()

    /**
     * Currently-installed HICON. Replaced atomically when [setIcon]
     * fires; the prior HICON gets DestroyIcon'd on success so we don't
     * leak GDI handles. Initially holds the icon built from the
     * builder's iconBytes.
     */
    private val iconHandle = AtomicReference<MemorySegment?>(null)

    /** Tracks the latest tooltip string for re-applying after setIcon. */
    @Volatile private var tooltip: String = initial.tooltip ?: ""

    /**
     * Latest menu set via [setMenu], or null if no menu attached. Stored
     * by reference rather than rebuilt-into-HMENU on each set: Win32
     * popup menus are short-lived (built on right-click, destroyed when
     * dismissed), so `setMenu` is just a state update — the HMENU is
     * constructed lazily inside [showContextMenu] each time the user
     * right-clicks the icon. Avoids the GDI handle leak that comes from
     * holding HMENUs across multiple setMenu calls.
     */
    @Volatile private var currentMenu: TrayMenu? = initial.menu

    /** Background pump thread running the `GetMessageW` + `DispatchMessageW` loop. */
    private val pumpThread = Thread({ pumpLoop() }, "libtray-win32-${ProcessHandle.current().pid()}").apply {
        isDaemon = true
    }

    init {
        // Window creation, instance registration, Shell_NotifyIcon
        // NIM_ADD, and the pump loop ALL run on pumpThread — see the
        // comment on [hwnd] for why. Main thread blocks on [readyLatch]
        // inside Companion.create until the pump signals success/failure.
        pumpThread.start()
    }

    override val isOpen: Boolean get() = open.get()

    override fun setTooltip(text: String): Boolean = synchronized(shellLock) {
        if (!open.get()) return false
        tooltip = text
        writeWideStringField(notifyIconData, "szTip", 128, text)
        notifyIconDataFlags(Win32Bindings.NIF_MESSAGE or Win32Bindings.NIF_ICON or Win32Bindings.NIF_TIP or Win32Bindings.NIF_SHOWTIP)
        shellNotifyIcon(Win32Bindings.NIM_MODIFY)
    }

    override fun setIcon(iconBytes: ByteArray): Boolean {
        if (!open.get()) return false
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
        // Scaling, decoding and the pixel conversion are plain JVM work and stay
        // outside the lock. CreateIcon runs under it, so close() cannot free the
        // arena between creating the HICON and handing it to the shell or
        // destroying it.
        val pixels = iconPixels(IconScaling.fit(iconBytes, initial.maxIconSize, log)) ?: run {
            log.warn("The new icon could not be converted for CreateIcon, setIcon ignored")
            return false
        }
        return synchronized(shellLock) {
            if (!open.get()) return false
            val newIcon = createHicon(pixels) ?: return false
            // Destroy the old icon only after the shell has the new one, so
            // the struct never points at a freed HICON.
            val prev = iconHandle.getAndSet(newIcon)
            notifyIconData.set(
                ValueLayout.ADDRESS,
                bindings.notifyIconDataLayout.byteOffset(MemoryLayout.PathElement.groupElement("hIcon")),
                newIcon,
            )
            notifyIconDataFlags(Win32Bindings.NIF_MESSAGE or Win32Bindings.NIF_ICON or Win32Bindings.NIF_TIP or Win32Bindings.NIF_SHOWTIP)
            val ok = shellNotifyIcon(Win32Bindings.NIM_MODIFY)
            prev?.let(::destroyIcon)
            ok
        }
    }

    private fun destroyIcon(icon: MemorySegment) {
        if (icon.address() != 0L) runCatching { bindings.handle("DestroyIcon").invokeExact(icon) as Int }
    }

    override fun setMenu(menu: TrayMenu?): Boolean {
        if (!open.get()) return false
        currentMenu = menu
        return true
    }

    override fun onEvent(listener: TrayEventListener): TraySubscription = events.subscribe(listener)

    override fun onEvent(executor: Executor, listener: TrayEventListener): TraySubscription =
        events.subscribe(listener, executor)

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        events.close()
        // Remove the tray icon BEFORE tearing down the window — once the
        // HWND is destroyed the shell may still hold an entry pointed at
        // a stale handle, which can leave a ghost icon until the next
        // explorer.exe restart. NIM_DELETE first is the documented order.
        synchronized(shellLock) {
            runCatching { shellNotifyIcon(Win32Bindings.NIM_DELETE) }
            iconHandle.getAndSet(null)?.let(::destroyIcon)
        }
        // Post WM_CLOSE so the pump thread wakes up out of GetMessage
        // and exits cleanly. DefWindowProc translates WM_CLOSE -> DestroyWindow,
        // which fires WM_DESTROY on the same thread -> PostQuitMessage(0)
        // in the WndProc -> GetMessage returns 0 -> loop exits.
        // If a context menu is open, the pump thread is parked inside
        // TrackPopupMenu's own modal loop and will never see WM_CLOSE, so
        // the join below would time out for as long as the user
        // holds the menu. WM_CANCELMODE posted to the owner window is the
        // documented cross-thread way to cancel a tracked popup menu, so
        // TrackPopupMenu returns and the pump can then process WM_CLOSE.
        // (EndMenu would only end the CALLING thread's menu, not the pump's.)
        //
        // Only with a real window. PostMessageW treats a NULL hWnd as
        // PostThreadMessage to the CALLING thread -- so on the "window
        // creation failed, tear the half-built instance down" path this
        // would push WM_CANCELMODE and WM_CLOSE into the queue of whatever
        // thread called close(), which for a UI consumer is the EDT. The
        // pump has already exited in that case; there is nothing to signal.
        if (hwnd.address() != 0L) {
            runCatching {
                bindings.handle("PostMessageW").invokeExact(
                    hwnd, Win32Bindings.WM_CANCELMODE, 0L, 0L,
                ) as Int
            }
            runCatching {
                bindings.handle("PostMessageW").invokeExact(
                    hwnd, Win32Bindings.WM_CLOSE, 0L, 0L,
                ) as Int
            }
        }
        // Everything after the join frees what a live pump thread is still
        // using: its WndProc looks the instance up in HWND_INSTANCES, the class
        // cannot be unregistered while its window exists, and the arena cannot
        // close while GetMessageW holds the MSG buffer. Leak them instead.
        if (!pumpThread.joinPastInterrupt(PUMP_JOIN_TIMEOUT_MS)) {
            log.warn(
                "libtray-win32 did not stop within {} ms. Leaving its window class and native handles " +
                    "allocated, the icon is gone either way.",
                PUMP_JOIN_TIMEOUT_MS,
            )
            return
        }
        HWND_INSTANCES.remove(hwnd.address())
        // Class is per-instance; unregister so a fresh Tray.create can
        // re-register the same name without ERROR_CLASS_ALREADY_EXISTS.
        runCatching {
            bindings.handle("UnregisterClassW").invokeExact(classNameSeg, hInstance) as Int
        }
        // Release the bindings arena (downcall handles + the reusable
        // NOTIFYICONDATA struct). Safe now that the window is destroyed and the
        // pump thread joined, so no WndProc will dereference these handles
        // again. The process-wide WndProc upcall stub lives in its own arena
        // (ensureWndProcStub) and is deliberately left open. Under the lock, so
        // a setter that got past its own open check finishes first.
        synchronized(shellLock) { runCatching { bindings.arena.close() } }
    }

    // ── Shell_NotifyIcon helpers ─────────────────────────────────────────

    private fun shellNotifyIcon(message: Int): Boolean {
        return runCatching {
            (bindings.handle("Shell_NotifyIconW").invokeExact(message, notifyIconData) as Int) != 0
        }.getOrElse { t ->
            log.warn("Shell_NotifyIcon (msg={}) threw", message, t)
            false
        }
    }

    private fun lastError(): Int = runCatching {
        bindings.handle("GetLastError").invokeExact() as Int
    }.getOrDefault(0)

    private fun initNotifyIconData(initialIcon: MemorySegment?) {
        val data = notifyIconData
        val layout = bindings.notifyIconDataLayout
        // Zero everything first — the struct is reused, and stale bits
        // in szInfo / guidItem fields would confuse the shell.
        for (i in 0 until layout.byteSize()) {
            data.set(ValueLayout.JAVA_BYTE, i, 0.toByte())
        }
        data.set(ValueLayout.JAVA_INT, off("cbSize"), layout.byteSize().toInt())
        data.set(ValueLayout.ADDRESS, off("hWnd"), hwnd)
        data.set(ValueLayout.JAVA_INT, off("uID"), Win32Bindings.TRAY_ICON_UID)
        data.set(ValueLayout.JAVA_INT, off("uCallbackMessage"), Win32Bindings.WM_TRAY_CALLBACK)
        data.set(ValueLayout.ADDRESS, off("hIcon"), initialIcon ?: MemorySegment.NULL)
        data.set(ValueLayout.JAVA_INT, off("uTimeoutOrVersion"), Win32Bindings.NOTIFYICON_VERSION_4)
        writeWideStringField(data, "szTip", 128, tooltip)
        notifyIconDataFlags(Win32Bindings.NIF_MESSAGE or Win32Bindings.NIF_ICON or Win32Bindings.NIF_TIP or Win32Bindings.NIF_SHOWTIP)
    }

    /** Mutate just the uFlags field — every NIM_MODIFY needs the flags it wants applied. */
    private fun notifyIconDataFlags(flags: Int) {
        notifyIconData.set(ValueLayout.JAVA_INT, off("uFlags"), flags)
    }

    private fun off(name: String): Long =
        bindings.notifyIconDataLayout.byteOffset(MemoryLayout.PathElement.groupElement(name))

    /**
     * Write a wide-string into a fixed-size WCHAR array field, NUL-padded
     * to the field length. Truncates to (maxChars - 1) so there's room
     * for the terminator. Used for szTip and friends.
     */
    private fun writeWideStringField(data: MemorySegment, fieldName: String, maxChars: Int, value: String) {
        val fieldOffset = off(fieldName)
        // Zero the whole field first to wipe any prior longer value.
        for (i in 0 until maxChars) {
            data.set(ValueLayout.JAVA_SHORT, fieldOffset + i * 2L, 0.toShort())
        }
        val truncated = if (value.length >= maxChars) value.substring(0, maxChars - 1) else value
        for ((i, ch) in truncated.withIndex()) {
            data.set(ValueLayout.JAVA_SHORT, fieldOffset + i * 2L, ch.code.toShort())
        }
    }

    // ── PNG → HICON conversion ───────────────────────────────────────────
    //
    // Decode via ImageIO (zero-extra-deps; libtray's Linux backend uses the
    // same path), convert ARGB->BGRA, call CreateIcon. With a 32bpp icon +
    // a zero AND mask, modern Windows uses the alpha channel for
    // transparency.
    //
    // Row order is top-down: CreateIcon builds DDBs (it calls CreateBitmap
    // internally), and DDB scanlines run top-to-bottom. The bottom-up
    // default only applies to DIBs (CreateDIBitmap with positive height).
    // Feeding bottom-up rows here paints the glyph upside down (issue #4).

    /** An icon converted to the two bitmaps CreateIcon takes. */
    private class IconPixels(val width: Int, val height: Int, val xorBytes: ByteArray, val andBytes: ByteArray)

    /** Decode [bytes] and convert them for CreateIcon. JVM work only, no native call. */
    private fun iconPixels(bytes: ByteArray): IconPixels? {
        val img = runCatching { IconScaling.decode(bytes) }.getOrNull() ?: run {
            log.warn("ImageIO could not decode PNG ({} bytes)", bytes.size)
            return null
        }
        val w = img.width
        val h = img.height
        if (w <= 0 || h <= 0 || w > 256 || h > 256) {
            log.warn("PNG dimensions out of range: {}x{} (expected 1..256)", w, h)
            return null
        }
        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)

        // XOR bits = BGRA, top-down (see row-order note above).
        val xorBytes = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val srcRowStart = y * w
            val dstRowStart = y * w * 4
            for (x in 0 until w) {
                val px = argb[srcRowStart + x]
                val a = (px ushr 24) and 0xff
                val r = (px ushr 16) and 0xff
                val g = (px ushr 8) and 0xff
                val b = px and 0xff
                xorBytes[dstRowStart + x * 4    ] = b.toByte()
                xorBytes[dstRowStart + x * 4 + 1] = g.toByte()
                xorBytes[dstRowStart + x * 4 + 2] = r.toByte()
                xorBytes[dstRowStart + x * 4 + 3] = a.toByte()
            }
        }

        // AND mask: 1bpp, scanlines aligned to 4-byte boundary. All zeros
        // tells Windows "use alpha from XOR bits"; correct on Vista+.
        val maskScanline = ((w + 31) / 32) * 4
        val andBytes = ByteArray(maskScanline * h)
        return IconPixels(w, h, xorBytes, andBytes)
    }

    /** CreateIcon from converted pixels. Callers hold [shellLock], since this uses the arena. */
    private fun createHicon(pixels: IconPixels): MemorySegment? {
        // Confined arena for the bitmap bytes. CreateIcon copies them
        // into the icon resource synchronously, so we can free as soon
        // as the call returns. Without this they'd accumulate in
        // bindings.arena (shared, JVM-lifetime) on every setIcon call,
        // about 4KB per 32x32 update, which adds up on apps that animate
        // the tray glyph.
        val createIcon = bindings.handle("CreateIcon")
        val hicon = Arena.ofConfined().use { tmp ->
            val xorSeg = tmp.allocate(pixels.xorBytes.size.toLong())
            xorSeg.asByteBuffer().put(pixels.xorBytes)
            val andSeg = tmp.allocate(pixels.andBytes.size.toLong())
            andSeg.asByteBuffer().put(pixels.andBytes)
            createIcon.invokeExact(
                hInstance, pixels.width, pixels.height, 1.toByte(), 32.toByte(), andSeg, xorSeg,
            ) as MemorySegment
        }
        if (hicon.address() == 0L) {
            log.warn("CreateIcon returned NULL (GetLastError={})", lastError())
            return null
        }
        return hicon
    }

    // ── Background dispatch loop ─────────────────────────────────────────

    private fun pumpLoop() {
        // ── Phase 1: bring up window + tray icon (this thread!) ───────────
        // Done here rather than in the constructor because Win32 messages
        // (the WM_USER+1 callbacks Shell_NotifyIcon dispatches for mouse
        // events) queue up to the *creating* thread. We pump on this
        // thread, so we MUST create the window on this thread too.
        val createdHwnd = createMessageWindow()
        if (createdHwnd == null || createdHwnd.address() == 0L) {
            log.warn("CreateWindowExW failed on pump thread (GetLastError={})", lastError())
            readyLatch.countDown()
            return
        }
        hwnd = createdHwnd
        HWND_INSTANCES[createdHwnd.address()] = this
        log.info("Win32 message window up: hwnd=0x{}", createdHwnd.address().toString(16))

        val initialPixels = iconPixels(IconScaling.fit(initial.iconBytes, initial.maxIconSize, log))
        // Under the lock and only while open. Once a create() that gave up on a
        // slow startup has called close(), an icon added here would outlive the
        // tray close() just tore down, whichever of the two got the lock first.
        val nimAddOk: Boolean? = synchronized(shellLock) {
            if (!open.get()) return@synchronized null
            val initialIcon = initialPixels?.let(::createHicon)
            if (initialIcon == null) {
                log.warn("The initial icon could not be created, the tray entry will register without one")
            } else {
                iconHandle.set(initialIcon)
            }
            initNotifyIconData(initialIcon)
            val added = shellNotifyIcon(Win32Bindings.NIM_ADD)
            if (!added) {
                // Not a reason to stop the pump: the caller sees
                // creationSucceeded=false from Companion.create and decides
                // how to degrade.
                log.warn("Shell_NotifyIcon NIM_ADD failed (GetLastError={})", lastError())
            } else {
                log.info("Shell_NotifyIcon NIM_ADD succeeded (uID={})", Win32Bindings.TRAY_ICON_UID)
            }
            // NIM_SETVERSION must come AFTER NIM_ADD per MSDN. It switches
            // the icon entry to NOTIFYICON_VERSION_4 mouse-message format.
            version4 = shellNotifyIcon(Win32Bindings.NIM_SETVERSION)
            if (!version4) {
                log.info("Shell_NotifyIcon NIM_SETVERSION failed, legacy mouse messages will be used")
            }
            added
        }
        if (nimAddOk == null) {
            // close() may have run before the window existed and posted nothing
            // that would end this thread, so take the window down here. Anything
            // it did post goes away with the window.
            runCatching { bindings.handle("DestroyWindow").invokeExact(createdHwnd) as Int }
            HWND_INSTANCES.remove(createdHwnd.address())
            readyLatch.countDown()
            return
        }

        creationSucceeded = nimAddOk
        readyLatch.countDown()

        // ── Phase 2: drain message queue until WM_QUIT ────────────────────
        // GetMessage sleeps until there is a message, so an idle tray costs no
        // wakeups. close() ends the loop by destroying the window, whose
        // WM_DESTROY posts the WM_QUIT that makes GetMessage return 0.
        val msg = bindings.arena.allocate(bindings.msgLayout)
        val getMessage = bindings.handle("GetMessageW")
        val translateMessage = bindings.handle("TranslateMessage")
        val dispatchMessage = bindings.handle("DispatchMessageW")
        while (true) {
            try {
                val got = getMessage.invokeExact(
                    msg,
                    MemorySegment.NULL,                      // any of this thread's windows
                    0,                                        // wMsgFilterMin
                    0,                                        // wMsgFilterMax
                ) as Int
                if (got == 0) return
                check(got != -1) { "GetMessageW failed (GetLastError=${lastError()})" }
                translateMessage.invokeExact(msg) as Int
                dispatchMessage.invokeExact(msg) as Long
            } catch (t: Throwable) {
                log.warn("Win32 pump iteration threw: {}", t.toString())
                log.debug("Win32 pump iteration threw", t)
                if (!open.get()) return
                // Back off. Without this a permanently-failing call spins this
                // daemon thread at 100% for the life of the process, writing a
                // warn line per iteration.
                Thread.sleep(500)
            }
        }
    }

    /**
     * Create the message-only HWND. Runs on the pump thread (see
     * thread-affinity comment on [hwnd]). All other window-init work
     * (class registration, hInstance lookup) happened on the main
     * thread inside [Companion.createInternal] — that work is per-process
     * and thread-safe.
     */
    private fun createMessageWindow(): MemorySegment? {
        return runCatching {
            val windowName = allocateUtf16(bindings.arena, initial.title)
            bindings.handle("CreateWindowExW").invokeExact(
                0,                                      // dwExStyle
                classNameSeg,                           // lpClassName
                windowName,                             // lpWindowName
                0,                                      // dwStyle
                0, 0, 0, 0,                            // x, y, w, h (irrelevant for message-only)
                Win32Bindings.HWND_MESSAGE,             // hWndParent — message-only
                MemorySegment.NULL,                     // hMenu
                hInstance,                              // hInstance
                MemorySegment.NULL,                     // lpParam
            ) as MemorySegment
        }.onFailure { log.warn("CreateWindowExW threw", it) }.getOrNull()
    }

    // ── WndProc dispatch (called from the upcall stub) ───────────────────
    //
    // Returns the LRESULT for the message. Most paths chain to
    // DefWindowProcW; the ones we explicitly handle take the bypass.

    private fun handleMessage(uMsg: Int, wParam: Long, lParam: Long): Long = when (uMsg) {
        Win32Bindings.WM_DESTROY -> {
            // Window's gone — schedule pump exit. The WM_QUIT this posts
            // is what the pump loop above breaks on.
            runCatching {
                bindings.handle("PostQuitMessage").invokeExact(0) as Unit
            }
            0L
        }

        // Shell_NotifyIcon callback. With NOTIFYICON_VERSION_4 (set
        // during init), the dispatch format is:
        //   wParam = (LOWORD = x, HIWORD = y) cursor position in screen pixels
        //   lParam = (LOWORD = mouse event id, HIWORD = our icon uID)
        // Earlier versions packed mouse events into wParam — we don't
        // support them; the version negotiation already failed if the
        // shell can't speak v4.
        Win32Bindings.WM_TRAY_CALLBACK -> {
            val event = lowWordSigned(lParam)
            val x = lowWordSigned(wParam)
            val y = highWordSigned(wParam)
            when (event) {
                Win32Bindings.WM_LBUTTONUP -> fire(TrayEvent.Activated)
                Win32Bindings.WM_MBUTTONUP -> fire(TrayEvent.MiddleActivated)
                // A version 4 shell sends WM_RBUTTONUP and then WM_CONTEXTMENU
                // for one right click, and WM_CONTEXTMENU alone for Shift+F10
                // or the menu key. Opening on both would track the popup twice.
                Win32Bindings.WM_CONTEXTMENU -> if (version4) showContextMenu(x, y)
                Win32Bindings.WM_RBUTTONUP -> if (!version4) showContextMenu(x, y)
            }
            0L
        }

        // Menu item clicked via the legacy WM_COMMAND path — only fires
        // if we ever pass TrackPopupMenu without TPM_RETURNCMD. We use
        // TPM_RETURNCMD, so the selection comes back from TrackPopupMenu
        // as its return value and this branch shouldn't fire. Defensive.
        Win32Bindings.WM_COMMAND -> 0L

        else -> defWindowProc(uMsg, wParam, lParam)
    }

    private fun lowWordSigned(value: Long): Int = (value and 0xffff).toShort().toInt()
    private fun highWordSigned(value: Long): Int = ((value shr 16) and 0xffff).toShort().toInt()

    /**
     * Build an HMENU from [currentMenu] and pop it at the cursor. Blocks
     * the pump thread until the user picks an item or dismisses the
     * menu — TrackPopupMenu pumps its own messages internally, so the
     * window stays responsive. Selected libtray ids fire as
     * [TrayEvent.MenuItemSelected]; dismissals fire nothing.
     *
     * No menu set → no popup. Standard behaviour: an empty right-click
     * surface is less surprising than an empty popup that opens and
     * immediately closes.
     *
     * [TrayEvent.MenuRequested] fires first, menu or not: it reports that
     * the user asked for the menu. Listeners get it on the event thread, so
     * nothing guarantees it lands before the popup opens.
     */
    private fun showContextMenu(x: Int, y: Int) {
        fire(TrayEvent.MenuRequested)
        val menu = currentMenu ?: return
        if (menu.items.isEmpty()) return

        // Per MSDN: SetForegroundWindow before TrackPopupMenu, otherwise
        // clicks outside the menu don't dismiss it (the menu's owner
        // window has to be foreground for the standard "click-away
        // closes menu" behaviour to kick in).
        runCatching {
            bindings.handle("SetForegroundWindow").invokeExact(hwnd) as Int
        }

        // Confined arena for menu label strings. AppendMenuW (with
        // MF_STRING) copies the LPCWSTR contents into the menu's own
        // storage, so the labels only need to outlive the call —
        // releasing them after the loop avoids growing bindings.arena
        // by ~50 bytes per item on every right-click.
        Arena.ofConfined().use { tmpArena ->
            val cmdToId = HashMap<Int, String>()
            val nextCmd = AtomicInteger(0x1000)

            val rootHmenu = runCatching {
                bindings.handle("CreatePopupMenu").invokeExact() as MemorySegment
            }.getOrNull() ?: return
            if (rootHmenu.address() == 0L) return

            appendItems(rootHmenu, menu.items, cmdToId, nextCmd, tmpArena)

            val flags = Win32Bindings.TPM_RETURNCMD or Win32Bindings.TPM_RIGHTBUTTON
            val selected = runCatching {
                bindings.handle("TrackPopupMenu").invokeExact(
                    rootHmenu, flags, x, y, 0, hwnd, MemorySegment.NULL,
                ) as Int
            }.getOrDefault(0)

            // DestroyMenu on the root walks any submenus attached via
            // MF_POPUP — that frees the whole HMENU tree in one call.
            // Per Win32 docs, calling DestroyMenu on an already-attached
            // submenu is forbidden, so we never track them separately.
            runCatching { bindings.handle("DestroyMenu").invokeExact(rootHmenu) as Int }

            if (selected != 0) {
                val libtrayId = cmdToId[selected]
                if (libtrayId != null) {
                    fire(TrayEvent.MenuItemSelected(libtrayId))
                }
            }
        }
    }

    /**
     * Recursively append [items] into [parent] HMENU. Standard items get
     * a fresh command id (0x1000+counter) so TrackPopupMenu's return
     * value can be looked up against [cmdToId] to recover the libtray
     * id. Submenus get a child HMENU built recursively.
     *
     * Label segments are allocated from [tmpArena] — the caller owns
     * the arena and closes it after TrackPopupMenu returns; AppendMenuW
     * has already copied each label into the menu's internal storage by
     * then.
     */
    private fun appendItems(
        parent: MemorySegment,
        items: List<TrayMenuItem>,
        cmdToId: HashMap<Int, String>,
        nextCmd: AtomicInteger,
        tmpArena: Arena,
    ) {
        val appendMenu = bindings.handle("AppendMenuW")
        for (item in items) {
            when (item) {
                is TrayMenuItem.Separator -> {
                    runCatching {
                        appendMenu.invokeExact(parent, Win32Bindings.MF_SEPARATOR, 0L, MemorySegment.NULL) as Int
                    }
                }
                is TrayMenuItem.Standard -> {
                    val cmd = nextCmd.getAndIncrement()
                    cmdToId[cmd] = item.id
                    val labelSeg = allocateUtf16(tmpArena, item.label)
                    var flags = Win32Bindings.MF_STRING
                    if (!item.enabled) flags = flags or Win32Bindings.MF_GRAYED
                    runCatching {
                        appendMenu.invokeExact(parent, flags, cmd.toLong(), labelSeg) as Int
                    }
                }
                is TrayMenuItem.Submenu -> {
                    val child = runCatching {
                        bindings.handle("CreatePopupMenu").invokeExact() as MemorySegment
                    }.getOrNull()
                    if (child != null && child.address() != 0L) {
                        appendItems(child, item.items, cmdToId, nextCmd, tmpArena)
                        val labelSeg = allocateUtf16(tmpArena, item.label)
                        var flags = Win32Bindings.MF_POPUP or Win32Bindings.MF_STRING
                        if (!item.enabled) flags = flags or Win32Bindings.MF_GRAYED
                        runCatching {
                            appendMenu.invokeExact(parent, flags, child.address(), labelSeg) as Int
                        }
                    }
                }
            }
        }
    }

    private fun defWindowProc(uMsg: Int, wParam: Long, lParam: Long): Long =
        bindings.handle("DefWindowProcW").invokeExact(hwnd, uMsg, wParam, lParam) as Long

    private fun fire(event: TrayEvent) = events.fire(event)

    internal companion object {
        private val log = LoggerFactory.getLogger("libtray.Win32Tray")
        private val classCounter = AtomicInteger(0)

        /** How long close() waits for the pump thread before leaving its resources allocated. */
        private const val PUMP_JOIN_TIMEOUT_MS: Long = 2_000

        /** How long create() waits for the pump thread to bring the window and icon up. */
        private const val READY_TIMEOUT_MS: Long = 5_000

        /** The one upcall libtray needs on Windows: the OS calls it for every message to our windows. */
        internal val WND_PROC = UpcallTarget(Win32TrayImpl::class.java, "wndProcEntry", Win32Bindings.WNDPROC_DESCRIPTOR)

        /**
         * HWND address → instance map. The WndProc upcall stub gets
         * called by Windows with the HWND as its first argument; we
         * look up the matching Tray instance to dispatch into. Static
         * because the upcall stub is process-wide; instance state lives
         * here.
         */
        private val HWND_INSTANCES = ConcurrentHashMap<Long, Win32TrayImpl>()

        /**
         * Single process-wide upcall stub for WndProc. Created lazily on
         * the first [create] call — Linker.upcallStub allocates from the
         * arena, and we keep both alive for the JVM lifetime since the OS
         * may dispatch into the stub at any time during a Tray's life.
         *
         * The stub bridges to [wndProcEntry] via a static method handle.
         */
        @Volatile private var wndProcStub: MemorySegment? = null
        @Volatile private var wndProcArena: Arena? = null

        /**
         * Static entry point the WndProc upcall stub bridges to. Looks
         * up the per-HWND Win32TrayImpl and forwards. If no instance is
         * registered (e.g. the OS dispatched a CREATE message before we
         * recorded the mapping), we DefWindowProc out of an immutable
         * fallback path.
         */
        @JvmStatic
        fun wndProcEntry(hwnd: MemorySegment, uMsg: Int, wParam: Long, lParam: Long): Long {
            val inst = HWND_INSTANCES[hwnd.address()]
            if (inst != null) {
                return try {
                    inst.handleMessage(uMsg, wParam, lParam)
                } catch (t: Throwable) {
                    log.warn("WndProc dispatch threw, falling back to DefWindowProc", t)
                    // Not the instance's handle: that may be what just threw, and
                    // an exception leaving this upcall ends the JVM.
                    wndProcDefaultFallback(hwnd, uMsg, wParam, lParam)
                }
            }
            // No instance recorded yet — happens for the WM_GETMINMAXINFO
            // / WM_NCCREATE messages CreateWindowExW fires before
            // returning. DefWindowProc gives sane defaults for all of them.
            return wndProcDefaultFallback(hwnd, uMsg, wParam, lParam)
        }

        /**
         * DefWindowProc for messages with no instance yet, and the fallback when
         * an instance's dispatch throws. Resolved onto the same process-lifetime
         * arena as the WndProc stub.
         *
         * It must NOT come from a Win32Bindings instance: those arenas are
         * per-Tray and are closed by close() and by the failed-create path,
         * while this handle is reachable from the process-wide upcall stub
         * for as long as any window exists. A handle whose arena has been
         * closed throws on invoke, the fallback swallows that and returns 0,
         * and returning 0 from WM_NCCREATE makes CreateWindowExW fail -- so a
         * second Tray.create would silently produce no window.
         */
        @Volatile private var defWindowProcHandle: MethodHandle? = null

        private fun wndProcDefaultFallback(
            hwnd: MemorySegment, uMsg: Int, wParam: Long, lParam: Long,
        ): Long {
            val h = defWindowProcHandle ?: return 0L
            return try {
                h.invokeExact(hwnd, uMsg, wParam, lParam) as Long
            } catch (_: Throwable) {
                0L
            }
        }

        fun create(builder: TrayBuilder): Tray? {
            val bindings = Win32Bindings.load() ?: run {
                log.info("Win32 DLLs not loadable — Win32 tray unavailable")
                return null
            }

            var constructed = false
            val tray = runCatching {
                createInternal(bindings, builder) { constructed = true }
            }.onFailure { t ->
                log.warn("Win32 tray construction threw", t)
            }.getOrNull()
            // Once an instance exists its close() owns the arena, and may have
            // left it open on purpose for a pump thread that is still running.
            // Before that, nothing else will ever close it.
            if (tray == null && !constructed) runCatching { bindings.arena.close() }
            return tray
        }

        private fun createInternal(bindings: Win32Bindings, builder: TrayBuilder, onConstructed: () -> Unit): Tray? {
            val arena = bindings.arena

            // hInstance — HMODULE of the calling EXE. Passing NULL to
            // GetModuleHandleW returns the EXE that owns the JVM process.
            // Process-wide handle, safe to acquire on any thread.
            val hInstance = bindings.handle("GetModuleHandleW")
                .invokeExact(MemorySegment.NULL) as MemorySegment
            if (hInstance.address() == 0L) {
                log.warn("GetModuleHandleW returned NULL")
                return null
            }

            // Build the WndProc upcall stub once per JVM, lazily.
            val (stub, _) = ensureWndProcStub() ?: run {
                log.warn("Could not create WndProc upcall stub")
                return null
            }

            // Window class registration is per-process and thread-safe;
            // doing it on the main thread (here) keeps the pump thread's
            // entry path lean. CreateWindowExW itself has to wait until
            // the pump thread runs — that's done inside Win32TrayImpl's
            // pumpLoop, see the thread-affinity comment on [hwnd].
            val className = "LibtrayWin32-${ProcessHandle.current().pid()}-${classCounter.incrementAndGet()}"
            val classNameSeg = allocateUtf16(arena, className)

            val wc = arena.allocate(bindings.wndClassExWLayout)
            val layout = bindings.wndClassExWLayout
            fun off(name: String): Long = layout.byteOffset(MemoryLayout.PathElement.groupElement(name))
            wc.set(ValueLayout.JAVA_INT, off("cbSize"), layout.byteSize().toInt())
            wc.set(ValueLayout.JAVA_INT, off("style"), 0)
            wc.set(ValueLayout.ADDRESS, off("lpfnWndProc"), stub)
            wc.set(ValueLayout.JAVA_INT, off("cbClsExtra"), 0)
            wc.set(ValueLayout.JAVA_INT, off("cbWndExtra"), 0)
            wc.set(ValueLayout.ADDRESS, off("hInstance"), hInstance)
            wc.set(ValueLayout.ADDRESS, off("hIcon"), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, off("hCursor"), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, off("hbrBackground"), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, off("lpszMenuName"), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, off("lpszClassName"), classNameSeg)
            wc.set(ValueLayout.ADDRESS, off("hIconSm"), MemorySegment.NULL)

            val atom = bindings.handle("RegisterClassExW").invokeExact(wc) as Short
            if (atom.toInt() == 0) {
                val err = bindings.handle("GetLastError").invokeExact() as Int
                log.warn("RegisterClassExW failed (GetLastError={})", err)
                return null
            }
            log.info("Win32 window class registered: {}", className)

            val instance = Win32TrayImpl(bindings, classNameSeg, hInstance, builder)
            onConstructed()
            // Wait for the pump thread to finish window creation and
            // NIM_ADD before handing the Tray back to the caller. 5s
            // is generous, since these calls return synchronously from the
            // shell and complete in the millisecond range when they
            // succeed at all.
            if (!instance.readyLatch.awaitPastInterrupt(READY_TIMEOUT_MS)) {
                log.warn("Win32 tray pump thread did not signal ready within 5s; tearing down")
                instance.close()
                return null
            }
            if (!instance.creationSucceeded) {
                log.warn("Win32 tray creation reported failure on pump thread")
                instance.close()
                return null
            }
            return instance
        }

        /**
         * Lazy upcall-stub factory. Allocates the stub from a long-lived
         * Arena (`Arena.ofShared`) — the OS may call back into the stub
         * at any time during a window's lifetime, so the stub MUST
         * outlive every window. Cached so multiple Tray instances share
         * the single stub instead of each allocating its own.
         */
        @Synchronized
        private fun ensureWndProcStub(): Pair<MemorySegment, Arena>? {
            wndProcStub?.let { stub -> wndProcArena?.let { arena -> return stub to arena } }
            return runCatching {
                val arena = Arena.ofShared()
                val stub = WND_PROC.stub(arena)
                // Same arena, so the fallback stays callable for as long as
                // the stub the OS holds -- see defWindowProcHandle's KDoc.
                defWindowProcHandle = SymbolLookup.libraryLookup("user32", arena)
                    .find("DefWindowProcW")
                    .map { symbol ->
                        Linker.nativeLinker().downcallHandle(symbol, Win32Bindings.WNDPROC_DESCRIPTOR)
                    }
                    .orElse(null)
                wndProcStub = stub
                wndProcArena = arena
                stub to arena
            }.onFailure {
                log.warn("WndProc upcall stub creation failed", it)
            }.getOrNull()
        }

        /**
         * Allocate a null-terminated UTF-16LE string in the given arena.
         * Win32 wide-string APIs (`*W` suffix) read this format directly.
         */
        private fun allocateUtf16(arena: Arena, text: String): MemorySegment {
            val bytes = text.toByteArray(StandardCharsets.UTF_16LE)
            // +2 for the UTF-16 NUL terminator.
            val seg = arena.allocate((bytes.size + 2).toLong())
            seg.asByteBuffer().put(bytes)
            seg.set(ValueLayout.JAVA_SHORT, bytes.size.toLong(), 0.toShort())
            return seg
        }
    }
}
