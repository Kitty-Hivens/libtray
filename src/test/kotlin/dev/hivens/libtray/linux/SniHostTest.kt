package dev.hivens.libtray.linux

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.linux.DBusTestClient.Arg
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * End-to-end coverage of the SNI backend against a live session bus, with
 * the test playing the tray host. Runs only in the `sniHostTest` task, which
 * points the JVM at a private bus (see PrivateSessionBus in build.gradle.kts).
 *
 * The latency checks guard the reply path: a property query once took about
 * a second to answer because sends waited out the I/O thread's poll. The
 * bounds are loose enough for a shared CI runner and far below that.
 */
@Tag("sni-host")
class SniHostTest {

    private val host = DBusTestClient()
    private val trays = mutableListOf<Tray>()

    @BeforeEach
    fun requirePrivateBus() {
        // The task is what makes the bus private. Without it this would
        // register test icons on the desktop's real tray.
        check(System.getenv("LIBTRAY_PRIVATE_BUS") == "1") { "run through the sniHostTest task" }
    }

    @AfterEach
    fun tearDown() {
        trays.forEach { it.close() }
        host.close()
    }

    @Test
    fun `exposes the item properties a host reads`() {
        val name = "org.libtray.HostTest.Properties"
        open(name, title = "Host Test")

        host.getStringProperty(name, "Id") shouldBe "hosttest"
        host.getStringProperty(name, "Title") shouldBe "Host Test"
        host.getStringProperty(name, "Status") shouldBe "Active"
        host.getStringProperty(name, "Menu") shouldBe DBusTestClient.MENU_PATH
        host.firstIconPixmapSize(name) shouldBe (32 to 32)
    }

    @Test
    fun `host clicks reach the listener as events`() {
        val name = "org.libtray.HostTest.Clicks"
        val events = LinkedBlockingQueue<TrayEvent>()
        open(name).onEvent { events.add(it) }

        host.callNoResult(name, DBusTestClient.ITEM_PATH, DBusTestClient.ITEM_IFACE, "Activate", xy())
        host.callNoResult(name, DBusTestClient.ITEM_PATH, DBusTestClient.ITEM_IFACE, "SecondaryActivate", xy())
        host.callNoResult(name, DBusTestClient.ITEM_PATH, DBusTestClient.ITEM_IFACE, "ContextMenu", xy())

        listOf(take(events), take(events), take(events)) shouldContainExactly listOf(
            TrayEvent.Activated, TrayEvent.MiddleActivated, TrayEvent.MenuRequested,
        )
    }

    @Test
    fun `menu layout is served and item clicks come back by id`() {
        val name = "org.libtray.HostTest.Menu"
        val events = LinkedBlockingQueue<TrayEvent>()
        open(name).onEvent { events.add(it) }

        // show=1, separator=2, exit=3: dbusmenu ids are assigned depth-first from 1.
        host.menuRootChildCount(name) shouldBe 3
        clickMenu(name, 2)  // separator, must not surface
        clickMenu(name, 3)

        take(events) shouldBe TrayEvent.MenuItemSelected("exit")
        events.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    }

    @Test
    fun `registers with the watcher under its bus name`() {
        DBusTestClient.Watcher().use { watcher ->
            open(linuxBusName = null)
            val registered = watcher.registrations.poll(2, TimeUnit.SECONDS)
            registered.shouldNotBeNull() shouldStartWith "org.kde.StatusNotifierItem-"
        }
    }

    @Test
    fun `registers again when the watcher comes back`() {
        val name = "org.libtray.HostTest.Reregister"
        DBusTestClient.Watcher().use { first ->
            open(name)
            first.registrations.poll(2, TimeUnit.SECONDS) shouldBe name
        }
        // A tray host restart: the old watcher is gone, a fresh one starts empty.
        DBusTestClient.Watcher().use { second ->
            second.registrations.poll(3, TimeUnit.SECONDS) shouldBe name
        }
    }

