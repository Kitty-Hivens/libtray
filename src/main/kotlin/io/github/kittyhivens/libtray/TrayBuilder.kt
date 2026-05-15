package io.github.kittyhivens.libtray

/**
 * Immutable construction parameters for [Tray.create]. Held by the backend
 * for its lifetime so subsequent setter calls can layer over a known
 * baseline.
 *
 * @property title The application identifier the tray host uses to
 *   distinguish this icon from other apps' icons. Linux's StatusNotifierItem
 *   uses it as the well-known D-Bus name suffix; Windows uses it for the
 *   notification-area uniqueness key; macOS shows it as the AppleScript
 *   identifier. Pick something stable across releases (typically your
 *   reverse-DNS app id or program name).
 * @property iconBytes Initial icon bytes. PNG is the universally-supported
 *   format. The library doesn't decode — backends pass the bytes straight
 *   to the OS surface.
 * @property tooltip Optional initial tooltip. Default null = no tooltip.
 * @property menu Optional initial right-click menu. Default null = the
 *   icon is click-only (no menu pops on right-click).
 */
public data class TrayBuilder(
    val title: String,
    val iconBytes: ByteArray,
    val tooltip: String? = null,
    val menu: TrayMenu? = null,
) {
    init {
        require(title.isNotBlank()) { "title must be non-blank" }
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
    }

    // Generated equals/hashCode skip ByteArray identity-vs-content equality —
    // override so two builders with the same bytes compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrayBuilder) return false
        return title == other.title &&
            tooltip == other.tooltip &&
            menu == other.menu &&
            iconBytes.contentEquals(other.iconBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + iconBytes.contentHashCode()
        result = 31 * result + (tooltip?.hashCode() ?: 0)
        result = 31 * result + (menu?.hashCode() ?: 0)
        return result
    }
}
