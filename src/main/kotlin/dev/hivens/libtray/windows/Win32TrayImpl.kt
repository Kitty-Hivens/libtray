package dev.hivens.libtray.windows

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu

/**
 * Windows tray backend stub. Real implementation will wrap
 * `Shell_NotifyIcon` from `shell32.dll` and pump notifications via a
 * Win32 message loop. Lands in a follow-up PR; this file exists so the
 * factory in [dev.hivens.libtray.Tray.create] can dispatch
 * without conditional reflection.
 */
internal class Win32TrayImpl private constructor() : Tray {
    override val isOpen: Boolean = false
    override fun setTooltip(text: String): Boolean = false
    override fun setIcon(iconBytes: ByteArray): Boolean = false
    override fun setMenu(menu: TrayMenu?): Boolean = false
    override fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit = { }
    override fun close() = Unit

    internal companion object {
        /** Returns null until the Shell_NotifyIcon backend lands. */
        @Suppress("UNUSED_PARAMETER")
        fun create(builder: TrayBuilder): Tray? = null
    }
}
