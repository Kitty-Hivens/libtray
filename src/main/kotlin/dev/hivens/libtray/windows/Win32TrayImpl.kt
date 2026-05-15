package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

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
 *      via `PeekMessageW` + `DispatchMessageW`.
 *   5. WndProc routes `WM_DESTROY` / `WM_USER+1` / `WM_COMMAND` to
 *      Kotlin-side handlers; everything else gets `DefWindowProcW`.
 *
 * **Phase 3 of the libtray rollout — foundation only.** This file
 * registers the window and runs the pump. The actual `Shell_NotifyIcon`
 * call, the `HICON` conversion from PNG, and the popup menu wiring land
 * in subsequent commits (Tasks #111, #112). Until those land,
 * [Tray.create] still returns a Win32 instance — but [setTooltip],
 * [setIcon], [setMenu] all no-op (return false), and no icon shows up in
 * the system tray.
 */
internal class Win32TrayImpl private constructor(
    private val bindings: Win32Bindings,
    private val hwnd: MemorySegment,
    private val classNameSeg: MemorySegment,
    private val hInstance: MemorySegment,
    initial: TrayBuilder,
) : Tray {

    private val log = LoggerFactory.getLogger("libtray.Win32Tray")

    @Volatile private var open = AtomicBoolean(true)
    private val handlers = CopyOnWriteArrayList<(TrayEvent) -> Unit>()

    /**
     * Reusable NOTIFYICONDATAW struct. Allocated once on the bindings
     * arena, mutated for each NIM_ADD / NIM_MODIFY / NIM_DELETE call.
     * The shell takes a snapshot of the struct contents during each
     * Shell_NotifyIcon call, so reuse is safe.
     */
    private val notifyIconData: MemorySegment = bindings.arena.allocate(bindings.notifyIconDataLayout)

    /**
     * Currently-installed HICON. Replaced atomically when [setIcon]
     * fires; the prior HICON gets DestroyIcon'd on success so we don't
     * leak GDI handles. Initially holds the icon built from the
     * builder's iconBytes.
     */
    private val iconHandle = AtomicReference<MemorySegment?>(null)

    /** Tracks the latest tooltip string for re-applying after setIcon. */
    @Volatile private var tooltip: String = initial.tooltip ?: ""

    /** Background pump thread — `PeekMessageW` + `DispatchMessageW` loop. */
    private val pumpThread = Thread({ pumpLoop() }, "libtray-win32-${ProcessHandle.current().pid()}").apply {
        isDaemon = true
    }

    init {
        HWND_INSTANCES[hwnd.address()] = this
        pumpThread.start()

        // Build HICON from the initial PNG; null is a soft failure —
        // we still register the icon entry (Shell_NotifyIcon allows
        // NIF_MESSAGE without NIF_ICON), the user just sees a blank
        // square in the tray. Logging at warn so the issue is visible.
        val initialIcon = pngToHicon(initial.iconBytes)
        if (initialIcon == null) {
            log.warn("PNG → HICON conversion returned null; tray entry will register without an icon")
        } else {
            iconHandle.set(initialIcon)
        }

        initNotifyIconData(initialIcon)
        if (!shellNotifyIcon(Win32Bindings.NIM_ADD)) {
            log.warn("Shell_NotifyIcon NIM_ADD failed (GetLastError={})", lastError())
        }
        // Opt into NOTIFYICON_VERSION_4 — modern mouse-message format
        // (Task #112 needs this to read button events from lParam).
        if (!shellNotifyIcon(Win32Bindings.NIM_SETVERSION)) {
            log.info("Shell_NotifyIcon NIM_SETVERSION failed; legacy mouse messages will be used")
        }
    }

    override val isOpen: Boolean get() = open.get()

    override fun setTooltip(text: String): Boolean {
        if (!open.get()) return false
        tooltip = text
        writeWideStringField(notifyIconData, "szTip", 128, text)
        notifyIconDataFlags(Win32Bindings.NIF_MESSAGE or Win32Bindings.NIF_ICON or Win32Bindings.NIF_TIP)
        return shellNotifyIcon(Win32Bindings.NIM_MODIFY)
    }

    override fun setIcon(iconBytes: ByteArray): Boolean {
        if (!open.get()) return false
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
        val newIcon = pngToHicon(iconBytes) ?: run {
            log.warn("PNG → HICON conversion returned null; setIcon ignored")
            return false
        }
        // Atomically swap the icon handle; destroy the old one only after
        // the swap succeeds so we never have the struct pointing at a
        // freed HICON. CompareAndSet not needed — setIcon serialised by
        // caller convention (single tray-control path).
        val prev = iconHandle.getAndSet(newIcon)
        notifyIconData.set(
            ValueLayout.ADDRESS,
            bindings.notifyIconDataLayout.byteOffset(MemoryLayout.PathElement.groupElement("hIcon")),
            newIcon,
        )
        notifyIconDataFlags(Win32Bindings.NIF_MESSAGE or Win32Bindings.NIF_ICON or Win32Bindings.NIF_TIP)
        val ok = shellNotifyIcon(Win32Bindings.NIM_MODIFY)
        if (prev != null && prev.address() != 0L) {
            runCatching { bindings.handle("DestroyIcon").invokeExact(prev) as Int }
        }
        return ok
    }

    override fun setMenu(menu: TrayMenu?): Boolean = false  // Task #112

    override fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        // Remove the tray icon BEFORE tearing down the window — once the
        // HWND is destroyed the shell may still hold an entry pointed at
        // a stale handle, which can leave a ghost icon until the next
        // explorer.exe restart. NIM_DELETE first is the documented order.
        runCatching { shellNotifyIcon(Win32Bindings.NIM_DELETE) }
        iconHandle.getAndSet(null)?.let { hicon ->
            if (hicon.address() != 0L) {
                runCatching { bindings.handle("DestroyIcon").invokeExact(hicon) as Int }
            }
        }
        // Post WM_CLOSE so the pump thread wakes up out of PeekMessage
        // and exits cleanly. DefWindowProc translates WM_CLOSE → DestroyWindow,
        // which fires WM_DESTROY on the same thread → PostQuitMessage(0)
        // in the WndProc → pump sees WM_QUIT → loop exits.
        runCatching {
            bindings.handle("PostMessageW").invokeExact(
                hwnd, Win32Bindings.WM_CLOSE, 0L, 0L,
            ) as Int
        }
        pumpThread.join(2_000)
        HWND_INSTANCES.remove(hwnd.address())
        // Class is per-instance; unregister so a fresh Tray.create can
        // re-register the same name without ERROR_CLASS_ALREADY_EXISTS.
        runCatching {
            bindings.handle("UnregisterClassW").invokeExact(classNameSeg, hInstance) as Int
        }
    }

    // ── Shell_NotifyIcon helpers ─────────────────────────────────────────

    private fun shellNotifyIcon(message: Int): Boolean {
        return runCatching {
            (bindings.handle("Shell_NotifyIconW").invokeExact(message, notifyIconData) as Int) != 0
        }.getOrElse { t ->
            log.warn("Shell_NotifyIcon (msg={}) threw: {}", message, t.message)
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
        notifyIconDataFlags(Win32Bindings.NIF_MESSAGE or Win32Bindings.NIF_ICON or Win32Bindings.NIF_TIP)
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
    // same path), convert ARGB→BGRA, flip vertically (CreateIcon expects
    // bottom-up DIB), call CreateIcon. With a 32bpp icon + a zero AND
    // mask, modern Windows uses the alpha channel for transparency.

    private fun pngToHicon(bytes: ByteArray): MemorySegment? {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: run {
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

        // XOR bits = BGRA, bottom-up.
        val xorBytes = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val srcRowStart = (h - 1 - y) * w
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

        val xorSeg = bindings.arena.allocate(xorBytes.size.toLong())
        xorSeg.asByteBuffer().put(xorBytes)
        val andSeg = bindings.arena.allocate(andBytes.size.toLong())
        andSeg.asByteBuffer().put(andBytes)

        val hicon = bindings.handle("CreateIcon").invokeExact(
            hInstance, w, h, 1.toByte(), 32.toByte(), andSeg, xorSeg,
        ) as MemorySegment
        if (hicon.address() == 0L) {
            log.warn("CreateIcon returned NULL (GetLastError={})", lastError())
            return null
        }
        return hicon
    }

    // ── Background dispatch loop ─────────────────────────────────────────

    private fun pumpLoop() {
        // Allocate a single MSG struct off the bindings arena; reused
        // every iteration. Lifetime tied to the Tray (closed alongside).
        val msg = bindings.arena.allocate(bindings.msgLayout)
        val peekMessage = bindings.handle("PeekMessageW")
        val translateMessage = bindings.handle("TranslateMessage")
        val dispatchMessage = bindings.handle("DispatchMessageW")
        val msgOffset = bindings.msgLayout.byteOffset(
            java.lang.foreign.MemoryLayout.PathElement.groupElement("message"),
        )
        while (open.get()) {
            try {
                val gotMessage = peekMessage.invokeExact(
                    msg,
                    MemorySegment.NULL,                      // any window
                    0,                                        // wMsgFilterMin
                    0,                                        // wMsgFilterMax
                    Win32Bindings.PM_REMOVE,
                ) as Int
                if (gotMessage == 0) {
                    // Queue empty — yield rather than spin. 16ms ≈ 60Hz
                    // wake rate; bursty events still feel instant, idle
                    // CPU stays in the noise.
                    Thread.sleep(16)
                    continue
                }
                val msgId = msg.get(ValueLayout.JAVA_INT, msgOffset)
                if (msgId == Win32Bindings.WM_QUIT) break
                translateMessage.invokeExact(msg) as Int
                dispatchMessage.invokeExact(msg) as Long
            } catch (t: Throwable) {
                log.warn("Win32 pump iteration threw: {}", t.message)
            }
        }
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

        // Shell_NotifyIcon callback — wired in Task #111 (icon) and the
        // mouse-side dispatched in Task #112 (menu/click). Until then the
        // window class IS registered with this WndProc, but no icon is
        // attached so the OS won't actually deliver WM_USER+1 events yet.
        Win32Bindings.WM_USER + 1 -> 0L

        // Menu item clicked — Task #112 wires LOWORD(wParam) → libtray
        // menu id → TrayEvent.MenuItemSelected. Foundation routing only.
        Win32Bindings.WM_COMMAND -> 0L

        else -> defWindowProc(uMsg, wParam, lParam)
    }

    private fun defWindowProc(uMsg: Int, wParam: Long, lParam: Long): Long =
        bindings.handle("DefWindowProcW").invokeExact(hwnd, uMsg, wParam, lParam) as Long

    @Suppress("unused")  // visible to fire() once Tasks #111/#112 wire events
    private fun fire(event: TrayEvent) {
        for (h in handlers) {
            runCatching { h(event) }.onFailure { log.warn("event handler threw", it) }
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libtray.Win32Tray")
        private val classCounter = AtomicInteger(0)

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
                    log.warn("WndProc dispatch threw, falling back to DefWindowProc: {}", t.message)
                    inst.defWindowProc(uMsg, wParam, lParam)
                }
            }
            // No instance recorded yet — happens for the WM_GETMINMAXINFO
            // / WM_NCCREATE messages CreateWindowExW fires before
            // returning. DefWindowProc gives sane defaults for all of them.
            return wndProcDefaultFallback(hwnd, uMsg, wParam, lParam)
        }

        /**
         * Pre-instance DefWindowProc — needs its own Win32Bindings handle
         * because we don't have a Tray instance to source one. Cached on
         * first call so the lookup amortises.
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
            // Cache DefWindowProc handle for the pre-instance fallback path.
            defWindowProcHandle = bindings.handle("DefWindowProcW")

            return runCatching {
                createInternal(bindings, builder)
            }.onFailure { t ->
                log.warn("Win32 tray construction threw: {}", t.message)
            }.getOrNull()
        }

        private fun createInternal(bindings: Win32Bindings, builder: TrayBuilder): Tray? {
            val arena = bindings.arena

            // hInstance — HMODULE of the calling EXE. Passing NULL to
            // GetModuleHandleW returns the EXE that owns the JVM process.
            val hInstance = bindings.handle("GetModuleHandleW")
                .invokeExact(MemorySegment.NULL) as MemorySegment
            if (hInstance.address() == 0L) {
                log.warn("GetModuleHandleW returned NULL")
                return null
            }

            // Build the WndProc upcall stub once per JVM, lazily.
            val (stub, _) = ensureWndProcStub(bindings) ?: run {
                log.warn("Could not create WndProc upcall stub")
                return null
            }

            // Window class name — wide-string. Suffixed with PID + counter
            // so multiple Tray instances + multiple processes don't collide.
            val className = "LibtrayWin32-${ProcessHandle.current().pid()}-${classCounter.incrementAndGet()}"
            val classNameSeg = allocateUtf16(arena, className)

            // WNDCLASSEXW — populate, register. cbSize is mandatory.
            val wc = arena.allocate(bindings.wndClassExWLayout)
            val layout = bindings.wndClassExWLayout
            wc.set(ValueLayout.JAVA_INT, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("cbSize")), layout.byteSize().toInt())
            wc.set(ValueLayout.JAVA_INT, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("style")), 0)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("lpfnWndProc")), stub)
            wc.set(ValueLayout.JAVA_INT, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("cbClsExtra")), 0)
            wc.set(ValueLayout.JAVA_INT, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("cbWndExtra")), 0)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("hInstance")), hInstance)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("hIcon")), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("hCursor")), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("hbrBackground")), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("lpszMenuName")), MemorySegment.NULL)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("lpszClassName")), classNameSeg)
            wc.set(ValueLayout.ADDRESS, layout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("hIconSm")), MemorySegment.NULL)

            val atom = bindings.handle("RegisterClassExW").invokeExact(wc) as Short
            if (atom.toInt() == 0) {
                val err = bindings.handle("GetLastError").invokeExact() as Int
                log.warn("RegisterClassExW failed (GetLastError={})", err)
                return null
            }

            // Create the message-only window. HWND_MESSAGE as parent puts
            // it in the message-only manager — invisible, doesn't appear
            // in alt-tab, but receives WM_USER messages just fine.
            val windowName = allocateUtf16(arena, builder.title)
            val hwnd = bindings.handle("CreateWindowExW").invokeExact(
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
            if (hwnd.address() == 0L) {
                val err = bindings.handle("GetLastError").invokeExact() as Int
                log.warn("CreateWindowExW failed (GetLastError={})", err)
                bindings.handle("UnregisterClassW").invokeExact(classNameSeg, hInstance) as Int
                return null
            }

            log.info("Win32 tray foundation up: hwnd=0x{} class={}",
                hwnd.address().toString(16), className)

            return Win32TrayImpl(bindings, hwnd, classNameSeg, hInstance, builder)
        }

        /**
         * Lazy upcall-stub factory. Allocates the stub from a long-lived
         * Arena (`Arena.ofShared`) — the OS may call back into the stub
         * at any time during a window's lifetime, so the stub MUST
         * outlive every window. Cached so multiple Tray instances share
         * the single stub instead of each allocating its own.
         */
        @Synchronized
        private fun ensureWndProcStub(bindings: Win32Bindings): Pair<MemorySegment, Arena>? {
            wndProcStub?.let { stub -> wndProcArena?.let { arena -> return stub to arena } }
            return runCatching {
                val handle = MethodHandles.lookup().findStatic(
                    Win32TrayImpl::class.java,
                    "wndProcEntry",
                    MethodType.methodType(
                        Long::class.javaPrimitiveType!!,
                        MemorySegment::class.java,
                        Int::class.javaPrimitiveType!!,
                        Long::class.javaPrimitiveType!!,
                        Long::class.javaPrimitiveType!!,
                    ),
                )
                val arena = Arena.ofShared()
                val stub = Linker.nativeLinker().upcallStub(handle, bindings.wndProcDescriptor, arena)
                wndProcStub = stub
                wndProcArena = arena
                stub to arena
            }.onFailure {
                log.warn("WndProc upcall stub creation failed: {}", it.message)
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
