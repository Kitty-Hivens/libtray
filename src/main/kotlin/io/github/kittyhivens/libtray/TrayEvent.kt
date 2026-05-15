package io.github.kittyhivens.libtray

/**
 * Events the tray icon can fire. Subscribed to via [Tray.onEvent].
 *
 * Backend coverage varies — every backend is guaranteed to fire at least
 * [Activated] (left-click) and [MenuItemSelected] (when a menu is set).
 * [MiddleActivated] and [MenuRequested] are best-effort: not all platforms
 * surface them as distinct events. Consumers should switch on what they
 * care about and ignore the rest rather than rely on exhaustive coverage.
 */
public sealed interface TrayEvent {

    /**
     * Primary click on the icon (left button on Win/Linux; single click
     * on macOS, where there's no concept of "primary"). Most apps wire
     * this to "show / focus the main window".
     */
    public data object Activated : TrayEvent

    /**
     * Middle-button click. Some Linux DEs surface this for "open a new
     * instance"; Windows fires it; macOS does not.
     */
    public data object MiddleActivated : TrayEvent

    /**
     * Right-click on the icon. Backends that auto-show the menu on
     * right-click (the typical case) fire this AFTER showing the menu —
     * it's informational. Useful for "rebuild menu lazily before showing"
     * patterns.
     */
    public data object MenuRequested : TrayEvent

    /**
     * Menu item with the given [id] was selected. The id matches the one
     * the consumer set in [TrayMenuItem.id] when building the menu.
     * Separators and disabled items don't fire this.
     */
    public data class MenuItemSelected(val id: String) : TrayEvent
}