    @Test
    fun `answers the host within the latency budget`() {
        val name = "org.libtray.HostTest.Latency"
        val delivered = LinkedBlockingQueue<Long>()
        open(name).onEvent { if (it == TrayEvent.Activated) delivered.add(System.nanoTime()) }

        val getAll = medianMillis {
            host.call(name, DBusTestClient.ITEM_PATH, "org.freedesktop.DBus.Properties", "GetAll",
                listOf(Arg.Str(DBusTestClient.ITEM_IFACE))) { _, _ -> }
        }
        val getLayout = medianMillis { host.menuRootChildCount(name) }
        val activate = medianMillis {
            val start = System.nanoTime()
            host.callNoResult(name, DBusTestClient.ITEM_PATH, DBusTestClient.ITEM_IFACE, "Activate", xy())
            val at = delivered.poll(2, TimeUnit.SECONDS) ?: error("Activated never arrived")
            (at - start) / 1_000_000.0
        }

        println("[sni-host] median latency: GetAll %.1f ms, GetLayout %.1f ms, Activate to listener %.1f ms"
            .format(getAll, getLayout, activate))
        (getAll < LATENCY_BUDGET_MS) shouldBe true
        (getLayout < LATENCY_BUDGET_MS) shouldBe true
        (activate < LATENCY_BUDGET_MS) shouldBe true
    }

    @Test
    fun `close stops the I O thread even while the bus is not reading`() {
        val tray = open("org.libtray.HostTest.Wedged")
        val pid = System.getenv("LIBTRAY_PRIVATE_BUS_PID") ?: error("no bus daemon pid")
        signal("STOP", pid)
        try {
            // Each call queues two signals. With the daemon stopped nothing
            // drains the socket, so its buffer fills well before this ends.
            repeat(20_000) { tray.setTooltip("state $it") }
            Thread.sleep(300)

            val start = System.nanoTime()
            tray.close()
            val tookMs = (System.nanoTime() - start) / 1_000_000
            val ioThread = SniTrayImpl::class.java.getDeclaredField("ioThread")
                .apply { isAccessible = true }.get(tray) as Thread
            println("[sni-host] close() with the bus stopped took $tookMs ms")
            ioThread.isAlive shouldBe false
        } finally {
            signal("CONT", pid)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun open(linuxBusName: String?, title: String = "libtray host test"): Tray {
        val tray = Tray.create(
            TrayBuilder(
                title = title,
                iconBytes = icon32(),
                menu = TrayMenu(
                    TrayMenuItem.Standard("show", "Show"),
                    TrayMenuItem.Separator,
                    TrayMenuItem.Standard("exit", "Exit"),
                ),
                linuxBusName = linuxBusName,
            ),
        ) ?: error("Tray.create returned null on the private bus")
        trays += tray
        return tray
    }

    private fun signal(name: String, pid: String) {
        val exit = ProcessBuilder("kill", "-$name", pid).inheritIO().start().waitFor()
        check(exit == 0) { "kill -$name $pid failed" }
    }

    private fun clickMenu(name: String, id: Int) =
        host.callNoResult(name, DBusTestClient.MENU_PATH, "com.canonical.dbusmenu", "Event",
            listOf(Arg.Int32(id), Arg.Str("clicked"), Arg.VariantInt32(0), Arg.UInt32(0)))

    private fun xy() = listOf(Arg.Int32(0), Arg.Int32(0))

    private fun take(events: LinkedBlockingQueue<TrayEvent>): TrayEvent =
        events.poll(2, TimeUnit.SECONDS) ?: error("no event within 2 s")

    /** Median of a warmed-up run, in milliseconds. [block] may return its own measurement. */
    private fun medianMillis(block: () -> Any?): Double {
        repeat(3) { block() }
        val samples = (1..15).map {
            val start = System.nanoTime()
            val own = block()
            own as? Double ?: ((System.nanoTime() - start) / 1_000_000.0)
        }.sorted()
        return samples[samples.size / 2]
    }

    private fun icon32(): ByteArray {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
    }

    private companion object {
        const val LATENCY_BUDGET_MS = 250.0
    }
}
