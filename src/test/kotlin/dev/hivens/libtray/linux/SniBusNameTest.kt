package dev.hivens.libtray.linux

import dev.hivens.libtray.TrayBuilder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SniBusNameTest {
    private val icon = byteArrayOf(1)

    @Test
    fun `uses the caller's Linux bus name unchanged`() {
        val name = "com.example.MyApp.StatusNotifierItem"
        val builder = TrayBuilder("MyApp", icon, linuxBusName = name)

        SniTrayImpl.busNameFor(builder) shouldBe name
        SniTrayImpl.busNameFor(builder) shouldBe name
    }

    @Test
    fun `keeps unique PID based names by default`() {
        val builder = TrayBuilder("MyApp", icon)
        val first = SniTrayImpl.busNameFor(builder)
        val second = SniTrayImpl.busNameFor(builder)

        first.startsWith("org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-") shouldBe true
        second.startsWith("org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-") shouldBe true
        (first != second) shouldBe true
    }
}
