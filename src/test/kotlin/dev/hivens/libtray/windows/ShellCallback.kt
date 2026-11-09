package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray

/**
 * Post the tray window a callback the way the shell does, in the
 * NOTIFYICON_VERSION_4 layout: event in LOWORD(lParam), icon id in HIWORD.
 * False when the post failed.
 */
internal fun postShellCallback(tray: Tray, mouseEvent: Int): Boolean {
    val hwnd = (tray as Win32TrayImpl).windowHandle
    val bindings = Win32Bindings.load() ?: error("user32 not loadable")
    try {
        val lParam = (mouseEvent.toLong() and 0xffff) or (Win32Bindings.TRAY_ICON_UID.toLong() shl 16)
        return (bindings.handle("PostMessageW").invokeExact(hwnd, Win32Bindings.WM_TRAY_CALLBACK, 0L, lParam) as Int) != 0
    } finally {
        bindings.arena.close()
    }
}
