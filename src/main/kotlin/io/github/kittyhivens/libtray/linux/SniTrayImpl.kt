package io.github.kittyhivens.libtray.linux

import io.github.kittyhivens.libtray.Tray
import io.github.kittyhivens.libtray.TrayBuilder
import io.github.kittyhivens.libtray.TrayEvent
import io.github.kittyhivens.libtray.TrayMenu

/**
 * Linux tray backend — StatusNotifierItem (`org.kde.StatusNotifierItem`)
 * over D-Bus, plus DBusMenu (`com.canonical.dbusmenu`) for the right-click
 * menu. The real `libdbus` Panama bindings + the SNI/DBusMenu protocol
 * implementation land in the next commit on this branch; this stub is in
 * place so the public [Tray.create] factory can dispatch through the
 * normal path.
 *
 * Why SNI direct rather than libayatana-appindicator: the latter wraps
 * SNI via GLib/GObject and pulls GTK3 transitively, adding 20-50 MB to a
 * bundled AppImage and a runtime dependency that minimal distros don't
 * ship. libdbus is part of every desktop Linux's core.
 */
internal class SniTrayImpl private constructor() : Tray {
    override val isOpen: Boolean = false
    override fun setTooltip(text: String): Boolean = false
    override fun setIcon(iconBytes: ByteArray): Boolean = false
    override fun setMenu(menu: TrayMenu?): Boolean = false
    override fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit = { }
    override fun close() = Unit

    internal companion object {
        /** Returns null until the SNI/D-Bus backend lands. */
        @Suppress("UNUSED_PARAMETER")
        fun create(builder: TrayBuilder): Tray? = null
    }
}
