package dev.hivens.libtray

/**
 * Immutable construction parameters for [Tray.create]. Held by the backend
 * for its lifetime so subsequent setter calls can layer over a known
 * baseline.
 *
 * Kotlin builds one with named arguments. Java uses [TrayBuilder.of], which
 * keeps compiling when a field is added:
 *
 * ```java
 * TrayBuilder spec = TrayBuilder.of("MyApp", iconBytes).menu(menu).build();
 * ```
 *
 * @property title The application identifier the tray host uses to
 *   distinguish this icon from other apps' icons. Linux derives the SNI
 *   `Id` from it; Windows uses it for the notification-area uniqueness key;
 *   macOS shows it as the AppleScript identifier. Pick something stable
 *   across releases (typically your reverse-DNS app id or program name).
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
 * @property linuxBusName Optional well-known D-Bus name for the Linux
 *   StatusNotifierItem. Defaults to the existing
 *   `org.kde.StatusNotifierItem-PID-N` name. Flatpak apps can set a name
 *   within their own app-id namespace (for example,
 *   `com.example.MyApp.StatusNotifierItem`) instead of requesting
 *   ownership of `org.kde.*`. The name must be unique on the session
 *   bus: a second tray asking for a name already taken, from this
 *   process or from another running instance of the app, gets no icon
 *   and [Tray.create] returns null. Ignored on Windows and macOS.
 * @property macosMenuOnPrimaryClick Whether a primary click on the macOS
 *   status item opens the menu, which is what Mac users expect from the
 *   menu bar. Default true. Set it to false to get [TrayEvent.Activated]
 *   for a primary click instead, with the menu on a right click or a
 *   Control-click, the way Linux and Windows behave. With no menu set,
 *   a primary click fires [TrayEvent.Activated] either way. Ignored on
 *   Linux and Windows.
 */
public data class TrayBuilder(
    val title: String,
    val iconBytes: ByteArray,
    val tooltip: String? = null,
    val menu: TrayMenu? = null,
    val maxIconSize: Int? = IconScaling.DEFAULT_MAX_SIZE,
    val linuxBusName: String? = null,
    val macosMenuOnPrimaryClick: Boolean = true,
) {
    init {
        require(title.isNotBlank()) { "title must be non-blank" }
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
        require(maxIconSize == null || maxIconSize > 0) {
            "maxIconSize must be positive, or null to disable scaling"
        }
        require(linuxBusName == null ||
            (linuxBusName.length <= 255 && WELL_KNOWN_BUS_NAME.matches(linuxBusName))) {
            "linuxBusName must be a valid well-known D-Bus name"
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
            linuxBusName == other.linuxBusName &&
            macosMenuOnPrimaryClick == other.macosMenuOnPrimaryClick &&
            iconBytes.contentEquals(other.iconBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + iconBytes.contentHashCode()
        result = 31 * result + (tooltip?.hashCode() ?: 0)
        result = 31 * result + (menu?.hashCode() ?: 0)
        result = 31 * result + (maxIconSize ?: 0)
        result = 31 * result + (linuxBusName?.hashCode() ?: 0)
        result = 31 * result + macosMenuOnPrimaryClick.hashCode()
        return result
    }

    /**
     * Fluent construction for Java, which has no named or default
     * arguments. Start it with [TrayBuilder.of]. Every optional field has a
     * setter and starts at the same default the constructor uses, so a
     * field added later is one more setter and existing call sites keep
     * compiling. Validation runs in [build].
     */
    public class Builder internal constructor(
        private val title: String,
        private val iconBytes: ByteArray,
    ) {
        private var tooltip: String? = null
        private var menu: TrayMenu? = null
        private var maxIconSize: Int? = IconScaling.DEFAULT_MAX_SIZE
        private var linuxBusName: String? = null
        private var macosMenuOnPrimaryClick: Boolean = true

        /** See [TrayBuilder.tooltip]. */
        public fun tooltip(tooltip: String?): Builder = apply { this.tooltip = tooltip }

        /** See [TrayBuilder.menu]. */
        public fun menu(menu: TrayMenu?): Builder = apply { this.menu = menu }

        /** See [TrayBuilder.maxIconSize]. */
        public fun maxIconSize(maxIconSize: Int?): Builder = apply { this.maxIconSize = maxIconSize }

        /** See [TrayBuilder.linuxBusName]. */
        public fun linuxBusName(linuxBusName: String?): Builder = apply { this.linuxBusName = linuxBusName }

        /** See [TrayBuilder.macosMenuOnPrimaryClick]. */
        public fun macosMenuOnPrimaryClick(macosMenuOnPrimaryClick: Boolean): Builder =
            apply { this.macosMenuOnPrimaryClick = macosMenuOnPrimaryClick }

        /** @throws IllegalArgumentException on the same invalid input the constructor rejects. */
        public fun build(): TrayBuilder =
            TrayBuilder(title, iconBytes, tooltip, menu, maxIconSize, linuxBusName, macosMenuOnPrimaryClick)
    }

    public companion object {
        private val WELL_KNOWN_BUS_NAME = Regex("[A-Za-z_-][A-Za-z0-9_-]*(\\.[A-Za-z_-][A-Za-z0-9_-]*)+")

        /** Start a [Builder] from the two required fields. */
        @JvmStatic
        public fun of(title: String, iconBytes: ByteArray): Builder = Builder(title, iconBytes)
    }
}
