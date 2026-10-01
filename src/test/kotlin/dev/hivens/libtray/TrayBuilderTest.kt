package dev.hivens.libtray

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrayBuilderTest {

    private val sampleIcon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)  // PNG magic

    @Test
    fun `requires non-blank title`() {
        shouldThrow<IllegalArgumentException> {
            TrayBuilder(title = "", iconBytes = sampleIcon)
        }
        shouldThrow<IllegalArgumentException> {
            TrayBuilder(title = "   ", iconBytes = sampleIcon)
        }
    }

    @Test
    fun `requires non-empty iconBytes`() {
        shouldThrow<IllegalArgumentException> {
            TrayBuilder(title = "MyApp", iconBytes = ByteArray(0))
        }
    }

    @Test
    fun `accepts a Flatpak app-owned Linux bus name`() {
        val name = "com.example.MyApp.StatusNotifierItem"
        TrayBuilder(title = "MyApp", iconBytes = sampleIcon, linuxBusName = name)
            .linuxBusName shouldBe name
        TrayBuilder(title = "MyApp", iconBytes = sampleIcon).linuxBusName shouldBe null
    }

    @Test
    fun `rejects invalid Linux bus names`() {
        listOf("", "single", ".com.example", "com..example", "com.1example", "com.example.*",
            "com.example with spaces", "com.example.", "com." + "a".repeat(252)).forEach { name ->
            shouldThrow<IllegalArgumentException> {
                TrayBuilder(title = "MyApp", iconBytes = sampleIcon, linuxBusName = name)
            }
        }
    }

    @Test
    fun `keeps the five-argument constructor for Java callers`() {
        val constructor = TrayBuilder::class.java.getConstructor(
            String::class.java, ByteArray::class.java, String::class.java,
            TrayMenu::class.java, Int::class.javaObjectType,
        )
        val builder = constructor.newInstance("MyApp", sampleIcon, null, null, 256)
        builder.linuxBusName shouldBe null
    }

    @Test
    fun `equals compares iconBytes by content`() {
        // Generated equals on a data class with ByteArray uses identity
        // equality, which is wrong for bytes. The override on TrayBuilder
        // uses contentEquals — pin that.
        val a = TrayBuilder(title = "MyApp", iconBytes = byteArrayOf(1, 2, 3))
        val b = TrayBuilder(title = "MyApp", iconBytes = byteArrayOf(1, 2, 3))
        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `equals distinguishes different icon bytes`() {
        val a = TrayBuilder(title = "MyApp", iconBytes = byteArrayOf(1, 2, 3))
        val c = TrayBuilder(title = "MyApp", iconBytes = byteArrayOf(1, 2, 4))
        (a == c) shouldBe false
    }

    @Test
    fun `equals distinguishes different Linux bus names`() {
        val a = TrayBuilder(title = "MyApp", iconBytes = sampleIcon, linuxBusName = "com.example.App.ItemA")
        val b = TrayBuilder(title = "MyApp", iconBytes = sampleIcon, linuxBusName = "com.example.App.ItemB")
        (a == b) shouldBe false
    }
}
