package dev.hivens.libtray

/**
 * Events the tray icon can fire. Subscribed to via [Tray.onEvent].
 *
 * Backend coverage is NOT uniform, and the per-event docs below say
 * exactly who fires what. Only [MenuItemSelected] is delivered by all
 * three backends; the click events depend on how much of the interaction
 * the platform's own tray host keeps to itself. Switch on what you care
 * about and ignore the rest rather than assuming exhaustive coverage —
 * in particular, do not build a UI whose only entry point is [Activated].
 */
public sealed interface TrayEvent {

    /**
     * Primary click on the icon — left button on Linux and Windows.
     *
     * Not fired on macOS. `NSStatusItem` routes the primary button to the
     * item's own menu, and libtray installs no target-action on the status
     * button, so the click never reaches Kotlin. A macOS consumer that
     * needs a "show the window" affordance has to put it in the menu.
     */
    public data object Activated : TrayEvent

    /**
     * Middle-button click. Fired on Linux (the SNI host's
     * `SecondaryActivate`) and on Windows. macOS does not surface a middle
     * click on a status item.
     */
    public data object MiddleActivated : TrayEvent

    /**
     * Right-click on the icon. Fired on Linux only, from the SNI host's
     * `ContextMenu` call.
     *
     * Informational, and it does NOT give you a chance to rebuild the menu
     * first: the host renders from the layout it already fetched, so this
     * arrives alongside the menu rather than before it. To change the menu,
     * call [Tray.setMenu] when the underlying state changes — every backend
     * picks the new layout up before the next open. Windows and macOS build
     * their popup without routing anything back to the consumer.
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
