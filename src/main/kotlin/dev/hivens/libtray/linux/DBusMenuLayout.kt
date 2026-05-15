package dev.hivens.libtray.linux

import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
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
 * One [DBusMenuLayout] per [TrayMenu] snapshot — calling
 * [SniTrayImpl.setMenu] swaps in a fresh layout and emits the
 * `LayoutUpdated` signal so the host re-fetches.
 *
 * IDs are sequential ints starting from 1 (root is the reserved 0).
 * Stable across queries within a single layout instance, NOT across
 * layout swaps — host re-reads the layout after `LayoutUpdated` so it
 * always sees a consistent view.
 */
internal class DBusMenuLayout(menu: TrayMenu) {

    /** Exposed for property dispatch — caller iterates by int id. */
    val nodesById: Map<Int, Node>

    /** Children of each node (parent int id → list of child int ids). */
    val childrenByParent: Map<Int, List<Int>>

    init {
        val nodes = HashMap<Int, Node>()
        val children = HashMap<Int, MutableList<Int>>()
        val counter = AtomicInteger(0)

        // Root node — id 0, holds the top-level items as children.
        nodes[0] = Node(
            id = 0, originalId = "<root>",
            label = "", enabled = false,
            kind = NodeKind.Submenu,
        )
        children[0] = mutableListOf()

        fun walk(items: List<TrayMenuItem>, parentId: Int) {
            for (item in items) {
                val id = counter.incrementAndGet()
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
                    walk(item.items, id)
                }
            }
        }
        walk(menu.items, 0)

        nodesById = nodes
        childrenByParent = children
    }

    /** Look up by host-supplied int id. */
    fun nodeOf(id: Int): Node? = nodesById[id]

    /** Children of [parent] — empty list when leaf or unknown id. */
    fun childrenOf(parent: Int): List<Int> = childrenByParent[parent] ?: emptyList()

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
                props["label"] = PropertyValue.Str(node.label)
                if (!node.enabled) props["enabled"] = PropertyValue.Bool(false)
            }
            NodeKind.Submenu -> {
                if (node.id != 0) {
                    props["label"] = PropertyValue.Str(node.label)
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
}
