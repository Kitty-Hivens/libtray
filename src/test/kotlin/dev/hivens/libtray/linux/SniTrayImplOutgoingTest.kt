package dev.hivens.libtray.linux

import dev.hivens.libtray.TrayBuilder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * I/O-thread lifecycle + outgoing-queue drain coverage for the Linux SNI
 * backend (issue #2). No session bus required: [DBusBindings] is replaced
 * with a recording mock whose `handle()` returns MethodHandles bound to JVM
 * methods, and messages are enqueued as recognizable zero-length segments
 * straight into [SniTrayImpl.outgoing].
 *
 * What this pins:
 *  - close() with nothing queued exits the loop and unrefs the connection
 *    exactly once.
 *  - the loop drains in FIFO order, sending + unrefing each message and
 *    never calling dbus_connection_flush, which can block forever against a
 *    bus that stopped reading.
 *  - close() mid-send lets the in-flight send/unref pair finish and then
 *    drains the still-queued messages by unref WITHOUT sending.
 *  - concurrent emit from many threads loses nothing and keeps each
 *    thread's messages in submission order on the wire.
 *  - a message queued during an iteration is sent before the loop blocks
 *    in the next poll, and poll + send all run on one thread.
 *    Those last two are the reply-latency fix: libdbus holds the
 *    connection's io path for the whole of `dbus_connection_read_write`,
 *    so a write from any other thread had to wait out the poll interval.
 */
class SniTrayImplOutgoingTest {

    @Test
    fun `close before any signal exits the loop and unrefs the connection once`() {
        val rec = RecordingDbus()
        val tray = newTray(rec)

        tray.close()

        tray.isOpen shouldBe false
        rec.sent.isEmpty() shouldBe true
        rec.flushes.get() shouldBe 0
        rec.connUnrefs.get() shouldBe 1
        // The private connection is closed exactly once, before the unref.
        rec.connCloses.get() shouldBe 1
        rec.connLifecycle.toList() shouldContainExactly listOf("close", "unref")
    }

    @Test
    fun `drains queued messages in FIFO order, sending and unrefing each without flushing`() {
        val rec = RecordingDbus()
        val tray = newTray(rec)
        val addrs = (1L..5L).toList()

        addrs.forEach { tray.outgoing.put(seg(it)) }

        // Wait on the unref, not the send: unref comes after the send, so
        // waiting on `sent` can observe the final message between the two
        // and read a short `unrefed` below.
        awaitUntil(2_000) { rec.unrefed.size == addrs.size } shouldBe true
        rec.sent.toList() shouldContainExactly addrs
        rec.unrefed.toList() shouldContainExactly addrs
        rec.flushes.get() shouldBe 0
        tray.close()
    }

    @Test
    fun `close mid-send finishes the in-flight send then drains the rest without sending`() {
        val rec = RecordingDbus()
        val sendEntered = CountDownLatch(1)
        val sendGate = CountDownLatch(1)
        // Block the first send so the loop is provably mid-send on message 1
        // while 2 and 3 sit in the queue.
        rec.onSend = {
            sendEntered.countDown()
            sendGate.await()
        }
        val tray = newTray(rec)

        listOf(1L, 2L, 3L).forEach { tray.outgoing.put(seg(it)) }
        sendEntered.await(2, TimeUnit.SECONDS) shouldBe true    // inside send(m1)

        val closer = thread { tray.close() }
        awaitUntil(2_000) { !tray.isOpen } shouldBe true         // close() flipped open=false
        sendGate.countDown()                                     // release the in-flight send
        closer.join(3_000)

        rec.sent.toList() shouldContainExactly listOf(1L)            // only m1 was handed to libdbus
        rec.flushes.get() shouldBe 0
        rec.unrefed.toList() shouldContainExactly listOf(1L, 2L, 3L) // m1 in-flight, m2/m3 final-drain
        rec.connUnrefs.get() shouldBe 1
    }

    @Test
    fun `concurrent emit from many threads loses nothing and keeps per-thread order`() {
        val rec = RecordingDbus()
        val tray = newTray(rec)
        val threads = 4
        val perThread = 25
        val start = CountDownLatch(1)

        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                for (s in 0 until perThread) tray.outgoing.put(seg(encode(t, s)))
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        val total = threads * perThread
        awaitUntil(3_000) { rec.sent.size == total } shouldBe true
        rec.sent.toSet().size shouldBe total                         // nothing dropped or duplicated

        val order = rec.sent.toList()
        for (t in 0 until threads) {
            val seqForThread = order.filter { threadOf(it) == t }.map { seqOf(it) }
            seqForThread shouldContainExactly (0 until perThread).toList()  // FIFO within each thread
        }
        tray.close()
    }

    /**
     * The reply-latency regression pin. A message queued while the loop sits
     * in a poll -- which is what answering an incoming method call amounts
     * to -- must go out before the loop enters the *next* poll, not after
     * it. Asserting on the operation log rather than on elapsed time keeps
     * this independent of the poll timeout's actual value.
     */
    @Test
    fun `a message queued during a poll is sent before the next poll`() {
        val rec = RecordingDbus()
        val trayRef = AtomicReference<SniTrayImpl>()
        val constructed = CountDownLatch(1)
        val polls = AtomicInteger(0)
        rec.onReadWrite = {
            // Wait for the constructor to return so trayRef is populated --
            // the loop starts from SniTrayImpl's init block and can reach
            // this hook first. Only the first poll queues, so the log holds
            // one unambiguous send.
            constructed.await()
            if (polls.incrementAndGet() == 1) trayRef.get().outgoing.put(seg(1))
        }
        val tray = newTray(rec)
        trayRef.set(tray)
        constructed.countDown()

        awaitUntil(2_000) { rec.events.contains("send:1") } shouldBe true
        rec.events.toList().take(3) shouldContainExactly listOf("poll", "send:1", "poll")
        tray.close()
    }

    @Test
    fun `polling and sending both happen on the single I O thread`() {
        val rec = RecordingDbus()
        val tray = newTray(rec)

        (1L..3L).forEach { tray.outgoing.put(seg(it)) }
        awaitUntil(2_000) { rec.sent.size == 3 } shouldBe true

        rec.ioThreads.toSet() shouldBe setOf("libtray-sni-${ProcessHandle.current().pid()}")
        tray.close()
    }

    // ── harness ──────────────────────────────────────────────────────────

    private fun newTray(rec: RecordingDbus): SniTrayImpl =
        SniTrayImpl(
            mockBindings(rec),
            MemorySegment.NULL,
            "test-item",
            // Non-empty (TrayBuilder requires it) but not a real PNG --
            // pngToPixmaps just returns an empty pixmap list on decode
            // failure, and the outgoing path never touches the icon anyway.
            TrayBuilder(title = "Test", iconBytes = byteArrayOf(1)),
        )

    /**
     * A [DBusBindings] whose `handle()` map is the recording mock. Only the
     * symbols the I/O loop + close touch are bound; the call-site
     * MethodTypes must match the libdbus descriptors exactly, since
     * `invokeExact` is strict.
     */
    private fun mockBindings(rec: RecordingDbus): DBusBindings {
        val l = MethodHandles.lookup()
        val seg = MemorySegment::class.java
        val int = Int::class.javaPrimitiveType!!
        val void = Void.TYPE
        val handles = mapOf(
            "dbus_connection_read_write" to l.bind(rec, "readWrite", MethodType.methodType(int, seg, int)),
            "dbus_connection_pop_message" to l.bind(rec, "popMessage", MethodType.methodType(seg, seg)),
            "dbus_connection_send" to l.bind(rec, "send", MethodType.methodType(int, seg, seg, seg)),
            "dbus_connection_flush" to l.bind(rec, "flush", MethodType.methodType(void, seg)),
            "dbus_message_unref" to l.bind(rec, "unref", MethodType.methodType(void, seg)),
            "dbus_connection_close" to l.bind(rec, "connClose", MethodType.methodType(void, seg)),
            "dbus_connection_unref" to l.bind(rec, "connUnref", MethodType.methodType(void, seg)),
        )
        return DBusBindings(Arena.ofShared(), handles)
    }

    // Methods are invoked reflectively (MethodHandles.bind in mockBindings),
    // and the unused params exist only to match the native call-site
    // signatures invokeExact checks against -- so the IDE's "never used"
    // reports here are expected.
    @Suppress("unused", "UNUSED_PARAMETER")
    private class RecordingDbus {
        val sent = ConcurrentLinkedQueue<Long>()
        val unrefed = ConcurrentLinkedQueue<Long>()
        val flushes = AtomicInteger(0)
        val connUnrefs = AtomicInteger(0)
        val connCloses = AtomicInteger(0)

        /** Ordered log of the socket-facing calls, for the latency pin. */
        val events = ConcurrentLinkedQueue<String>()

        /** Names of the threads that made those calls. */
        val ioThreads = ConcurrentLinkedQueue<String>()

        /** Records "close"/"unref" so the test can pin their relative order. */
        val connLifecycle = ConcurrentLinkedQueue<String>()

        /** Optional hook run at the start of each send (mid-send gating). */
        @Volatile var onSend: (() -> Unit)? = null

        /** Optional hook run inside each poll, standing in for an arriving call. */
        @Volatile var onReadWrite: (() -> Unit)? = null

        // Real dbus_connection_read_write blocks up to timeoutMs; sleep a
        // little so the loop doesn't busy-spin during the test.
        fun readWrite(connection: MemorySegment, timeoutMs: Int): Int {
            record("poll")
            onReadWrite?.invoke()
            Thread.sleep(20)
            return 1
        }

        fun popMessage(connection: MemorySegment): MemorySegment = MemorySegment.NULL

        fun send(connection: MemorySegment, msg: MemorySegment, serial: MemorySegment): Int {
            onSend?.invoke()
            record("send:${msg.address()}")
            sent.add(msg.address())
            return 1
        }

        // Still bound so a flush, if one came back, would be counted rather
        // than fail the lookup.
        fun flush(connection: MemorySegment) {
            record("flush")
            flushes.incrementAndGet()
        }

        fun unref(msg: MemorySegment) {
            unrefed.add(msg.address())
        }

        fun connClose(connection: MemorySegment) {
            connCloses.incrementAndGet(); connLifecycle.add("close")
        }

        fun connUnref(connection: MemorySegment) {
            connUnrefs.incrementAndGet(); connLifecycle.add("unref")
        }

        private fun record(event: String) {
            events.add(event)
            ioThreads.add(Thread.currentThread().name)
        }
    }

    // Recognizable, non-zero, collision-free message "pointers".
    private fun seg(addr: Long): MemorySegment = MemorySegment.ofAddress(addr)
    private fun encode(thread: Int, seq: Int): Long = (thread + 1) * 10_000L + seq
    private fun threadOf(addr: Long): Int = (addr / 10_000L).toInt() - 1
    private fun seqOf(addr: Long): Int = (addr % 10_000L).toInt()

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
}
