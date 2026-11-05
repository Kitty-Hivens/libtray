package dev.hivens.libtray

/**
 * Run [wait], a bounded wait on another thread, with the caller's interrupt
 * flag held back and restored afterwards. close() has to finish its teardown
 * and cannot throw, and an interrupted caller would otherwise get
 * InterruptedException from the first join or await.
 */
internal inline fun waitPastInterrupt(wait: () -> Unit) {
    var interrupted = Thread.interrupted()
    try {
        wait()
    } catch (_: InterruptedException) {
        interrupted = true
    }
    if (interrupted) Thread.currentThread().interrupt()
}
