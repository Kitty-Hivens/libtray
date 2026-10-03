package dev.hivens.libtray

/**
 * Receives [TrayEvent]s from a [Tray]. Registered via [Tray.onEvent].
 *
 * A `fun interface` so a Kotlin lambda and a Java lambda both convert to it
 * directly, with no `Unit` to return from the Java side.
 */
public fun interface TrayEventListener {
    public fun onEvent(event: TrayEvent)
}

/**
 * Handle returned by [Tray.onEvent]. [close] stops delivery to the listener
 * it was returned for. Idempotent, and safe to call from inside the listener
 * itself.
 *
 * Overrides [AutoCloseable.close] without a checked exception, so Java code
 * can call it, or use it in try-with-resources, without a `catch`.
 */
public interface TraySubscription : AutoCloseable {
    override fun close()
}
