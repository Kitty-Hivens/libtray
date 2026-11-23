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
    fun `the SNI Id keeps letters and digits of any script`() {
        // Latin titles keep the Id they always had, so hosts that store
        // settings per Id do not lose them.
        SniTrayImpl.slugifyForSni("Nexira Launcher 2") shouldBe "nexiralauncher2"
        SniTrayImpl.slugifyForSni("Мой Трей") shouldBe "мойтрей"
        SniTrayImpl.slugifyForSni("トレイ") shouldBe "トレイ"
        SniTrayImpl.slugifyForSni("🚀 !") shouldBe "tray"
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
