package dev.hivens.libtray

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
 * @property maxIconSize Longest edge, in pixels, an icon may have before
 *   libtray scales it down (aspect ratio preserved, re-encoded as PNG,
 *   logged at WARN). Applies to [iconBytes] and to every later
 *   [Tray.setIcon]. Tray hosts draw at panel height, so a 512 or 1024
 *   application icon costs bytes and CPU without ever showing the extra
 *   detail — on Linux it is re-marshalled into the `IconPixmap` property
 *   on every query the host makes. Default 256, which is also the largest
 *   size the Win32 backend accepts. Set to null to send the original
 *   bytes untouched.
 */
public data class TrayBuilder(
    val title: String,
    val iconBytes: ByteArray,
    val tooltip: String? = null,
    val menu: TrayMenu? = null,
    val maxIconSize: Int? = IconScaling.DEFAULT_MAX_SIZE,
) {
    init {
        require(title.isNotBlank()) { "title must be non-blank" }
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
        require(maxIconSize == null || maxIconSize > 0) {
            "maxIconSize must be positive, or null to disable scaling"
        }
    }

    // Generated equals/hashCode skip ByteArray identity-vs-content equality —
    // override so two builders with the same bytes compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrayBuilder) return false
        return title == other.title &&
            tooltip == other.tooltip &&
            menu == other.menu &&
            maxIconSize == other.maxIconSize &&
            iconBytes.contentEquals(other.iconBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + iconBytes.contentHashCode()
        result = 31 * result + (tooltip?.hashCode() ?: 0)
        result = 31 * result + (menu?.hashCode() ?: 0)
        result = 31 * result + (maxIconSize ?: 0)
        return result
    }
}
