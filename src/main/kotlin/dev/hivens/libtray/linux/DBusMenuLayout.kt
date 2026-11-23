package dev.hivens.libtray.linux

import dev.hivens.libtray.MenuItemKey
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.menuItemKeys
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maps a [TrayMenu] (string-keyed, hierarchical) onto the integer-keyed
 * model the `com.canonical.dbusmenu` protocol expects. The host queries
 * us with int ids (the root is always 0); we hand back the corresponding
 * subtree, properties, and children. When the host fires `Event(id,
 * "clicked", ...)` we look the int back up to recover the original
 * caller-supplied string id and surface that via
 * [dev.hivens.libtray.TrayEvent.MenuItemSelected].
 *
 * One [DBusMenuLayout] per [TrayMenu] snapshot, built from the [previous]
 * one. Every host matches the items it shows by id, so an item that is still
 * in the menu, by its [MenuItemKey], keeps the id it had. An item new to the
 * menu takes the next number from [ids], which a tray shares across all its
 * layouts, so an id never comes back with another meaning. A layout whose
 * shape matches the previous one ([sameStructureAs]) only needs its changed
 * properties sent, so hosts update the menu they built instead of rebuilding
 * it.
 *
 * Items dropped from the menu stay resolvable through [clickTarget] for a
 * while, since a host can still have the previous menu on screen and the user
 * may click one of them.
 */
internal class DBusMenuLayout(menu: TrayMenu?, ids: AtomicInteger = AtomicInteger(0), previous: DBusMenuLayout? = null) {

    /** Exposed for property dispatch — caller iterates by int id. */
    val nodesById: Map<Int, Node>

    /** Children of each node (parent int id → list of child int ids). */
    val childrenByParent: Map<Int, List<Int>>

    /** The id of each item by its key, for the next layout to carry over. */
    private val idsByKey: Map<MenuItemKey, Int>

    /** Nodes earlier layouts had and this one dropped, oldest first, at most [MAX_RETIRED]. */
    private val retired: Map<Int, Node>

    init {
        val nodes = HashMap<Int, Node>()
        val children = HashMap<Int, MutableList<Int>>()
        val keyed = HashMap<MenuItemKey, Int>()

        // Root node — id 0, holds the top-level items as children.
        nodes[0] = Node(
            id = 0, originalId = "<root>",
            label = "", enabled = false,
            kind = NodeKind.Submenu,
        )
        children[0] = mutableListOf()

        fun walk(items: List<TrayMenuItem>, parentId: Int, parentKey: MenuItemKey?) {
            for ((item, key) in items.zip(menuItemKeys(items, parentKey))) {
                val id = previous?.idsByKey?.get(key) ?: ids.incrementAndGet()
                keyed[key] = id
                val kind = when (item) {
                    is TrayMenuItem.Standard -> NodeKind.Standard
                    is TrayMenuItem.Submenu  -> NodeKind.Submenu
                    is TrayMenuItem.Separator -> NodeKind.Separator
                }
                nodes[id] = Node(
                    id = id, originalId = item.id,
                    label = item.label, enabled = item.enabled,
                    kind = kind,
                )
                children.getOrPut(parentId) { mutableListOf() }.add(id)
                if (item is TrayMenuItem.Submenu) {
                    walk(item.items, id, key)
                }
            }
        }
        if (menu != null) walk(menu.items, 0, null)

        nodesById = nodes
        childrenByParent = children
        idsByKey = keyed
        retired = LinkedHashMap<Int, Node>().apply {
            if (previous != null) {
                for ((id, node) in previous.retired) if (id !in nodes) put(id, node)
                for ((id, node) in previous.nodesById) if (id != 0 && id !in nodes) put(id, node)
            }
            val excess = size - MAX_RETIRED
            if (excess > 0) keys.take(excess).forEach(::remove)
        }
    }

    /** Look up by host-supplied int id. */
    fun nodeOf(id: Int): Node? = nodesById[id]

