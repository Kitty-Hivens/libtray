package dev.hivens.libtray

/**
 * Events the tray icon can fire. Subscribed to via [Tray.onEvent].
 *
 * Backend coverage is NOT uniform, and the per-event docs below say
 * exactly who fires what. The click events depend on how much of the
 * interaction the platform's tray host keeps to itself, and on macOS on
 * [TrayBuilder.macosMenuOnPrimaryClick]. Switch on what you care about and
 * ignore the rest rather than assuming exhaustive coverage. In particular,
 * do not build a UI whose only entry point is [Activated].
 */
public sealed interface TrayEvent {

    /**
     * Primary click on the icon, the left button.
     *
     * On macOS a primary click opens the menu by default, as Mac users
     * expect, and then fires [MenuRequested] instead. It fires [Activated]
     * when [TrayBuilder.macosMenuOnPrimaryClick] is false or no menu is set.
     */
    public data object Activated : TrayEvent

    /**
     * Middle-button click. Fired on Linux (the SNI host's
     * `SecondaryActivate`), on Windows and on macOS.
     */
    public data object MiddleActivated : TrayEvent

    /**
     * The user asked for the menu, typically with a right click. Fired on
     * Windows and macOS as the popup is opened, also when no menu is set.
     * On macOS that includes a Control-click, and a primary click while
     * [TrayBuilder.macosMenuOnPrimaryClick] is true and a menu is set. On
     * Linux it depends on the tray host: the event comes from the SNI
     * `ContextMenu` call, and a host that renders the dbusmenu itself,
     * which is the common case, may open the menu without ever making that
     * call.
     *
     * Best effort on timing. Linux sends it alongside a menu the host has
     * already rendered, and elsewhere it reaches the listener on the event
     * thread while the popup opens, so it never gives you a chance to
     * rebuild the menu first. To change the menu, call [Tray.setMenu] when
     * the underlying state changes. Every backend picks the new layout up
     * before the next open.
     */
    public data object MenuRequested : TrayEvent

    /**
     * Menu item with the given [id] was selected. The id matches the one
     * the consumer set in [TrayMenuItem.id] when building the menu.
     *
     * Only enabled [TrayMenuItem.Standard] entries produce this. Separators
     * have no id worth reporting, disabled entries are not selectable, and
     * clicking a [TrayMenuItem.Submenu] parent opens the submenu instead of
     * making a selection.
     */
    public data class MenuItemSelected(val id: String) : TrayEvent
}
