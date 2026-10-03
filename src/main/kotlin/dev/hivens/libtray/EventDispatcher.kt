package dev.hivens.libtray

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands [TrayEvent]s from a backend to the consumer's listeners on a thread
 * libtray owns, one per tray.
 *
 * Backends call [fire] from wherever the platform delivers the click: the
 * D-Bus I/O thread, the Win32 message pump, the Cocoa main thread. [fire]
 * only enqueues, so a listener that blocks holds up later events for that
 * tray and nothing else. The backend thread keeps answering the tray host,
 * and close() never waits on consumer code.
 *
 * Events reach each listener in the order they were fired. A listener
 * registered with an [Executor] gets each event submitted to that executor
 * instead of being called on the event thread, so ordering on that path is
 * whatever the executor guarantees (FIFO for the usual UI-thread executors).
 */
internal class EventDispatcher(threadName: String) {

    private class Registration(val listener: TrayEventListener, val executor: Executor?) {
        val active = AtomicBoolean(true)
    }

    /** Queue element that stops the event thread. Not a [TrayEvent], so no listener ever sees it. */
    private object Stop

    private val log = LoggerFactory.getLogger("libtray.Events")
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val queue = LinkedBlockingQueue<Any>()
    private val open = AtomicBoolean(true)

    private val thread = Thread({ run() }, threadName).apply {
        isDaemon = true
        start()
    }

    fun subscribe(listener: TrayEventListener, executor: Executor? = null): TraySubscription {
        val registration = Registration(listener, executor)
        registrations.add(registration)
        return object : TraySubscription {
            override fun close() {
                if (registration.active.compareAndSet(true, false)) registrations.remove(registration)
            }
        }
    }

    fun fire(event: TrayEvent) {
        if (open.get()) queue.put(event)
    }

    /**
     * Stop delivering. Events still queued are dropped, and so are executor
     * submissions that have not started yet. Does not wait for a listener
     * that is running right now: that is consumer code, and waiting on it
     * is what this class exists to avoid.
     */
    fun close() {
        if (!open.compareAndSet(true, false)) return
        queue.clear()
        queue.put(Stop)
    }

    private fun run() {
        while (true) {
            val next = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            if (next === Stop) return
            deliver(next as TrayEvent)
        }
    }

    private fun deliver(event: TrayEvent) {
        for (registration in registrations) {
            if (!open.get()) return
            val executor = registration.executor
            if (executor == null) {
                invoke(registration, event)
                continue
            }
            try {
                executor.execute { if (open.get()) invoke(registration, event) }
            } catch (e: RejectedExecutionException) {
                log.warn("Executor rejected a tray event, dropping {}: {}", event, e.message)
            }
        }
    }

    private fun invoke(registration: Registration, event: TrayEvent) {
        if (!registration.active.get()) return
        try {
            registration.listener.onEvent(event)
        } catch (t: Throwable) {
            log.warn("onEvent listener threw on {}", event, t)
        }
    }
}
