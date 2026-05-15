package io.github.kittyhivens.libtray.macos

import io.github.kittyhivens.libtray.Tray
import io.github.kittyhivens.libtray.TrayBuilder
import io.github.kittyhivens.libtray.TrayEvent
import io.github.kittyhivens.libtray.TrayMenu

/**
 * macOS tray backend stub. Real implementation will wrap `NSStatusBar`
 * + `NSStatusItem` from AppKit via `objc_msgSend` Panama bindings. Lands
 * in a follow-up PR; this file exists so the factory in
 * [io.github.kittyhivens.libtray.Tray.create] can dispatch without
 * conditional reflection.
 *
 * Implementation note for future-self / contributors: AppKit must be
 * touched from the Cocoa main thread. The JVM's main thread fits as long
 * as the host app launched with `-XstartOnFirstThread` (Aura already does
 * this). Without it, `NSStatusBar.systemStatusBar` returns nil and the
 * icon never appears.
 */
internal class AppKitTrayImpl private constructor() : Tray {
    override val isOpen: Boolean = false
    override fun setTooltip(text: String): Boolean = false
    override fun setIcon(iconBytes: ByteArray): Boolean = false
    override fun setMenu(menu: TrayMenu?): Boolean = false
    override fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit = { }
    override fun close() = Unit

    internal companion object {
        /** Returns null until the NSStatusItem backend lands. */
        @Suppress("UNUSED_PARAMETER")
        fun create(builder: TrayBuilder): Tray? = null
    }
}
