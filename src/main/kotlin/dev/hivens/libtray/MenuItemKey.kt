package dev.hivens.libtray

/**
 * What makes a menu item the same item across [Tray.setMenu] calls: its
 * parent's key, its kind, its caller id, and how many earlier siblings share
 * that kind and id. Item ids are not unique (every separator is "---"), so the
 * position among its namesakes tells them apart, while a relabelled or
 * re-enabled item, or one that moved past an unrelated sibling, keeps its key.
 *
 * Backends keep a native item per key between menus, because the hosts they
 * talk to match items by identity: dbusmenu hosts by id, AppKit by the
 * NSMenuItem object. A new identity for every item on each setMenu makes a
 * host rebuild the whole menu, and turns a click on the menu still on screen
 * into a click on an item it no longer knows.
 */
internal data class MenuItemKey(val parent: MenuItemKey?, val kind: Kind, val id: String, val ordinal: Int) {
    enum class Kind { Standard, Submenu, Separator }
}

/** The keys of [items], children of the item with key [parent] (null at the top level), in order. */
internal fun menuItemKeys(items: List<TrayMenuItem>, parent: MenuItemKey?): List<MenuItemKey> {
    val seen = HashMap<Pair<MenuItemKey.Kind, String>, Int>()
    return items.map { item ->
        val kind = when (item) {
            is TrayMenuItem.Standard -> MenuItemKey.Kind.Standard
            is TrayMenuItem.Submenu -> MenuItemKey.Kind.Submenu
            is TrayMenuItem.Separator -> MenuItemKey.Kind.Separator
        }
        val ordinal = seen.merge(kind to item.id, 1, Int::plus)!! - 1
        MenuItemKey(parent, kind, item.id, ordinal)
    }
}
