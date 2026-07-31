package dev.hivens.libtray.linux

import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The string-id [TrayMenu] to int-id dbusmenu mapping. Everything the
 * `com.canonical.dbusmenu` server answers with -- GetLayout's tree,
 * GetGroupProperties' dicts, and the reverse lookup that turns an
 * `Event(id, "clicked")` back into a caller-visible id -- is read out of
 * this class, so a misshape here is invisible until a real tray host
 * renders the wrong menu.
 */
class DBusMenuLayoutTest {

    private val menu = TrayMenu(
        listOf(
            TrayMenuItem.Standard(id = "show", label = "Show"),
            TrayMenuItem.Separator,
            TrayMenuItem.Standard(id = "busy", label = "Busy", enabled = false),
            TrayMenuItem.Submenu(
                id = "servers", label = "Servers",
                items = listOf(
                    TrayMenuItem.Standard(id = "eu", label = "EU"),
                    TrayMenuItem.Standard(id = "us", label = "US"),
                ),
            ),
        ),
    )

    @Test
    fun `root is id 0 and holds the top-level items as children`() {
        val layout = DBusMenuLayout(menu)

        layout.nodeOf(0)!!.originalId shouldBe "<root>"
        // Ids are assigned depth-first from 1: show=1, sep=2, busy=3,
        // servers=4, then its children 5 and 6 -- so the root's own
        // children are the four top-level entries, not the leaves.
        layout.childrenOf(0) shouldContainExactly listOf(1, 2, 3, 4)
        layout.childrenOf(4) shouldContainExactly listOf(5, 6)
        layout.nodeOf(5)!!.originalId shouldBe "eu"
        layout.nodeOf(6)!!.originalId shouldBe "us"
    }

    @Test
    fun `unknown ids resolve to nothing rather than throwing`() {
        val layout = DBusMenuLayout(menu)
        // A host can send any int over the bus, including ids from a
        // layout we already replaced.
        layout.nodeOf(999).shouldBeNull()
        layout.childrenOf(999) shouldContainExactly emptyList()
        layout.propertiesOf(999) shouldContainExactly emptyMap()
    }

    @Test
    fun `node kind and enabled flag survive the mapping`() {
        // These two fields are what SniTrayImpl.fireMenuClick filters on
        // before surfacing MenuItemSelected, so they have to be faithful.
        val layout = DBusMenuLayout(menu)

        layout.nodeOf(1)!!.kind shouldBe DBusMenuLayout.NodeKind.Standard
        layout.nodeOf(1)!!.enabled shouldBe true
        layout.nodeOf(2)!!.kind shouldBe DBusMenuLayout.NodeKind.Separator
        layout.nodeOf(3)!!.kind shouldBe DBusMenuLayout.NodeKind.Standard
        layout.nodeOf(3)!!.enabled shouldBe false
        layout.nodeOf(4)!!.kind shouldBe DBusMenuLayout.NodeKind.Submenu
        // The root is never a click target; it reads as disabled, which is
        // the same guard that drops separators and greyed-out entries.
        layout.nodeOf(0)!!.enabled shouldBe false
    }

    @Test
    fun `properties omit spec defaults and mark the non-default shapes`() {
        val layout = DBusMenuLayout(menu)

        // enabled=true is the dbusmenu default, so it is not emitted.
        layout.propertiesOf(1) shouldContainExactly mapOf(
            "label" to DBusMenuLayout.PropertyValue.Str("Show"),
        )
        layout.propertiesOf(2) shouldContainExactly mapOf(
            "type" to DBusMenuLayout.PropertyValue.Str("separator"),
        )
        layout.propertiesOf(3) shouldContainExactly mapOf(
            "label" to DBusMenuLayout.PropertyValue.Str("Busy"),
            "enabled" to DBusMenuLayout.PropertyValue.Bool(false),
        )
        layout.propertiesOf(4) shouldContainExactly mapOf(
            "label" to DBusMenuLayout.PropertyValue.Str("Servers"),
            "children-display" to DBusMenuLayout.PropertyValue.Str("submenu"),
        )
        // The root carries children-display but no label -- a host that
        // rendered the root's label would show it as a menu entry.
        layout.propertiesOf(0) shouldContainExactly mapOf(
            "children-display" to DBusMenuLayout.PropertyValue.Str("submenu"),
        )
    }

    @Test
    fun `duplicate caller ids stay distinct nodes`() {
        // TrayMenu deliberately does not enforce unique ids (see its init
        // comment), so two items can share one -- they must still be two
        // separate dbusmenu nodes rather than collapse into one.
        val layout = DBusMenuLayout(
            TrayMenu(
                listOf(
                    TrayMenuItem.Standard(id = "same", label = "First"),
                    TrayMenuItem.Standard(id = "same", label = "Second"),
                ),
            ),
        )
        layout.childrenOf(0) shouldContainExactly listOf(1, 2)
        layout.nodeOf(1)!!.label shouldBe "First"
        layout.nodeOf(2)!!.label shouldBe "Second"
        layout.nodeOf(1)!!.originalId shouldBe layout.nodeOf(2)!!.originalId
    }
}
