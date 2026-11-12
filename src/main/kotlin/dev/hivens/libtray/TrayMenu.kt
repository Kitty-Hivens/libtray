package dev.hivens.libtray

/**
 * Static description of the right-click menu attached to a tray icon.
 *
 * Immutable by design: to update the menu, build a new [TrayMenu] and call
 * [Tray.setMenu]. Each backend rebuilds its native menu from it whole (a new
 * DBusMenu layout on Linux, a new NSMenu on macOS, a new HMENU on Windows at
 * every right click).
 */
public data class TrayMenu(
    val items: List<TrayMenuItem>,
) {
    /** Varargs convenience, mostly for Java: `new TrayMenu(show, TrayMenuItem.Separator.INSTANCE, exit)`. */
    public constructor(vararg items: TrayMenuItem) : this(items.toList())

    init {
        // Item ids are not checked for uniqueness. Backends key their native
        // items by their own numbers, so two items sharing an id both work and
        // both report that id. Library surface stays "you set, you handle".
        require(items.isNotEmpty()) { "menu must have at least one item; pass null to setMenu to remove the menu instead" }
    }
}

/**
 * One item in a [TrayMenu]. Each subclass models a distinct rendering
 * shape that all three backends understand without per-platform branching.
 */
public sealed interface TrayMenuItem {

    /** Stable id surfaced via [TrayEvent.MenuItemSelected] when clicked. */
    public val id: String

    /**
     * Localised label shown in the menu UI, as written. `_` and `&` are shown
     * as themselves and do not mark an access key on any platform.
     */
    public val label: String

    /** When false, the item is rendered greyed-out and ignores clicks. */
    public val enabled: Boolean

    /**
     * A regular click-to-fire menu entry. Most common shape.
     */
    public data class Standard @JvmOverloads constructor(
        public override val id: String,
        public override val label: String,
        public override val enabled: Boolean = true,
    ) : TrayMenuItem

    /**
     * A nested submenu. Backends render this as a parent item with an
     * arrow that opens the [items] children on hover/click.
     */
    public data class Submenu @JvmOverloads constructor(
        public override val id: String,
        public override val label: String,
        val items: List<TrayMenuItem>,
        public override val enabled: Boolean = true,
    ) : TrayMenuItem {
        init {
            require(items.isNotEmpty()) { "submenu '$id' must have at least one item" }
        }
    }

    /**
     * A horizontal separator line. Has no id, label, or click behaviour;
     * the [id] / [label] / [enabled] fields are forced to fixed values
     * because the sealed interface contract requires them but the
     * separator doesn't use them.
     */
    public data object Separator : TrayMenuItem {
        public override val id: String = "---"
        public override val label: String = ""
        public override val enabled: Boolean = false
    }
}
