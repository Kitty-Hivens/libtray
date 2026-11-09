package dev.hivens.libtray

import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

/**
 * A live system-tray icon owned by this process.
 *
 * Created via [Tray.create]; closed via [close] (or auto-closed if the
 * caller uses `use { }`). All other methods are valid only between
 * creation and close. On a closed Tray the setters do nothing and return
 * false, so a call that races close() can't crash the application.
 *
 * Threading: implementations don't pin the caller to a specific thread.
 * Events are delivered on one event thread per tray that libtray owns, in
 * the order they happened, never on the thread that talks to the platform.
 * A listener that blocks delays later events for that tray but cannot stall
 * the tray host or [close]. To land on a UI thread, register with
 * `onEvent(executor, listener)` and pass the toolkit's executor
 * (`Platform::runLater`, `SwingUtilities::invokeLater`,
 * `Dispatchers.Main.asExecutor()`).
 *
 * No-throw philosophy: the tray is a non-essential UX surface. Every
 * mutating call (`setTooltip` / `setIcon` / `setMenu`) returns boolean
 * success rather than throwing, so a transient backend failure
 * (D-Bus daemon restart, AppIndicator service unregister) degrades the
 * tray to "stale visual" instead of taking down the host application. The
 * one exception is an empty array passed to `setIcon`, a programming error
 * that throws IllegalArgumentException.
 *
 * What a true return means differs by platform. On Windows the icon and
 * tooltip setters report the result of the shell call, and `setMenu` only
 * stores the menu for the next right click. Linux reports that the signal was
 * queued for the tray host. macOS applies the change directly on the Cocoa main
 * thread and queues it from any other, so there it can also mean only that it
 * was queued.
 */
public interface Tray : AutoCloseable {

    /** True between successful construction and [close]. */
    public val isOpen: Boolean

    /**
     * Replace the tooltip shown when the user hovers over the icon. An empty
     * string removes it on Windows and macOS. On Linux the tray host falls
     * back to the title given to [TrayBuilder].
     * @return false when the tray is closed or the change failed. See the
     *   class KDoc for what true means on each platform.
     */
    public fun setTooltip(text: String): Boolean

    /**
     * Replace the icon. PNG works on every platform. Linux and Windows decode
     * through ImageIO, so they take what the JDK reads (PNG, JPEG, GIF, BMP)
     * and not ICO or ICNS. macOS passes the bytes to NSImage, which also takes
     * formats like ICNS. In a GraalVM native image the choice is narrower, see
     * the README.
     *
     * Bytes that do not decode leave the current icon in place on Linux and
     * Windows, which return false. macOS decodes on the main thread, shows a
     * text placeholder instead, and can still return true.
     * @return false when the tray is closed or the change failed. See the
     *   class KDoc for what true means on each platform.
     */
    public fun setIcon(iconBytes: ByteArray): Boolean

    /**
     * Replace the right-click menu. `null` removes the menu (the icon
     * becomes click-only).
     * @return false when the tray is closed or the change failed. See the
     *   class KDoc for what true means on each platform.
     */
    public fun setMenu(menu: TrayMenu?): Boolean

    /**
     * Subscribe to tray events. [listener] runs on this tray's event thread
     * (see class KDoc). Closing the returned [TraySubscription] stops
     * delivery to it.
     */
    public fun onEvent(listener: TrayEventListener): TraySubscription

    /**
     * Subscribe to tray events, each one handed to [executor] instead of run
     * on the event thread. Use it to receive events straight on a UI thread.
     * An executor that rejects a task drops that event with a warning.
     */
    public fun onEvent(executor: Executor, listener: TrayEventListener): TraySubscription

    /**
     * Hide the icon and release backend resources. Idempotent — calling
     * close on an already-closed Tray is safe.
     */
    public override fun close()

    public companion object {
        private val log = LoggerFactory.getLogger(Tray::class.java)

        /**
         * Detect the host OS, instantiate the matching backend, and return
         * a live [Tray]. Returns null when no backend is available — either
         * the OS isn't recognised, the platform's tray service isn't
         * running, or the backend's native libraries can't be resolved.
         * Callers should treat null as "tray UX not available, degrade
         * gracefully" (hide the menu item, don't crash).
         */
        @JvmStatic
        public fun create(builder: TrayBuilder): Tray? {
            val osName = System.getProperty("os.name", "").lowercase()
            val backend: Tray? = runCatching {
                when {
                    osName.contains("linux") || osName.contains("bsd") ->
                        dev.hivens.libtray.linux.SniTrayImpl.create(builder)
                    osName.contains("windows") ->
                        dev.hivens.libtray.windows.Win32TrayImpl.create(builder)
                    osName.contains("mac") || osName.contains("darwin") ->
                        dev.hivens.libtray.macos.AppKitTrayImpl.create(builder)
                    else -> null
                }
            }.onFailure {
                log.warn("Tray backend construction failed", it)
            }.getOrNull()
            return backend.also {
                if (it == null) log.info("Tray unavailable for os.name={}", osName)
            }
        }
    }
}
