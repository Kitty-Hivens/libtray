package dev.hivens.libtray

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// Shared by the per-platform check programs, which also run as GraalVM native
// images and so use neither JUnit nor an assertion library. Exit code 0 means
// every step passed.

internal fun step(what: String) = println("[check] $what")

internal fun fail(why: String): Nothing {
    System.err.println("[check] FAIL: $why")
    exitProcess(1)
}

internal fun expect(events: LinkedBlockingQueue<TrayEvent>, expected: TrayEvent, what: String = expected.toString()) {
    val got = events.poll(5, TimeUnit.SECONDS)
    if (got != expected) fail("$what delivered $got, expected $expected")
    step("$what reached the listener")
}

/** Fail after [seconds] instead of hanging in native code until the CI job times out. */
internal fun startWatchdog(seconds: Long = 90) {
    Thread {
        Thread.sleep(seconds * 1_000)
        System.err.println("[check] FAIL: timed out after $seconds s")
        // Not exitProcess: a shutdown hook could wait on whatever hung.
        Runtime.getRuntime().halt(1)
    }.apply {
        isDaemon = true
        start()
    }
}
