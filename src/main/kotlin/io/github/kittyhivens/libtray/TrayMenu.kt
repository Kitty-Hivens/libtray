package io.github.kittyhivens.libtray

/**
 * Static description of the right-click menu attached to a tray icon.
 *
 * Immutable by design — to update the menu, build a new [TrayMenu] and
 * call [Tray.setMenu]. Backends diff against the previous menu when
 * possible (DBusMenu has explicit "layout updated" signals; Win32 rebuilds
 * the HMENU; macOS rebuilds the NSMenu).
 */
public data class TrayMenu(
    val items: List<TrayMenuItem>,
) {
    init {
        // Don't enforce uniqueness on item ids — some platforms (Win32)
        // index by HMENU position rather than id, and a duplicate id is
        // valid as long as both items don't both receive a click. Library
        // surface stays "you set, you handle".
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

    /** Localised label shown in the menu UI. */
    public val label: String

    /** When false, the item is rendered greyed-out and ignores clicks. */
    public val enabled: Boolean

    /**
     * A regular click-to-fire menu entry. Most common shape.
     */
    public data class Standard(
        public override val id: String,
        public override val label: String,
        public override val enabled: Boolean = true,
    ) : TrayMenuItem

    /**
     * A nested submenu. Backends render this as a parent item with an
     * arrow that opens the [items] children on hover/click.
     */
    public data class Submenu(
        public override val id: String,
        public override val label: String,
        public override val enabled: Boolean = true,
        val items: List<TrayMenuItem>,
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
