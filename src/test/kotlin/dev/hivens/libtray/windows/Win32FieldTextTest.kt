package dev.hivens.libtray.windows

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Pure string work, so it runs on every OS, not only on Windows like [Win32TrayTest]. */
class Win32FieldTextTest {

    @Test
    fun `text that fits is kept whole`() {
        win32FieldText("tooltip", 128) shouldBe "tooltip"
        win32FieldText("a".repeat(127), 128) shouldBe "a".repeat(127)
    }

    @Test
    fun `longer text is cut to leave room for the terminator`() {
        win32FieldText("a".repeat(200), 128) shouldBe "a".repeat(127)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        // The emoji takes code units 126 and 127, so a cut at 127 would keep
        // its high half alone, which the shell draws as a stray glyph.
        val text = "a".repeat(126) + "😀" + "tail"
        win32FieldText(text, 128) shouldBe "a".repeat(126)
    }
}
