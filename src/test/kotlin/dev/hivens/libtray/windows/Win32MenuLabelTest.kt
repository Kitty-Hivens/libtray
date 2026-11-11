package dev.hivens.libtray.windows

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Pure string work, so it runs on every OS, not only on Windows like [Win32TrayTest]. */
class Win32MenuLabelTest {

    @Test
    fun `ampersands are doubled so the menu shows them instead of underlining the next letter`() {
        win32MenuLabel("Save & Exit") shouldBe "Save && Exit"
        win32MenuLabel("&&") shouldBe "&&&&"
        win32MenuLabel("Plain") shouldBe "Plain"
    }
}
