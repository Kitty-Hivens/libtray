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
}