    /**
     * The item a click on [id] means: one of this layout, or one an earlier
     * layout had, as a host may still show the menu it fetched before. Null for
     * an id no recent layout knows.
     */
    fun clickTarget(id: Int): Node? = nodesById[id] ?: retired[id]

    /** Children of [parent] — empty list when leaf or unknown id. */
    fun childrenOf(parent: Int): List<Int> = childrenByParent[parent] ?: emptyList()

    /** Whether [other] has the same items, of the same kinds, in the same places, so only properties can differ. */
    fun sameStructureAs(other: DBusMenuLayout): Boolean =
        childrenByParent == other.childrenByParent &&
            nodesById.all { (id, node) -> other.nodesById[id]?.kind == node.kind }

    /**
     * What changed since [previous], which has the same structure: for each
     * item whose properties differ, its full new set, and the names of the
     * properties it no longer has (back to their spec default). The two parts
     * of an `ItemsPropertiesUpdated` signal.
     */
    fun propertyChangesSince(previous: DBusMenuLayout): Pair<Map<Int, Map<String, PropertyValue>>, Map<Int, List<String>>> {
        val updated = LinkedHashMap<Int, Map<String, PropertyValue>>()
        val removed = LinkedHashMap<Int, List<String>>()
        for (id in nodesById.keys.sorted()) {
            val now = propertiesOf(id)
            val before = previous.propertiesOf(id)
            if (now == before) continue
            updated[id] = now
            val gone = before.keys - now.keys
            if (gone.isNotEmpty()) removed[id] = gone.toList()
        }
        return updated to removed
    }

    /**
     * Properties for [id] in the form the dbusmenu protocol expects —
     * a string→variant-equivalent map. Caller is responsible for
     * marshalling each value into a D-Bus variant.
     */
    fun propertiesOf(id: Int): Map<String, PropertyValue> {
        val node = nodesById[id] ?: return emptyMap()
        // Per dbusmenu spec: properties default to spec values when absent,
        // so we only emit non-default fields. label and enabled are
        // load-bearing for every item; type and children-display vary.
        val props = LinkedHashMap<String, PropertyValue>()
        when (node.kind) {
            NodeKind.Standard -> {
                props["label"] = PropertyValue.Str(wireLabel(node.label))
                if (!node.enabled) props["enabled"] = PropertyValue.Bool(false)
            }
            NodeKind.Submenu -> {
                if (node.id != 0) {
                    props["label"] = PropertyValue.Str(wireLabel(node.label))
                    if (!node.enabled) props["enabled"] = PropertyValue.Bool(false)
                }
                props["children-display"] = PropertyValue.Str("submenu")
            }
            NodeKind.Separator -> {
                props["type"] = PropertyValue.Str("separator")
            }
        }
        return props
    }

    /**
     * [label] as dbusmenu text that shows it literally. The spec reads a
     * single underscore as the access key marker and hides it, and shows a
     * doubled one as one underscore.
     */
    private fun wireLabel(label: String): String = label.replace("_", "__")

    internal data class Node(
        val id: Int,
        val originalId: String,
        val label: String,
        val enabled: Boolean,
        val kind: NodeKind,
    )

    internal enum class NodeKind { Standard, Submenu, Separator }

    /**
     * Sealed container for the property values dbusmenu emits — each
     * fan-out point in the SNI marshalling code branches on the kind to
     * pick the right `dbus_message_iter_append_basic` invocation.
     * Modelled here rather than as a raw `Any?` so the compiler enforces
     * exhaustive handling at the marshalling site.
     */
    internal sealed interface PropertyValue {
        data class Str(val value: String) : PropertyValue
        data class Bool(val value: Boolean) : PropertyValue
    }

    private companion object {
        /**
         * How many dropped items stay clickable. A host shows at most the
         * menu it fetched last, so this only has to outlast a few quick
         * setMenu calls while one is open, and it caps the memory an app that
         * churns item ids can pin.
         */
        const val MAX_RETIRED = 256
    }
}
