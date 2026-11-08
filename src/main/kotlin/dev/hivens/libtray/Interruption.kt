package dev.hivens.libtray

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Bounded waits for close() and create(), which have to finish and cannot
// throw. An interrupt, already pending or arriving mid-wait, neither ends the
// wait early nor gets lost: the caller's flag is set again afterwards.

/** Join this thread for up to [timeoutMs]. True when it ended. */
internal fun Thread.joinPastInterrupt(timeoutMs: Long): Boolean =
    waitPastInterrupt(timeoutMs, { !isAlive }) { join(it) }

/** Await this latch for up to [timeoutMs]. True when it reached zero. */
internal fun CountDownLatch.awaitPastInterrupt(timeoutMs: Long): Boolean =
    waitPastInterrupt(timeoutMs, { count == 0L }) { await(it, TimeUnit.MILLISECONDS) }

private fun waitPastInterrupt(timeoutMs: Long, done: () -> Boolean, wait: (remainingMs: Long) -> Unit): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var interrupted = Thread.interrupted()
    try {
        while (!done()) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) break
            try {
                wait(remainingMs)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return done()
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
