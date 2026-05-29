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
import kotlin.concurrent.thread

/**
 * Sender-thread lifecycle + outgoing-queue drain coverage for the Linux
 * SNI backend (issue #2). No session bus required: [DBusBindings] is
 * replaced with a recording mock whose `handle()` returns MethodHandles
 * bound to JVM methods, and messages are enqueued as recognizable
 * zero-length segments straight into [SniTrayImpl.outgoing].
 *
 * What this pins:
 *  - close() with nothing queued exits the sender and unrefs the
 *    connection exactly once.
 *  - the sender drains in FIFO order, flushing + unrefing each message.
 *  - close() mid-flush lets the in-flight send/flush/unref triplet finish
 *    and then drains the still-queued messages by unref WITHOUT sending.
 *  - concurrent emit from many threads loses nothing and keeps each
 *    thread's messages in submission order on the wire.
 */
class SniTrayImplSenderTest {

    @Test
    fun `close before any signal exits the sender and unrefs the connection once`() {
        val rec = RecordingDbus()
        val tray = newTray(rec)

        tray.close()

        tray.isOpen shouldBe false
        rec.sent.isEmpty() shouldBe true
        rec.flushes.get() shouldBe 0
        rec.connUnrefs.get() shouldBe 1
    }

    @Test
    fun `drains queued messages in FIFO order, flushing and unrefing each`() {
        val rec = RecordingDbus()
        val tray = newTray(rec)
        val addrs = (1L..5L).toList()

        addrs.forEach { tray.outgoing.put(seg(it)) }

        awaitUntil(2_000) { rec.sent.size == addrs.size } shouldBe true
        rec.sent.toList() shouldContainExactly addrs
        rec.unrefed.toList() shouldContainExactly addrs
        rec.flushes.get() shouldBe addrs.size
        tray.close()
    }

    @Test
    fun `close mid-flush finishes the in-flight triplet then drains the rest without sending`() {
        val rec = RecordingDbus()
        val flushEntered = CountDownLatch(1)
        val flushGate = CountDownLatch(1)
        // Block the first (and only) flush so the sender is provably mid-
        // triplet on message 1 while 2 and 3 sit in the queue.
        rec.onFlush = {
            flushEntered.countDown()
            flushGate.await()
        }
        val tray = newTray(rec)

        listOf(1L, 2L, 3L).forEach { tray.outgoing.put(seg(it)) }
        flushEntered.await(2, TimeUnit.SECONDS) shouldBe true   // sender sent m1, is inside flush(m1)

        val closer = thread { tray.close() }
        awaitUntil(2_000) { !tray.isOpen } shouldBe true         // close() flipped open=false
        flushGate.countDown()                                    // release the in-flight flush
        closer.join(3_000)

        rec.sent.toList() shouldContainExactly listOf(1L)            // only m1 ever reached the wire
        rec.flushes.get() shouldBe 1
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

    // ── harness ──────────────────────────────────────────────────────────

    private fun newTray(rec: RecordingDbus): SniTrayImpl =
        SniTrayImpl(
            mockBindings(rec),
            MemorySegment.NULL,
            "test-item",
            // Non-empty (TrayBuilder requires it) but not a real PNG --
            // pngToPixmaps just returns an empty pixmap list on decode
            // failure, and the sender path never touches the icon anyway.
            TrayBuilder(title = "Test", iconBytes = byteArrayOf(1)),
        )

    /**
     * A [DBusBindings] whose `handle()` map is the recording mock. Only the
     * symbols the pump + sender + close touch are bound; the call-site
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

        /** Optional hook run at the start of each flush (mid-flush gating). */
        @Volatile var onFlush: (() -> Unit)? = null

        // Real dbus_connection_read_write blocks up to timeoutMs; sleep a
        // little so the pump loop doesn't busy-spin during the test.
        fun readWrite(connection: MemorySegment, timeoutMs: Int): Int {
            Thread.sleep(20); return 1
        }

        fun popMessage(connection: MemorySegment): MemorySegment = MemorySegment.NULL

        fun send(connection: MemorySegment, msg: MemorySegment, serial: MemorySegment): Int {
            sent.add(msg.address()); return 1
        }

        fun flush(connection: MemorySegment) {
            onFlush?.invoke(); flushes.incrementAndGet()
        }

        fun unref(msg: MemorySegment) {
            unrefed.add(msg.address())
        }

        fun connUnref(connection: MemorySegment) {
            connUnrefs.incrementAndGet()
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
