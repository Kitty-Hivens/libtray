package dev.hivens.libtray

import org.slf4j.LoggerFactory

/**
 * A live system-tray icon owned by this process.
 *
 * Created via [Tray.create]; closed via [close] (or auto-closed if the
 * caller uses `use { }`). All other methods are valid only between
 * creation and close — operating on a closed Tray is a no-op (logged at
 * debug, doesn't throw) so a forgotten close in a finally block can't
 * crash the application.
 *
 * Threading: implementations don't pin the caller to a specific thread.
 * Backend-side event dispatch happens on a thread the backend chooses
 * (D-Bus reader thread on Linux, Win32 message pump thread on Windows,
 * Cocoa main thread on macOS); the registered [onEvent] handler runs on
 * that thread. Consumers wanting to touch UI state should hop themselves
 * (e.g. via `withContext(Dispatchers.Main)`).
 *
 * No-throw philosophy: the tray is a non-essential UX surface. Every
 * mutating call (`setTooltip` / `setIcon` / `setMenu`) returns boolean
 * success rather than throwing, so a transient backend failure
 * (D-Bus daemon restart, AppIndicator service unregister) degrades the
 * tray to "stale visual" instead of taking down the host application.
 */
public interface Tray : AutoCloseable {

    /** True between successful construction and [close]. */
    public val isOpen: Boolean

    /**
     * Replace the tooltip shown when the user hovers over the icon.
     * Empty string is allowed and removes the tooltip.
     * @return true on confirmed success.
     */
    public fun setTooltip(text: String): Boolean

    /**
     * Replace the icon. Bytes must be a format the host supports — PNG
     * is universal; ICO works on Windows; ICNS works on macOS; PNG is
     * always safe. The library does NOT decode the bytes itself, just
     * hands them to the backend.
     * @return true on confirmed success.
     */
    public fun setIcon(iconBytes: ByteArray): Boolean

    /**
     * Replace the right-click menu. `null` removes the menu (the icon
     * becomes click-only).
     * @return true on confirmed success.
     */
    public fun setMenu(menu: TrayMenu?): Boolean

    /**
     * Subscribe to tray events. The handler is invoked on the backend's
     * dispatch thread (see class KDoc). The returned function unsubscribes
     * the handler when called; idempotent.
     */
    public fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit

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
                log.info("Tray backend construction failed: {}", it.message ?: it.javaClass.simpleName)
            }.getOrNull()
            return backend.also {
                if (it == null) log.info("Tray unavailable for os.name={}", osName)
            }
        }
    }
}
