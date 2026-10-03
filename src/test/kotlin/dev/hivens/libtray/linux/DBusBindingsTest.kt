package dev.hivens.libtray.linux

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena

class DBusBindingsTest {

    @Test
    fun `message iterator scratch is pointer aligned and covers the real struct`() {
        val layout = DBusBindings(Arena.ofAuto(), emptyMap()).messageIterLayout
        layout.byteAlignment() shouldBe 8L
        // sizeof(DBusMessageIter) is 72 on x86_64 and aarch64.
        (layout.byteSize() >= 72L) shouldBe true
    }
}
