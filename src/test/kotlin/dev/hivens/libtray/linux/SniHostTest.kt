package dev.hivens.libtray.linux

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.linux.DBusTestClient.Arg
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import dev.hivens.libtray.solidPng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
    fun `setIcon with undecodable bytes reports failure and keeps the current icon`() {
        val name = "org.libtray.HostTest.UndecodableIcon"
        val tray = open(name)

        tray.setIcon(byteArrayOf(1, 2, 3)) shouldBe false

        host.firstIconPixmapSize(name) shouldBe (32 to 32)
    }

    @Test
    fun `setters work from a thread with its interrupt flag set`() {
        val tray = open("org.libtray.HostTest.Interrupted")

        Thread.currentThread().interrupt()
        val result = tray.setTooltip("while interrupted")

        Thread.interrupted() shouldBe true
        result shouldBe true
    }

    @Test
    fun `setters racing close never throw`() {
        repeat(20) { round ->
            val tray = open("org.libtray.HostTest.CloseRace$round")
            val failure = AtomicReference<Throwable?>()
            val started = CountDownLatch(1)
            val setter = thread {
                try {
                    while (tray.isOpen) {
                        tray.setTooltip("racing")
                        tray.setMenu(null)
                        started.countDown()
                    }
                    tray.setTooltip("after close") shouldBe false
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            // close() has to land while the setter is looping, not before it starts.
            started.await(2, TimeUnit.SECONDS) shouldBe true
            tray.close()
            setter.join(2_000)
            setter.isAlive shouldBe false
            failure.get() shouldBe null
        }
    }

    @Test
    fun `menu clicks in an EventGroup come back by id`() {
        val name = "org.libtray.HostTest.EventGroup"
        val events = LinkedBlockingQueue<TrayEvent>()
        open(name).onEvent { events.add(it) }

        callEventGroup(name, listOf(Arg.MenuClicks(emptyList())))
        callEventGroup(name, listOf(Arg.MenuClicks(listOf(1, 2, 3))))

        // Item 2 is the separator, which must not surface.
        take(events) shouldBe TrayEvent.MenuItemSelected("show")
        take(events) shouldBe TrayEvent.MenuItemSelected("exit")
        events.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    }

    @Test
    fun `a malformed EventGroup gets an error and the item keeps serving`() {
        val name = "org.libtray.HostTest.MalformedEventGroup"
        open(name)

        for (args in listOf(emptyList(), listOf(Arg.Int32(1)), listOf(Arg.Int32Array(listOf(1))))) {
            shouldThrow<IllegalStateException> { callEventGroup(name, args) }
                .message.shouldContain("InvalidArgs")
        }

        host.getStringProperty(name, "Status") shouldBe "Active"
    }

    @Test
    fun `property and menu calls with missing arguments get InvalidArgs`() {
        val name = "org.libtray.HostTest.MissingArguments"
        open(name)

        // An empty method return would carry the wrong signature for Get and GetProperty.
        val calls = listOf(
            Triple(DBusTestClient.ITEM_PATH, "org.freedesktop.DBus.Properties", "Get"),
            Triple(DBusTestClient.ITEM_PATH, "org.freedesktop.DBus.Properties", "GetAll"),
            Triple(DBusTestClient.MENU_PATH, "com.canonical.dbusmenu", "GetProperty"),
            Triple(DBusTestClient.MENU_PATH, "com.canonical.dbusmenu", "Event"),
        )
        for ((path, iface, member) in calls) {
            shouldThrow<IllegalStateException> { host.callNoResult(name, path, iface, member) }
                .message.shouldContain("InvalidArgs")
        }

        host.getStringProperty(name, "Status") shouldBe "Active"
    }

    @Test
    fun `a click on an item of a replaced menu is dropped`() {
        val name = "org.libtray.HostTest.ReplacedMenu"
        val events = LinkedBlockingQueue<TrayEvent>()
        val tray = open(name)
        tray.onEvent { events.add(it) }

        tray.setMenu(TrayMenu(TrayMenuItem.Standard("a", "A"), TrayMenuItem.Standard("b", "B"), TrayMenuItem.Standard("c", "C")))

        // Id 3 was "exit" in the menu a host may still have on screen.
        clickMenu(name, 3)
        events.poll(200, TimeUnit.MILLISECONDS) shouldBe null

        clickMenu(name, 4)
        take(events) shouldBe TrayEvent.MenuItemSelected("a")
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
                iconBytes = solidPng(32),
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

    private fun callEventGroup(name: String, args: List<Arg>) =
        host.callNoResult(name, DBusTestClient.MENU_PATH, "com.canonical.dbusmenu", "EventGroup", args)

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


    private companion object {
        const val LATENCY_BUDGET_MS = 250.0
    }
}
