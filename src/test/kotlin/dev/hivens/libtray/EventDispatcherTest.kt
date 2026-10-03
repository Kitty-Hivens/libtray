package dev.hivens.libtray

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The threading contract every backend relies on: the backend thread only
 * enqueues, listeners run on the tray's own event thread (or the executor
 * they registered with), in order, and nothing a listener does can hold up
 * the backend or close().
 */
class EventDispatcherTest {

    private val dispatcher = EventDispatcher("libtray-events-test")

    @AfterEach
    fun tearDown() = dispatcher.close()

    @Test
    fun `delivers events in firing order on the event thread`() {
        val seen = ConcurrentLinkedQueue<TrayEvent>()
        val threads = ConcurrentLinkedQueue<String>()
        val done = CountDownLatch(3)
        dispatcher.subscribe({ event ->
            seen.add(event)
            threads.add(Thread.currentThread().name)
            done.countDown()
        })

        dispatcher.fire(TrayEvent.Activated)
        dispatcher.fire(TrayEvent.MenuItemSelected("a"))
        dispatcher.fire(TrayEvent.MiddleActivated)

        done.await(2, TimeUnit.SECONDS) shouldBe true
        seen.toList() shouldContainExactly listOf(
            TrayEvent.Activated, TrayEvent.MenuItemSelected("a"), TrayEvent.MiddleActivated,
        )
        threads.toSet().single() shouldStartWith "libtray-events-test-"
    }

    @Test
    fun `a blocked listener does not block the firing thread or close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        dispatcher.subscribe({
            entered.countDown()
            release.await()
        })

        try {
            dispatcher.fire(TrayEvent.Activated)
            entered.await(2, TimeUnit.SECONDS) shouldBe true

            // The listener is parked. Firing more and closing must both return
            // promptly instead of waiting on it.
            val start = System.nanoTime()
            repeat(100) { dispatcher.fire(TrayEvent.Activated) }
            dispatcher.close()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            (elapsedMs < 500) shouldBe true
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a throwing listener does not stop the others`() {
        val received = CountDownLatch(2)
        dispatcher.subscribe({ throw IllegalStateException("boom") })
        dispatcher.subscribe({ received.countDown() })

        dispatcher.fire(TrayEvent.Activated)
        dispatcher.fire(TrayEvent.Activated)

        received.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `closing a subscription stops delivery to it and is idempotent`() {
        val count = AtomicInteger()
        val first = CountDownLatch(1)
        val subscription = dispatcher.subscribe({
            count.incrementAndGet()
            first.countDown()
        })
        dispatcher.fire(TrayEvent.Activated)
        first.await(2, TimeUnit.SECONDS) shouldBe true

        subscription.close()
        subscription.close()

        val marker = CountDownLatch(1)
        dispatcher.subscribe({ marker.countDown() })
        dispatcher.fire(TrayEvent.Activated)
        marker.await(2, TimeUnit.SECONDS) shouldBe true
        count.get() shouldBe 1
    }

    @Test
    fun `a listener can unsubscribe itself from inside onEvent`() {
        val count = AtomicInteger()
        val seen = CountDownLatch(1)
        lateinit var subscription: TraySubscription
        subscription = dispatcher.subscribe({
            count.incrementAndGet()
            subscription.close()
            seen.countDown()
        })

        dispatcher.fire(TrayEvent.Activated)
        seen.await(2, TimeUnit.SECONDS) shouldBe true

        val marker = CountDownLatch(1)
        dispatcher.subscribe({ marker.countDown() })
        dispatcher.fire(TrayEvent.Activated)
        marker.await(2, TimeUnit.SECONDS) shouldBe true
        count.get() shouldBe 1
    }

    @Test
    fun `an executor listener receives events on that executor`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "ui-thread-stand-in") }
        try {
            val thread = ConcurrentLinkedQueue<String>()
            val done = CountDownLatch(1)
            dispatcher.subscribe({
                thread.add(Thread.currentThread().name)
                done.countDown()
            }, pool)

            dispatcher.fire(TrayEvent.Activated)

            done.await(2, TimeUnit.SECONDS) shouldBe true
            thread.single() shouldBe "ui-thread-stand-in"
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a rejecting executor drops the event without affecting other listeners`() {
        val rejecting = Executor { throw RejectedExecutionException("shut down") }
        val received = CountDownLatch(1)
        dispatcher.subscribe({ error("must not run") }, rejecting)
        dispatcher.subscribe({ received.countDown() })

        dispatcher.fire(TrayEvent.Activated)

        received.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `an executor that throws drops that event and keeps the thread alive`() {
        // Platform::runLater before the toolkit starts throws IllegalStateException.
        val broken = Executor { throw IllegalStateException("Toolkit not initialized") }
        val received = CountDownLatch(2)
        dispatcher.subscribe({ error("must not run") }, broken)
        dispatcher.subscribe({ received.countDown() })

        dispatcher.fire(TrayEvent.Activated)
        dispatcher.fire(TrayEvent.Activated)

        received.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `an interrupt flag left by a listener does not end delivery`() {
        val received = CountDownLatch(2)
        dispatcher.subscribe({
            // The usual idiom after catching InterruptedException.
            Thread.currentThread().interrupt()
            received.countDown()
        })

        dispatcher.fire(TrayEvent.Activated)
        dispatcher.fire(TrayEvent.Activated)

        received.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `nothing is delivered after close`() {
        val count = AtomicInteger()
        dispatcher.subscribe({ count.incrementAndGet() })

        dispatcher.close()
        dispatcher.fire(TrayEvent.Activated)

        Thread.sleep(100)
        count.get() shouldBe 0
    }

    @Test
    fun `events still queued at close are dropped`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        dispatcher.subscribe({
            if (count.incrementAndGet() == 1) {
                entered.countDown()
                release.await()
            }
        })
        try {
            dispatcher.fire(TrayEvent.Activated)
            entered.await(2, TimeUnit.SECONDS) shouldBe true
            repeat(5) { dispatcher.fire(TrayEvent.Activated) }
            dispatcher.close()
        } finally {
            release.countDown()
        }

        Thread.sleep(100)
        count.get() shouldBe 1
    }

    @Test
    fun `executor tasks that have not started by close are dropped`() {
        val pending = ConcurrentLinkedQueue<Runnable>()
        val queued = CountDownLatch(1)
        val count = AtomicInteger()
        dispatcher.subscribe({ count.incrementAndGet() }, Executor { task ->
            pending.add(task)
            queued.countDown()
        })

        dispatcher.fire(TrayEvent.Activated)
        queued.await(2, TimeUnit.SECONDS) shouldBe true
        dispatcher.close()
        pending.forEach { it.run() }

        count.get() shouldBe 0
    }

    @Test
    fun `no thread is started until the first event`() {
        val fresh = EventDispatcher("libtray-events-lazy")
        try {
            fresh.subscribe({ })
            Thread.getAllStackTraces().keys.none { it.name.startsWith("libtray-events-lazy") } shouldBe true
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `event thread exits on close`() {
        val thread = AtomicReference<Thread>()
        val seen = CountDownLatch(1)
        dispatcher.subscribe({
            thread.set(Thread.currentThread())
            seen.countDown()
        })
        dispatcher.fire(TrayEvent.Activated)
        seen.await(2, TimeUnit.SECONDS) shouldBe true
        thread.get().name shouldStartWith "libtray-events-test-"

        dispatcher.close()

        thread.get().join(2_000)
        thread.get().isAlive shouldBe false
    }
}
