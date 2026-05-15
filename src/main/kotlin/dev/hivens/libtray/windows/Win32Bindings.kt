package dev.hivens.libtray.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Panama bindings to the three Win32 system DLLs the tray backend needs.
 * Loaded once per process via [Win32Bindings.load]; the resulting object
 * holds method handles for the subset of `user32`/`shell32`/`gdi32`/
 * `kernel32` the [Win32TrayImpl] uses.
 *
 * Wire-level approach: a hidden message-only `HWND_MESSAGE` window
 * receives `Shell_NotifyIcon` callbacks (`WM_USER+1` and friends), with a
 * `PeekMessageW`/`DispatchMessageW` pump on a daemon thread we own.
 * Unlike the Linux SNI design, Win32 unavoidably needs ONE Panama upcall
 * stub — the WndProc — because Windows calls into the WndProc
 * synchronously to dispatch each message. The single stub routes by
 * `uMsg` value to internal handlers; everything else stays Java-side.
 *
 * Reference for constants and shapes: Microsoft `winuser.h`/`shellapi.h`
 * (https://learn.microsoft.com/en-us/windows/win32/api). Where this file
 * says `WS_EX_FOO` or `WM_USER` the value matches the C macro.
 */
internal class Win32Bindings private constructor(
    val arena: Arena,
    val handles: Map<String, MethodHandle>,
) {
    /**
     * Look up a previously-resolved handle. Throws if the symbol wasn't
     * in the load-time set — programmer error, not a runtime fallback.
     */
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("Win32 handle not loaded: $name. Add to LOAD_SET in Win32Bindings.load.")

    companion object {

        // ── Window message constants from winuser.h ─────────────────────────
        // We only declare the ones the tray dispatch actually keys on.

        const val WM_DESTROY:  Int = 0x0002
        const val WM_CLOSE:    Int = 0x0010
        const val WM_QUIT:     Int = 0x0012
        const val WM_COMMAND:  Int = 0x0111
        const val WM_USER:     Int = 0x0400  // Shell_NotifyIcon callback base; we use WM_USER + 1

        // Mouse messages forwarded by Shell_NotifyIcon as the lParam of WM_USER+1.
        const val WM_LBUTTONUP: Int = 0x0202
        const val WM_RBUTTONUP: Int = 0x0205
        const val WM_CONTEXTMENU: Int = 0x007B

        // ── Window-creation constants ───────────────────────────────────────
        // HWND_MESSAGE = (HWND)-3 — pseudo-parent that puts the new window
        // in the message-only window manager. No screen presence, but the
        // window can still receive WM_USER messages from Shell_NotifyIcon.

        val HWND_MESSAGE: MemorySegment = MemorySegment.ofAddress(-3L)

        // CW_USEDEFAULT for x/y/width/height. Values are signed 32-bit.
        const val CW_USEDEFAULT: Int = 0x80000000.toInt()

        /**
         * Library names — Win32 system DLLs. JDK's `SymbolLookup.libraryLookup`
         * is given the un-suffixed name; the loader appends `.dll` and walks
         * `KnownDLLs`.
         */
        private val WIN32_DLLS = listOf("kernel32", "user32", "shell32", "gdi32")

        /**
         * Symbols this binding loads. Each entry: name → (return layout or
         * null for void, arg layouts...). The bindings cover Task #110's
         * scope — message-only window + pump. Icon / tooltip / menu calls
         * land in subsequent commits and should be appended here.
         *
         * `LRESULT`/`WPARAM`/`LPARAM`/`UINT_PTR`/`LONG_PTR` are all 64-bit
         * on x86_64 Windows — JAVA_LONG. `BOOL`/`UINT`/`int` are 32-bit —
         * JAVA_INT. `HWND`/`HMODULE`/`HINSTANCE` are pointer types — ADDRESS.
         * Wide strings (`LPCWSTR`) are pointers to UTF-16 — ADDRESS.
         */
        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // ── kernel32 ───────────────────────────────────────────────────
            Triple("GetModuleHandleW",
                ValueLayout.ADDRESS,                                        // HMODULE
                listOf(ValueLayout.ADDRESS),                                // LPCWSTR (NULL = current)
            ),
            Triple("GetLastError",
                ValueLayout.JAVA_INT,                                       // DWORD
                emptyList(),
            ),

            // ── user32: window class + window ──────────────────────────────
            Triple("RegisterClassExW",
                ValueLayout.JAVA_SHORT,                                     // ATOM (returned as USHORT)
                listOf(ValueLayout.ADDRESS),                                // const WNDCLASSEXW*
            ),
            Triple("UnregisterClassW",
                ValueLayout.JAVA_INT,                                       // BOOL
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),           // LPCWSTR, HINSTANCE
            ),
            Triple("CreateWindowExW",
                ValueLayout.ADDRESS,                                        // HWND
                listOf(
                    ValueLayout.JAVA_INT,    // dwExStyle
                    ValueLayout.ADDRESS,     // LPCWSTR lpClassName
                    ValueLayout.ADDRESS,     // LPCWSTR lpWindowName
                    ValueLayout.JAVA_INT,    // DWORD dwStyle
                    ValueLayout.JAVA_INT,    // int x
                    ValueLayout.JAVA_INT,    // int y
                    ValueLayout.JAVA_INT,    // int nWidth
                    ValueLayout.JAVA_INT,    // int nHeight
                    ValueLayout.ADDRESS,     // HWND hWndParent
                    ValueLayout.ADDRESS,     // HMENU hMenu
                    ValueLayout.ADDRESS,     // HINSTANCE hInstance
                    ValueLayout.ADDRESS,     // LPVOID lpParam
                ),
            ),
            Triple("DestroyWindow",
                ValueLayout.JAVA_INT,                                       // BOOL
                listOf(ValueLayout.ADDRESS),                                // HWND
            ),
            Triple("DefWindowProcW",
                ValueLayout.JAVA_LONG,                                      // LRESULT
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                       ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),       // HWND, UINT, WPARAM, LPARAM
            ),

            // ── user32: message pump ───────────────────────────────────────
            Triple("PeekMessageW",
                ValueLayout.JAVA_INT,                                       // BOOL
                listOf(
                    ValueLayout.ADDRESS,     // LPMSG
                    ValueLayout.ADDRESS,     // HWND filter (NULL = all)
                    ValueLayout.JAVA_INT,    // UINT wMsgFilterMin
                    ValueLayout.JAVA_INT,    // UINT wMsgFilterMax
                    ValueLayout.JAVA_INT,    // UINT wRemoveMsg
                ),
            ),
            Triple("TranslateMessage",
                ValueLayout.JAVA_INT,                                       // BOOL
                listOf(ValueLayout.ADDRESS),                                // const MSG*
            ),
            Triple("DispatchMessageW",
                ValueLayout.JAVA_LONG,                                      // LRESULT
                listOf(ValueLayout.ADDRESS),                                // const MSG*
            ),
            Triple("PostMessageW",
                ValueLayout.JAVA_INT,                                       // BOOL
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                       ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),       // HWND, UINT, WPARAM, LPARAM
            ),
            Triple("PostQuitMessage",
                null,                                                       // void
                listOf(ValueLayout.JAVA_INT),                               // int nExitCode
            ),
        )

        /** PeekMessage flags from winuser.h. */
        const val PM_NOREMOVE: Int = 0x0000
        const val PM_REMOVE:   Int = 0x0001

        /**
         * Load the Win32 DLLs into a fresh shared arena and bind every
         * symbol in [LOAD_SET]. Returns null when:
         *   * we're not on Windows (DLLs won't load)
         *   * any required symbol is missing — fail-fast, no partial backend
         *
         * Caller treats null as "no Win32 tray", same null-as-degrade path
         * the Linux SNI backend uses.
         */
        fun load(): Win32Bindings? {
            val arena = Arena.ofShared()
            val combined = WIN32_DLLS.mapNotNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            }
            if (combined.isEmpty()) {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((name, ret, args) in LOAD_SET) {
                val descriptor = if (ret == null) {
                    FunctionDescriptor.ofVoid(*args.toTypedArray())
                } else {
                    FunctionDescriptor.of(ret, *args.toTypedArray())
                }
                // Walk the loaded DLL list — symbol could be in any of them
                // (kernel32 has GetModuleHandle, user32 has the rest, etc).
                val symbol = combined.firstNotNullOfOrNull { it.find(name).orElse(null) }
                    ?: run {
                        arena.close()
                        return null
                    }
                handles[name] = linker.downcallHandle(symbol, descriptor)
            }
            return Win32Bindings(arena, handles)
        }
    }

    // ── Struct layouts ──────────────────────────────────────────────────────
    //
    // WNDCLASSEXW from winuser.h. cbSize MUST equal the struct size or
    // RegisterClassExW returns 0 with GetLastError() == ERROR_INVALID_PARAMETER.

    val wndClassExWLayout: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cbSize"),
        ValueLayout.JAVA_INT.withName("style"),
        ValueLayout.ADDRESS.withName("lpfnWndProc"),
        ValueLayout.JAVA_INT.withName("cbClsExtra"),
        ValueLayout.JAVA_INT.withName("cbWndExtra"),
        ValueLayout.ADDRESS.withName("hInstance"),
        ValueLayout.ADDRESS.withName("hIcon"),
        ValueLayout.ADDRESS.withName("hCursor"),
        ValueLayout.ADDRESS.withName("hbrBackground"),
        ValueLayout.ADDRESS.withName("lpszMenuName"),
        ValueLayout.ADDRESS.withName("lpszClassName"),
        ValueLayout.ADDRESS.withName("hIconSm"),
    )

    /**
     * MSG from winuser.h — populated by PeekMessageW / GetMessageW. POINT
     * is two LONGs; lPrivate is undocumented padding the kernel reserves.
     *
     * ```c
     * typedef struct tagMSG {
     *   HWND   hwnd;     // 8
     *   UINT   message;  // 4
     *   WPARAM wParam;   // 8 (after 4-byte pad)
     *   LPARAM lParam;   // 8
     *   DWORD  time;     // 4
     *   POINT  pt;       // 8 (LONG x, LONG y)
     *   DWORD  lPrivate; // 4 (padding to alignment)
     * } MSG;
     * ```
     */
    val msgLayout: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("hwnd"),
        ValueLayout.JAVA_INT.withName("message"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("wParam"),
        ValueLayout.JAVA_LONG.withName("lParam"),
        ValueLayout.JAVA_INT.withName("time"),
        ValueLayout.JAVA_INT.withName("pt_x"),
        ValueLayout.JAVA_INT.withName("pt_y"),
        ValueLayout.JAVA_INT.withName("lPrivate"),
    )

    // ── Upcall stub: WndProc ───────────────────────────────────────────────
    //
    // The single Panama upcall stub libtray needs on Windows. Hands the
    // callable C function pointer to RegisterClassExW.lpfnWndProc; the
    // OS calls it synchronously for every message dispatched to our
    // window. Implementing it as a plain Java MethodHandle keeps the
    // routing logic in Kotlin where it belongs.

    /**
     * `LRESULT CALLBACK WndProc(HWND, UINT, WPARAM, LPARAM)` descriptor —
     * matches the C calling convention of `WNDPROC` in winuser.h. Used as
     * the function descriptor for [Linker.upcallStub].
     */
    val wndProcDescriptor: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,                                  // LRESULT
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,           // HWND, UINT, WPARAM, LPARAM
    )
}
