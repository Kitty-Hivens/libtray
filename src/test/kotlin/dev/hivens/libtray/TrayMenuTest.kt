package dev.hivens.libtray

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-data sanity for the menu model. The platform backends consume the
 * model verbatim; misshapes here would propagate to every backend.
 */
class TrayMenuTest {

    @Test
    fun `Standard item defaults to enabled`() {
        val item = TrayMenuItem.Standard(id = "show", label = "Show window")
        item.enabled shouldBe true
    }

    @Test
    fun `Submenu rejects empty items list`() {
        // A submenu with no children is a UX bug — backends would render
        // an empty disabled cascade. Catch at construction.
        shouldThrow<IllegalArgumentException> {
            TrayMenuItem.Submenu(id = "empty", label = "Empty", items = emptyList())
        }
    }

    @Test
    fun `Separator carries fixed marker fields`() {
        // The sealed interface requires id / label / enabled; the
        // separator forces them to fixed values. Pin so consumers
        // pattern-matching on `is Separator` don't accidentally read
        // them as meaningful.
        TrayMenuItem.Separator.id shouldBe "---"
        TrayMenuItem.Separator.label shouldBe ""
        TrayMenuItem.Separator.enabled shouldBe false
    }

    @Test
    fun `TrayMenu rejects empty items list`() {
        // null is the "remove menu" sentinel via setMenu; an empty list
        // would be ambiguous and silently render an empty popup.
        shouldThrow<IllegalArgumentException> { TrayMenu(items = emptyList()) }
    }

    @Test
    fun `TrayMenu accepts heterogeneous items`() {
        val menu = TrayMenu(
            items = listOf(
                TrayMenuItem.Standard(id = "show", label = "Show"),
                TrayMenuItem.Separator,
                TrayMenuItem.Submenu(id = "servers", label = "Servers", items = listOf(
                    TrayMenuItem.Standard(id = "industrial", label = "Industrial"),
                    TrayMenuItem.Standard(id = "rpg", label = "RPG"),
                )),
                TrayMenuItem.Separator,
                TrayMenuItem.Standard(id = "exit", label = "Exit"),
            ),
        )
        menu.items shouldHaveSize 5
    }

    @Test
    fun `a snapshot does not follow later changes to the caller's lists`() {
        // A backend keeps the menu past setMenu (until the next right click
        // on Windows, until the main queue gets to it on macOS), so it works
        // on a snapshot rather than on lists the caller can still change.
        val children = mutableListOf<TrayMenuItem>(TrayMenuItem.Standard("eu", "EU"))
        val top = mutableListOf<TrayMenuItem>(
            TrayMenuItem.Standard("show", "Show"),
            TrayMenuItem.Submenu("servers", "Servers", children),
        )
        val snapshot = TrayMenu(top).snapshot() ?: error("no snapshot")

        top.add(TrayMenuItem.Standard("late", "Late"))
        children.add(TrayMenuItem.Standard("us", "US"))

        snapshot.items shouldHaveSize 2
        (snapshot.items[1] as TrayMenuItem.Submenu).items shouldHaveSize 1
    }

    @Test
    fun `a menu whose list was emptied after construction has no snapshot`() {
        // The constructor checked the list while it still had items.
        val top = mutableListOf<TrayMenuItem>(TrayMenuItem.Standard("show", "Show"))
        val menu = TrayMenu(top)
        top.clear()
        menu.snapshot() shouldBe null

        val children = mutableListOf<TrayMenuItem>(TrayMenuItem.Standard("eu", "EU"))
        val nested = TrayMenu(TrayMenuItem.Submenu("servers", "Servers", children))
        children.clear()
        nested.snapshot() shouldBe null
    }
}
