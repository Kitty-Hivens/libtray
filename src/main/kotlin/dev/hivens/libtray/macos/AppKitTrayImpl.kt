package dev.hivens.libtray.macos

import dev.hivens.libtray.EventDispatcher
import dev.hivens.libtray.IconScaling
import dev.hivens.libtray.MenuItemKey
import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayEventListener
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.TraySubscription
import dev.hivens.libtray.UpcallTarget
import dev.hivens.libtray.awaitPastInterrupt
import dev.hivens.libtray.menuItemKeys
import dev.hivens.libtray.snapshot
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * macOS tray backend on top of `NSStatusBar` / `NSStatusItem` via the
 * Objective-C runtime. Project Panama only — no JNI, no Foundation Kit
 * bridging dep, no Swift. The `objc_msgSend` family in [ObjcBindings]
 * is our entire bridge into Cocoa.
 *
 * Structural mirror of [dev.hivens.libtray.linux.SniTrayImpl] and
 * [dev.hivens.libtray.windows.Win32TrayImpl]:
 *
 *   1. Bind [ObjcBindings] (libobjc + AppKit + Foundation).
 *   2. Lazily register one custom Objective-C target class per JVM,
 *      `LibtrayMenuTarget`, with `-onMenuItem:` and `-onStatusItemClick:`,
 *      each IMP a Panama upcall stub. Cocoa target-action requires an
 *      object, not a function pointer; this is the canonical bridge.
 *   3. Build the `NSStatusItem` via `[[NSStatusBar systemStatusBar]
 *      statusItemWithLength:NSSquareStatusItemLength]`. Retain it
 *      so it lives past the autorelease pool.
 *   4. Install initial icon (PNG → NSData → NSImage → button.image)
 *      and tooltip (button.toolTip).
 *   5. One NSMenu kept for the tray and updated in place on every
 *      [setMenu], an NSMenuItem per [MenuItemKey], with target-action wired
 *      to our shared LibtrayMenuTarget instance + `onMenuItem:` selector.
 *      Each item carries its libtray id as `representedObject` and the
 *      tray's instance id as its tag, which is all the upcall handler reads.
 *   6. A transparent `LibtrayStatusView` is laid over the status button
 *      and receives its mouse events, each with the `NSEvent` as an
 *      argument, so the button and modifiers are read from the click
 *      itself (see [handleStatusViewMouse]). The menu is not left attached
 *      to the status item, since an attached menu makes AppKit take the
 *      click: [popUpMenu] attaches it only for one `performClick:`. The
 *      button's own target-action stays for presses that do not come from
 *      the mouse, such as VoiceOver.
 *
 * **Threading.** [create] off the main thread runs the creation on the Cocoa
 * main thread and waits for it (see [createThroughMainQueue]), so an AWT, Swing
 * or Compose app can call it from any thread once its UI is up. Mutating calls
 * (setIcon / setTooltip / setMenu) go to the same queue of main thread actions
 * (see [enqueueOnMainQueue]): a call already on the main thread runs inline,
 * any other thread enqueues. AppKit therefore always runs where it wants
 * regardless of which thread the consumer calls from, and the public setters
 * still return as soon as the enqueue succeeds. The
 * `onMenuItem:`, `onStatusItemClick:` and status view mouse upcalls already
 * arrive on the main thread by AppKit's contract. [close] queues its teardown
 * on the main queue too, but waits for it, so the icon is gone when close()
 * returns (see [tearDownOnMainThread]).
 */
internal class AppKitTrayImpl private constructor(
    private val bindings: ObjcBindings,
    private val statusItem: MemorySegment,   // NSStatusItem* (retained)
    private val statusButton: MemorySegment, // NSStatusBarButton* (owned by statusItem)
    private val instanceId: Int,
    initial: TrayBuilder,
    /** [initial]'s icon, already scaled by the caller so the main thread does not have to. */
    initialIcon: ByteArray,
) : Tray {

    private val log = LoggerFactory.getLogger("libtray.AppKitTray")

    private val open = AtomicBoolean(true)

    /** Set when [tearDown] starts. Main thread, read by the macOS check from others. */
    private val tornDown = AtomicBoolean(false)
    private val events = EventDispatcher("libtray-events-${ProcessHandle.current().pid()}")

    /**
     * The NSMenu and NSMenuItems the tray keeps between [setMenu] calls, one
     * per [MenuItemKey], or null with no menu. [syncItems] changes them in
     * place, so an item that is still in the menu stays the same object and a
     * menu on screen updates instead of going stale. Each item carries its
     * libtray id as its `representedObject` and this tray's [instanceId] as
     * its tag, so a click names its own item even when setMenu removed it
     * while the menu was open. Main thread only.
     */
    private var menuModel: ShownMenu? = null

    /** The root NSMenu of [menuModel], or NULL when no menu set. */
    @Volatile private var currentMenu: MemorySegment = MemorySegment.NULL

    /** For the macOS check, which drives the native objects directly. */
    internal val currentMenuHandle: MemorySegment get() = currentMenu
    internal val statusButtonHandle: MemorySegment get() = statusButton
    internal val statusViewHandle: MemorySegment get() = statusView
    internal val isTornDown: Boolean get() = tornDown.get()

    /**
     * For the macOS check: `[statusButton toolTip]`, read on the main queue
     * behind every setter queued before it, or null when the tooltip is unset
     * or the queue did not get to the read in time.
     */
    internal fun shownTooltip(): String? {
        var shown: String? = null
        val done = CountDownLatch(1)
        val read = {
            try {
                autoreleasepool {
                    val msgSendId = bindings.handle("objc_msgSend_id")
                    val tooltip = msgSendId.invokeExact(statusButton, bindings.sel("toolTip")) as MemorySegment
                    if (tooltip.address() != 0L) {
                        val utf8 = msgSendId.invokeExact(tooltip, bindings.sel("UTF8String")) as MemorySegment
                        shown = utf8.reinterpret(Long.MAX_VALUE).getString(0)
                    }
                }
            } finally {
                done.countDown()
            }
        }
        if (isCocoaMainThread(bindings)) read() else if (!enqueueOnMainQueue(read)) return null
        return if (done.awaitPastInterrupt(CLOSE_ON_MAIN_TIMEOUT_MS)) shown else null
    }

    /** See [dev.hivens.libtray.TrayBuilder.maxIconSize]; null disables scaling. */
    private val maxIconSize: Int? = initial.maxIconSize

    /** See [dev.hivens.libtray.TrayBuilder.subsampleOversizedIcons]. */
    private val subsampleOversized: Boolean = initial.subsampleOversizedIcons

    /** See [dev.hivens.libtray.TrayBuilder.macosMenuOnPrimaryClick]. */
    private val menuOnPrimaryClick: Boolean = initial.macosMenuOnPrimaryClick

    /**
     * True while [popUpMenu] is inside `performClick:`. Main thread only. A
     * click action arriving then is the simulated click itself, and handling
     * it would open the menu again from inside its own tracking loop.
     */
    private var menuOpen = false

    /** The menu [popUpMenu] has on screen, for [tearDown] to cancel. Main thread only. */
    private var shownMenu: MemorySegment = MemorySegment.NULL

    /** The click overlay over [statusButton] (alloc +1, ours), or NULL. Main thread only. */
    private var statusView: MemorySegment = MemorySegment.NULL

    /** A primary press that did not open the menu, so its release fires Activated. Main thread only. */
    private var primaryPressPending = false

    /**
     * [System.nanoTime] of the last mouse event the overlay handled, or of
     * the menu closing. Main thread only. See [handleStatusItemAction].
     */
    private var lastOverlayActivityNanos = 0L

    init {
        INSTANCE_REGISTRY[instanceId] = this
        installClickHandler()
        // Belt-and-suspenders: set a short text title FIRST so even if
        // the image pipeline silently fails (NSImage decoded fine but
        // SystemUIServer drops it), the menu bar shows SOMETHING. The
        // title gets overwritten visually by setImage if the image
        // does succeed; otherwise it stays as the visible fallback.
        applyTitleFallback("●")
        if (initialIcon.isNotEmpty()) showIcon(initialIcon)
        initial.tooltip?.takeIf { it.isNotEmpty() }?.let { applyTooltip(it) }
        initial.menu?.snapshot()?.let { applyMenu(it) }
    }

    override val isOpen: Boolean get() = open.get()

    override fun setIcon(iconBytes: ByteArray): Boolean {
        if (!open.get()) return false
        // Checked here, since NSImage would turn no bytes into the text
        // placeholder and report success.
        if (iconBytes.isEmpty()) {
            log.warn("setIcon got no bytes, keeping the current icon")
            return false
        }
        return runCatching { applyIcon(iconBytes) }.getOrElse { t ->
            log.warn("setIcon threw", t); false
        }
    }

    override fun setTooltip(text: String): Boolean {
        if (!open.get()) return false
        return runCatching { applyTooltip(text); true }.getOrElse { t ->
            log.warn("setTooltip threw", t); false
        }
    }

    override fun setMenu(menu: TrayMenu?): Boolean {
        if (!open.get()) return false
        return runCatching {
            // A copy, since the main queue builds the NSMenu after this returns.
            if (menu == null) {
                clearMenu()
            } else {
                applyMenu(
                    menu.snapshot() ?: run {
                        log.warn("setMenu got a menu with a list emptied after it was built, keeping the current menu")
                        return false
                    },
                )
            }
            true
        }.getOrElse { t ->
            log.warn("setMenu threw", t); false
        }
    }

    override fun onEvent(listener: TrayEventListener): TraySubscription = events.subscribe(listener)

    override fun onEvent(executor: Executor, listener: TrayEventListener): TraySubscription =
        events.subscribe(listener, executor)

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        events.close()
        INSTANCE_REGISTRY.remove(instanceId)
        tearDownOnMainThread()
        // The ObjcBindings arena is deliberately NOT closed here (unlike the
        // Linux / Win32 backends). runOnMainQueue can leave actions queued for
        // the main thread that still use the msgSend handles, and the
        // menu-target class + its upcall stub are already process-lifetime.
        // All trays share one set of bindings, see sharedBindings.
    }

    /**
     * Run [tearDown] on the Cocoa main thread and wait for it, bounded.
     *
     * `NSStatusItem` is main-thread-only, and close() is routinely called
     * from a listener, which runs on the libtray event thread. Queuing the
     * teardown also puts it behind any apply* the main thread is already
     * running or holding, so none of them can message a released status
     * item. Waiting keeps close() synchronous: the icon is gone when it
     * returns. The wait is bounded because a main thread that is blocked,
     * or a process that never runs a Cocoa loop, would otherwise hang the
     * caller. On timeout the teardown stays queued and the icon stays up until
     * the main thread gets to it. It never runs on the calling thread: AppKit
     * raises an exception when a view is removed there, which ends the process.
     */
    private fun tearDownOnMainThread() {
        if (isCocoaMainThread(bindings)) {
            tearDown()
            return
        }
        val done = CountDownLatch(1)
        val enqueued = enqueueOnMainQueue {
            try {
                tearDown()
            } finally {
                done.countDown()
            }
        }
        if (!enqueued) {
            log.warn("Could not queue the tray teardown on the Cocoa main thread, so the icon stays up")
        } else if (!done.awaitPastInterrupt(CLOSE_ON_MAIN_TIMEOUT_MS)) {
            log.warn(
                "The Cocoa main thread did not run the tray teardown within {} ms. The icon goes away once it does.",
                CLOSE_ON_MAIN_TIMEOUT_MS,
            )
        }
    }

    private fun tearDown() {
        if (!tornDown.compareAndSet(false, true)) return
        autoreleasepool {
            // close() from inside the open menu's tracking loop (a listener, or
            // the main queue draining while the menu is up): end the tracking
            // first so AppKit is not left showing a menu of a removed item.
            if (menuOpen && shownMenu.address() != 0L) {
                runCatching {
                    bindings.handle("objc_msgSend_void").invokeExact(shownMenu, bindings.sel("cancelTracking")) as Unit
                }
            }
            if (currentMenu.address() != 0L) {
                runCatching {
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        statusItem, bindings.sel("setMenu:"), MemorySegment.NULL,
                    ) as Unit
                }
            }
            releaseMenuModel()
            if (statusView.address() != 0L) {
                runCatching {
                    bindings.handle("objc_msgSend_void").invokeExact(statusView, bindings.sel("removeFromSuperview")) as Unit
                }
                // With the menu open, the overlay's own mouse handler is on the
                // stack. popUpMenu drops our reference once it has returned.
                if (!menuOpen) {
                    runCatching { bindings.handle("objc_release").invokeExact(statusView) as Unit }
                    statusView = MemorySegment.NULL
                }
            }
            // [[NSStatusBar systemStatusBar] removeStatusItem:item]
            runCatching {
                val nsStatusBarCls = bindings.cls("NSStatusBar")
                val systemStatusBar = bindings.handle("objc_msgSend_id")
                    .invokeExact(nsStatusBarCls, bindings.sel("systemStatusBar")) as MemorySegment
                bindings.handle("objc_msgSend_void_id").invokeExact(
                    systemStatusBar, bindings.sel("removeStatusItem:"), statusItem,
                ) as Unit
            }
            // Release our retained reference to the status item itself.
            runCatching { bindings.handle("objc_release").invokeExact(statusItem) as Unit }
        }
    }

    // ── Internals: AppKit operations ─────────────────────────────────────

    /**
     * PNG bytes → NSImage → install on the status item's button.
     * `[NSImage initWithData:]` decodes any format AppKit understands;
     * PNG is universally supported. Released to autorelease pool —
     * the button retains the image when set.
     */
    private fun applyIcon(rawPngBytes: ByteArray): Boolean {
        // Scale on the calling thread, before the marshal: it is pure JVM
        // image work with no AppKit involvement, and doing it here keeps
        // the main queue free of a full-resolution decode + re-encode.
        // Null when the icon is past the pixel budget, which fit has already logged.
        val fitted = IconScaling.fit(rawPngBytes, maxIconSize, log, subsampleOversized) ?: return false
        // An icon that already fits comes back as the caller's own array, and
        // the main queue reads it after this returns.
        showIcon(if (fitted === rawPngBytes) fitted.copyOf() else fitted)
        return true
    }

    /** Install already scaled [pngBytes] on the button, on the main queue. */
    private fun showIcon(pngBytes: ByteArray) {
        runOnMainQueue {
            autoreleasepool {
                val nsData = bindings.nsData(pngBytes)
                // Debug, not info: unlike the create-time steps below, this
                // path runs on every setIcon call, and a consumer animating
                // the tray glyph would get three lines per frame.
                log.debug("applyIcon: NSData allocated, addr=0x{} bytes={}",
                    nsData.address().toString(16), pngBytes.size)
                val nsImageCls = bindings.cls("NSImage")
                val allocated = bindings.handle("objc_msgSend_id")
                    .invokeExact(nsImageCls, bindings.sel("alloc")) as MemorySegment
                val image = bindings.handle("objc_msgSend_id_id")
                    .invokeExact(allocated, bindings.sel("initWithData:"), nsData) as MemorySegment
                if (image.address() == 0L) {
                    log.warn("[NSImage initWithData:] returned NULL -- invalid PNG? Falling back to text title.")
                    setButtonTitle("●")
                    return@autoreleasepool
                }
                log.debug("applyIcon: NSImage created, addr=0x{}", image.address().toString(16))
                // Force template OFF -- by default macOS may render our colored
                // PNG as a black-only template (auto-inverting in dark mode),
                // blanking out colored-only icons. setTemplate:NO uses it as-is.
                runCatching {
                    bindings.handle("objc_msgSend_void_long").invokeExact(
                        image, bindings.sel("setTemplate:"), 0L,
                    ) as Unit
                }
                bindings.handle("objc_msgSend_void_id").invokeExact(
                    statusButton, bindings.sel("setImage:"), image,
                ) as Unit
                // Clear the bullet fallback set in init: Ventura+ NSStatusBarButton
                // shows BOTH image and title, leaving a stray dot beside the icon.
                setButtonTitle("")
                // setImage: retains the image; release our alloc +1 so it doesn't leak.
                bindings.handle("objc_release").invokeExact(image) as Unit
                log.debug("applyIcon: setImage: dispatched to status button (template=NO)")
            }
        }
    }

    /**
     * Set a text title on the status button as a visible fallback —
     * if image decoding fails silently and we have no icon, at least
     * SOMETHING shows up in the menu bar instead of an invisible
     * zero-width entry.
     */
    /**
     * Set the status button's title directly. The caller must already be on the
     * Cocoa main queue (e.g. inside [applyIcon]'s marshaled block); use
     * [applyTitleFallback] from anywhere else.
     */
    private fun setButtonTitle(text: String) {
        runCatching {
            val nsString = bindings.nsString(text)
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusButton, bindings.sel("setTitle:"), nsString,
            ) as Unit
        }
    }

    private fun applyTitleFallback(text: String) {
        runOnMainQueue { autoreleasepool { setButtonTitle(text) } }
    }

    private fun applyTooltip(text: String) {
        runOnMainQueue {
            autoreleasepool {
                val nsString = bindings.nsString(text)
                bindings.handle("objc_msgSend_void_id").invokeExact(
                    statusButton, bindings.sel("setToolTip:"), nsString,
                ) as Unit
            }
        }
    }

    /**
     * Bring the kept NSMenu in line with [menu], creating it on first use, and
     * keep it in [currentMenu] for [popUpMenu]. Items whose key is still there
     * are updated in place, so a menu on screen changes under the user instead
     * of going stale, and a click on it still reaches the item it shows.
     */
    private fun applyMenu(menu: TrayMenu) {
        runOnMainQueue {
            autoreleasepool {
                val root = menuModel ?: ShownMenu(newMenu()).also {
                    menuModel = it
                    currentMenu = it.menu
                }
                syncItems(root, menu.items, null)
            }
        }
    }

    private fun clearMenu() {
        runOnMainQueue { autoreleasepool { releaseMenuModel() } }
    }

    /**
     * Drop the kept menu and our references to it. A menu on screen stays up:
     * [popUpMenu] holds its own reference, and the NSMenu its items, whose
     * clicks still deliver since each item carries its id and owner.
     */
    private fun releaseMenuModel() {
        val model = menuModel ?: return
        menuModel = null
        currentMenu = MemorySegment.NULL
        releaseShownMenu(model)
    }

    private fun releaseShownMenu(shown: ShownMenu) {
        shown.items.forEach(::releaseShownItem)
        release(shown.menu)
    }

    private fun releaseShownItem(shown: ShownItem) {
        shown.submenu?.let(::releaseShownMenu)
        release(shown.item)
    }

    /** A new empty NSMenu with auto-enabling off. Alloc +1, owned by the caller. */
    private fun newMenu(): MemorySegment {
        val allocated = bindings.handle("objc_msgSend_id")
            .invokeExact(bindings.cls("NSMenu"), bindings.sel("alloc")) as MemorySegment
        val menu = bindings.handle("objc_msgSend_id_id")
            .invokeExact(allocated, bindings.sel("initWithTitle:"), bindings.nsString("")) as MemorySegment
        disableAutoenabling(menu)
        return menu
    }

    /**
     * Make [shown] hold [items], children of the item keyed [parentKey]:
     * items whose key is gone are removed, the rest updated in place and
     * moved where they belong, and new ones created and inserted. [shown]'s
     * list follows the NSMenu's order throughout.
     */
    private fun syncItems(shown: ShownMenu, items: List<TrayMenuItem>, parentKey: MenuItemKey?) {
        val keys = menuItemKeys(items, parentKey)
        val wanted = keys.toHashSet()
        val gone = shown.items.filter { it.key !in wanted }
        for (item in gone) {
            bindings.handle("objc_msgSend_void_id").invokeExact(shown.menu, bindings.sel("removeItem:"), item.item) as Unit
            releaseShownItem(item)
        }
        shown.items.removeAll(gone)
        for ((index, entry) in items.zip(keys).withIndex()) {
            val (item, key) = entry
            val existing = shown.items.firstOrNull { it.key == key }
            if (existing == null) {
                val created = createItem(item, key)
                insertItem(shown.menu, created.item, index)
                shown.items.add(index, created)
                continue
            }
            updateItem(existing, item)
            val at = shown.items.indexOf(existing)
            if (at != index) {
                // Our reference keeps the item alive between the two calls.
                bindings.handle("objc_msgSend_void_id").invokeExact(shown.menu, bindings.sel("removeItem:"), existing.item) as Unit
                shown.items.removeAt(at)
                insertItem(shown.menu, existing.item, index)
                shown.items.add(index, existing)
            }
        }
    }

    private fun insertItem(menu: MemorySegment, item: MemorySegment, index: Int) {
        bindings.handle("objc_msgSend_void_id_long")
            .invokeExact(menu, bindings.sel("insertItem:atIndex:"), item, index.toLong()) as Unit
    }

    /** A new NSMenuItem for [item], submenu included, with our own reference to it. */
    private fun createItem(item: TrayMenuItem, key: MenuItemKey): ShownItem {
        val nsMenuItemCls = bindings.cls("NSMenuItem")
        if (item is TrayMenuItem.Separator) {
            val separator = bindings.handle("objc_msgSend_id")
                .invokeExact(nsMenuItemCls, bindings.sel("separatorItem")) as MemorySegment
            // Autoreleased, so take a reference of our own like the other items have.
            bindings.handle("objc_retain").invokeExact(separator) as MemorySegment
            return ShownItem(key, separator, item.label, item.enabled, null)
        }
        // A submenu parent carries no action: AppKit opens the submenu instead.
        val action = if (item is TrayMenuItem.Standard) bindings.sel("onMenuItem:") else MemorySegment.NULL
        val allocated = bindings.handle("objc_msgSend_id")
            .invokeExact(nsMenuItemCls, bindings.sel("alloc")) as MemorySegment
        val menuItem = bindings.handle("objc_msgSend_id_id_sel_id").invokeExact(
            allocated, bindings.sel("initWithTitle:action:keyEquivalent:"),
            bindings.nsString(item.label), action, bindings.nsString(""),
        ) as MemorySegment
        if (item is TrayMenuItem.Standard) {
            bindings.handle("objc_msgSend_void_id").invokeExact(
                menuItem, bindings.sel("setTarget:"), menuTargetInstance,
            ) as Unit
            bindings.handle("objc_msgSend_void_long").invokeExact(
                menuItem, bindings.sel("setTag:"), instanceId.toLong(),
            ) as Unit
            bindings.handle("objc_msgSend_void_id").invokeExact(
                menuItem, bindings.sel("setRepresentedObject:"), bindings.nsString(item.id),
            ) as Unit
        }
        if (!item.enabled) {
            bindings.handle("objc_msgSend_void_long").invokeExact(menuItem, bindings.sel("setEnabled:"), 0L) as Unit
        }
        var submenu: ShownMenu? = null
        if (item is TrayMenuItem.Submenu) {
            submenu = ShownMenu(newMenu())
            bindings.handle("objc_msgSend_void_id").invokeExact(menuItem, bindings.sel("setSubmenu:"), submenu.menu) as Unit
            syncItems(submenu, item.items, key)
        }
        return ShownItem(key, menuItem, item.label, item.enabled, submenu)
    }

    /** Apply what changed in [item] to the NSMenuItem [shown] already holds. */
    private fun updateItem(shown: ShownItem, item: TrayMenuItem) {
        if (item is TrayMenuItem.Separator) return
        if (shown.label != item.label) {
            bindings.handle("objc_msgSend_void_id").invokeExact(
                shown.item, bindings.sel("setTitle:"), bindings.nsString(item.label),
            ) as Unit
            shown.label = item.label
        }
        if (shown.enabled != item.enabled) {
            bindings.handle("objc_msgSend_void_long").invokeExact(
                shown.item, bindings.sel("setEnabled:"), if (item.enabled) 1L else 0L,
            ) as Unit
            shown.enabled = item.enabled
        }
        if (item is TrayMenuItem.Submenu) syncItems(checkNotNull(shown.submenu), item.items, shown.key)
    }

    /** An NSMenu kept between menus, our reference held, with its items in menu order. */
    private class ShownMenu(val menu: MemorySegment) {
        val items = mutableListOf<ShownItem>()
    }

    /** An NSMenuItem kept between menus, our reference held, with what it shows now. */
    private class ShownItem(
        val key: MenuItemKey,
        val item: MemorySegment,
        var label: String,
        var enabled: Boolean,
        val submenu: ShownMenu?,
    )

    /**
     * `[menu setAutoenablesItems:NO]`.
     *
     * NSMenu defaults this to YES, which makes AppKit recompute every
     * item's enabled state at display time from the responder chain: an
     * item whose action the target implements is force-enabled, and our
     * `setEnabled:NO` is silently overwritten. Since our menu target does
     * implement `onMenuItem:` for every Standard item, that meant
     * [TrayMenuItem.enabled] = false rendered as a normal, clickable entry
     * on macOS while Linux and Windows greyed it out. Turning auto-enabling
     * off makes the explicit flag authoritative, which is what the
     * cross-platform contract says.
     */
    private fun disableAutoenabling(menu: MemorySegment) {
        runCatching {
            bindings.handle("objc_msgSend_void_long").invokeExact(
                menu, bindings.sel("setAutoenablesItems:"), 0L,
            ) as Unit
        }
    }

    // ── Status item clicks ───────────────────────────────────────────────

    /**
     * Lay a [LibtrayStatusView][ensureStatusViewClass] over the status button
     * and point the button's target-action at the shared target. Runs from
     * the constructor, which [create] only reaches on the main thread.
     *
     * The overlay is what sees mouse clicks. Its handlers get the `NSEvent`
     * as an argument, unlike a target-action, which has to read
     * `[NSApp currentEvent]`, and on macOS 27 that is no longer reliably the
     * click: controls are driven by gesture recognizers there and the action
     * arrives after the event. The overlay is sized to the button with
     * constraints, which spares reading the button's `frame` back, a struct
     * return that needs `objc_msgSend_stret` on x86_64. The target-action
     * only fires for presses that do not reach the overlay, such as
     * VoiceOver.
     */
    private fun installClickHandler() = autoreleasepool {
        runCatching {
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusButton, bindings.sel("setTarget:"), menuTargetInstance,
            ) as Unit
            bindings.handle("objc_msgSend_void_sel").invokeExact(
                statusButton, bindings.sel("setAction:"), bindings.sel("onStatusItemClick:"),
            ) as Unit

            val allocated = bindings.handle("objc_msgSend_id")
                .invokeExact(statusViewClass, bindings.sel("alloc")) as MemorySegment
            // -[NSView init] is initWithFrame: with a zero rect.
            val view = bindings.handle("objc_msgSend_id")
                .invokeExact(allocated, bindings.sel("init")) as MemorySegment
            require(view.address() != 0L) { "status view init returned nil" }
            statusView = view
            bindings.handle("objc_msgSend_void_long").invokeExact(
                view, bindings.sel("setTranslatesAutoresizingMaskIntoConstraints:"), 0L,
            ) as Unit
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusButton, bindings.sel("addSubview:"), view,
            ) as Unit
            for (anchor in listOf("leadingAnchor", "trailingAnchor", "topAnchor", "bottomAnchor")) {
                val own = bindings.handle("objc_msgSend_id").invokeExact(view, bindings.sel(anchor)) as MemorySegment
                val button = bindings.handle("objc_msgSend_id")
                    .invokeExact(statusButton, bindings.sel(anchor)) as MemorySegment
                val constraint = bindings.handle("objc_msgSend_id_id")
                    .invokeExact(own, bindings.sel("constraintEqualToAnchor:"), button) as MemorySegment
                bindings.handle("objc_msgSend_void_long").invokeExact(
                    constraint, bindings.sel("setActive:"), 1L,
                ) as Unit
            }
        }.onFailure {
            log.warn("Installing the status item click handling failed, clicks will not reach the app", it)
        }
    }

    /**
     * One mouse event on the overlay, [selector] naming which. Main thread,
     * from the status view upcall.
     *
     * A primary press opens the menu right away when it should, the way a
     * status item menu opens on press, and otherwise only highlights the
     * button so the release can fire Activated. A secondary press always
     * asks for the menu. The middle button reports on release, like the
     * other backends.
     */
    private fun handleStatusViewMouse(selector: MemorySegment, event: MemorySegment) {
        if (menuOpen) return
        lastOverlayActivityNanos = System.nanoTime()
        when (selector.address()) {
            bindings.sel("mouseDown:").address() -> {
                val control = (msgSendLong(event, "modifierFlags") and NS_EVENT_MODIFIER_FLAG_CONTROL) != 0L
                if (control || (menuOnPrimaryClick && currentMenu.address() != 0L)) {
                    primaryPressPending = false
                    openMenu()
                } else {
                    primaryPressPending = true
                    setHighlighted(true)
                }
            }
            bindings.sel("mouseUp:").address() -> {
                setHighlighted(false)
                if (primaryPressPending) {
                    primaryPressPending = false
                    fire(TrayEvent.Activated)
                }
            }
            bindings.sel("rightMouseDown:").address() -> openMenu()
            bindings.sel("otherMouseUp:").address() ->
                if (msgSendLong(event, "buttonNumber") == MIDDLE_BUTTON_NUMBER) fire(TrayEvent.MiddleActivated)
        }
    }

    /**
     * A press that did not come through the overlay, such as VoiceOver.
     * Counts as a primary click.
     *
     * An action shortly after overlay activity is dropped. On macOS 27 the
     * button is driven by gesture recognizers, which may still recognize a
     * click the overlay already handled and deliver the action late, after
     * the menu it opened has closed. Handling that would fire the click a
     * second time or reopen the menu.
     */
    private fun handleStatusItemAction() {
        if (menuOpen) return
        if (System.nanoTime() - lastOverlayActivityNanos < OVERLAY_ACTION_SUPPRESS_NANOS) return
        if (menuOnPrimaryClick && currentMenu.address() != 0L) openMenu() else fire(TrayEvent.Activated)
    }

    /** The overlay takes the mouse, so the button no longer highlights on its own. */
    private fun setHighlighted(highlighted: Boolean) {
        runCatching {
            bindings.handle("objc_msgSend_void_long").invokeExact(
                statusButton, bindings.sel("highlight:"), if (highlighted) 1L else 0L,
            ) as Unit
        }
    }

    private fun openMenu() {
        // Menu tracking eats the release of a primary press still held down,
        // so a pending one would otherwise fire on some later stray release.
        primaryPressPending = false
        fire(TrayEvent.MenuRequested)
        popUpMenu()
    }

    /**
     * Show [currentMenu] anchored to the status item. Attaching the menu and
     * sending `performClick:` lets AppKit place, track and dismiss it like
     * any status item menu, keyboard navigation included. `performClick:`
     * returns once the menu closes, and detaching it then leaves the next
     * click to the overlay again. The extra retains cover a [setMenu] that
     * releases our reference to the menu while it is open, and a close()
     * that tears the status item down from inside the tracking loop, after
     * which the item must not be messaged again.
     *
     * This always runs inside the overlay's own mouse handler, under
     * `-[NSWindow sendEvent:]` for the status bar window. When a close()
     * landed during tracking, the last references to the overlay, the menu
     * and the status item (which owns the button and its window) are dropped
     * on a later main-queue turn, once that event dispatch has unwound.
     */
    private fun popUpMenu() {
        val menu = currentMenu
        if (menu.address() == 0L) return
        bindings.handle("objc_retain").invokeExact(menu) as MemorySegment
        bindings.handle("objc_retain").invokeExact(statusItem) as MemorySegment
        menuOpen = true
        shownMenu = menu
        try {
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusItem, bindings.sel("setMenu:"), menu,
            ) as Unit
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusButton, bindings.sel("performClick:"), MemorySegment.NULL,
            ) as Unit
        } finally {
            menuOpen = false
            shownMenu = MemorySegment.NULL
            lastOverlayActivityNanos = System.nanoTime()
            if (!tornDown.get()) {
                runCatching {
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        statusItem, bindings.sel("setMenu:"), MemorySegment.NULL,
                    ) as Unit
                }
                release(menu)
                release(statusItem)
            } else {
                val view = statusView
                statusView = MemorySegment.NULL
                val releaseLater = {
                    release(menu)
                    release(statusItem)
                    if (view.address() != 0L) release(view)
                }
                if (!enqueueOnMainQueue(releaseLater)) releaseLater()
            }
        }
    }

    private fun release(obj: MemorySegment) {
        runCatching { bindings.handle("objc_release").invokeExact(obj) as Unit }
    }

    private fun msgSendLong(receiver: MemorySegment, selector: String): Long =
        bindings.handle("objc_msgSend_long").invokeExact(receiver, bindings.sel(selector)) as Long

    /**
     * Each AppKit operation gets its own short-lived autorelease pool.
     * Cocoa convenience constructors return autoreleased objects
     * (NSString, NSImage, NSData) that would otherwise accumulate in
     * the thread's pool until it drains — without our own pool, that's
     * a slow leak in long-running JVMs that never see a Cocoa runloop.
     */
    private inline fun autoreleasepool(block: () -> Unit) {
        val push = bindings.handle("objc_autoreleasePoolPush")
        val pop  = bindings.handle("objc_autoreleasePoolPop")
        val pool = push.invokeExact() as MemorySegment
        try {
            block()
        } finally {
            runCatching { pop.invokeExact(pool) as Unit }
        }
    }

    private fun fire(event: TrayEvent) = events.fire(event)

    /**
     * Run [action] on the Cocoa main thread (issue #3). Already there -> run
     * inline, otherwise enqueue it (see [enqueueOnMainQueue]).
     *
     * The queued body re-checks [open] on the main thread rather than
     * trusting the check made here at enqueue time. close() flips [open]
     * before it queues the teardown, so an action that had not started by
     * then turns into a no-op instead of messaging the released
     * NSStatusItem, and one that had started finishes before the teardown
     * runs behind it.
     */
    private fun runOnMainQueue(action: () -> Unit) {
        if (!open.get()) return
        if (isCocoaMainThread(bindings)) {
            runCatching { action() }
            return
        }
        if (!enqueueOnMainQueue { if (open.get()) action() }) {
            log.warn("Could not queue a tray change on the Cocoa main thread, so it is dropped")
        }
    }

    private fun enqueueOnMainQueue(action: () -> Unit): Boolean = enqueueOnMainQueue(bindings, action) != null

    internal companion object {
        private val log = LoggerFactory.getLogger("libtray.AppKitTray")
        private val instanceCounter = AtomicInteger(0)

        /** instance id → impl, for upcall dispatch. */
        private val INSTANCE_REGISTRY = ConcurrentHashMap<Int, AppKitTrayImpl>()

        /** GCD work function `void f(void *context)`, the shape of [dispatchTrampoline]. */
        internal val TRAMPOLINE_DESCRIPTOR: FunctionDescriptor = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)

        /**
         * `void f(id self, SEL _cmd, id arg)`, the shape of every Objective-C
         * method IMP libtray installs: [onMenuItemEntry],
         * [onStatusItemClickEntry], [onStatusViewMouseEntry] and
         * [onMainThreadWakeEntry].
         */
        internal val OBJC_METHOD_DESCRIPTOR: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)

        private val DISPATCH_TRAMPOLINE = UpcallTarget(AppKitTrayImpl::class.java, "dispatchTrampoline", TRAMPOLINE_DESCRIPTOR)
        private val ON_MAIN_THREAD_WAKE = UpcallTarget(AppKitTrayImpl::class.java, "onMainThreadWakeEntry", OBJC_METHOD_DESCRIPTOR)
        private val ON_MENU_ITEM = UpcallTarget(AppKitTrayImpl::class.java, "onMenuItemEntry", OBJC_METHOD_DESCRIPTOR)
        private val ON_STATUS_ITEM_CLICK = UpcallTarget(AppKitTrayImpl::class.java, "onStatusItemClickEntry", OBJC_METHOD_DESCRIPTOR)
        private val ON_STATUS_VIEW_MOUSE = UpcallTarget(AppKitTrayImpl::class.java, "onStatusViewMouseEntry", OBJC_METHOD_DESCRIPTOR)

        /** How long close() waits for the main queue to run the teardown. Matches the other backends' join budget. */
        private const val CLOSE_ON_MAIN_TIMEOUT_MS: Long = 2_000

        /** How long create() waits for the main queue to build the tray. Matches the Windows backend's startup wait. */
        private const val CREATE_ON_MAIN_TIMEOUT_MS: Long = 5_000

        /** `NSEventModifierFlagControl` from NSEvent.h. */
        private const val NS_EVENT_MODIFIER_FLAG_CONTROL: Long = 1L shl 18

        /** How long after overlay activity a button action is taken for the same click. */
        private const val OVERLAY_ACTION_SUPPRESS_NANOS: Long = 500_000_000

        /** `[NSEvent buttonNumber]` of the middle button. 0 is left, 1 right. */
        private const val MIDDLE_BUTTON_NUMBER: Long = 2

        // ── Main-thread marshaling (issue #3) ─────────────────────────────

        /** One action waiting for the main thread. A class of its own, so a caller that gives up can remove it. */
        private class MainAction(val run: () -> Unit)

        /** What waits for the main thread, oldest first. */
        private val MAIN_ACTIONS = ConcurrentLinkedQueue<MainAction>()

        /** True while [runMainActions] is on the stack. Main thread only. */
        private var runningMainActions = false

        @Volatile private var trampolineStub: MemorySegment? = null
        @Volatile private var trampolineArena: Arena? = null

        /**
         * AWT's private run loop mode, the same in OpenJDK and JBR. AWT runs the
         * main thread's loop in it while that thread waits for the event
         * dispatch thread, which AppKit's accessibility and Services queries
         * make it do.
         */
        private const val AWT_RUN_LOOP_MODE = "AWTRunLoopMode"

        /** The value of `NSRunLoopCommonModes`. */
        private const val COMMON_RUN_LOOP_MODES = "kCFRunLoopCommonModes"

        /** An instance of `LibtrayMainThread_<pid>`, the target of [performWake], or NULL until it is needed. */
        @Volatile private var mainThreadTarget: MemorySegment = MemorySegment.NULL

        /** The NSArray of run loop modes [performWake] works in. Set before [mainThreadTarget]. */
        @Volatile private var mainThreadModes: MemorySegment = MemorySegment.NULL
        @Volatile private var mainThreadTargetFailed = false
        @Volatile private var mainThreadStubArena: Arena? = null

        /**
         * Run what waits for the main thread, in the order it was queued. Main
         * thread only. A wake that arrives while an action is running, from a
         * run loop that action started, leaves the rest to the call already on
         * the stack, so actions never run inside one another.
         */
        private fun runMainActions() {
            if (runningMainActions) return
            runningMainActions = true
            try {
                while (true) {
                    val next = MAIN_ACTIONS.poll() ?: break
                    runCatching { next.run() }.onFailure { log.warn("main-thread action threw", it) }
                }
            } finally {
                runningMainActions = false
            }
        }

        /** GCD work function `void f(void* context)`, one of the two wakes. */
        @JvmStatic
        @Suppress("unused", "UNUSED_PARAMETER")
        fun dispatchTrampoline(context: MemorySegment) {
            runCatching { runMainActions() }.onFailure { log.warn("running the main-thread actions threw", it) }
        }

        /** The `void wake:(id)` IMP of `LibtrayMainThread`, the other wake. */
        @JvmStatic
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onMainThreadWakeEntry(self: MemorySegment, cmd: MemorySegment, arg: MemorySegment) {
            runCatching { runMainActions() }.onFailure { log.warn("running the main-thread actions threw", it) }
        }

        /**
         * Queue [action] for the Cocoa main thread and wake it. Returns what a
         * caller that gives up removes from [MAIN_ACTIONS], or null when the
         * main thread could not be woken at all.
         *
         * Two wakes, and whichever comes first runs the action. The main
         * dispatch queue is not drained while AWT waits for the event dispatch
         * thread in [AWT_RUN_LOOP_MODE], and a caller on that thread which
         * waits for the action would deadlock with it. [performWake] names that
         * mode. The dispatch one serves a main thread without a Foundation run
         * loop.
         */
        private fun enqueueOnMainQueue(bindings: ObjcBindings, action: () -> Unit): MainAction? {
            val queued = MainAction(action)
            MAIN_ACTIONS.add(queued)
            val performed = runCatching { performWake(bindings) }
                .onFailure { log.debug("performSelectorOnMainThread: threw", it) }.getOrDefault(false)
            val dispatched = bindings.mainQueue.address() != 0L && runCatching {
                bindings.handle("dispatch_async_f").invokeExact(
                    bindings.mainQueue, MemorySegment.NULL, trampolineStub ?: dispatchTrampolineStub(),
                ) as Unit
                true
            }.getOrDefault(false)
            if (performed || dispatched) return queued
            MAIN_ACTIONS.remove(queued)
            return null
        }

        /** `[target performSelectorOnMainThread:wake: withObject:nil waitUntilDone:NO modes:]`. */
        private fun performWake(bindings: ObjcBindings): Boolean {
            // Past the lock once it is built: the main thread takes the same one,
            // and a caller it waits for must not wait for it.
            var target = mainThreadTarget
            if (target.address() == 0L) target = buildMainThreadTarget(bindings)
            if (target.address() == 0L) return false
            val pool = bindings.handle("objc_autoreleasePoolPush").invokeExact() as MemorySegment
            try {
                bindings.handle("objc_msgSend_void_sel_id_bool_id").invokeExact(
                    target, bindings.sel("performSelectorOnMainThread:withObject:waitUntilDone:modes:"),
                    bindings.sel("wake:"), MemorySegment.NULL, false, mainThreadModes,
                ) as Unit
            } finally {
                bindings.handle("objc_autoreleasePoolPop").invokeExact(pool) as Unit
            }
            return true
        }

        /**
         * The target of [performWake], built on first use on whichever thread
         * that is, as the Objective-C runtime allows. NULL when it could not be
         * built, as for a second copy of libtray in the process (see
         * [ensureMenuTargetClass]).
         */
        @Synchronized
        private fun buildMainThreadTarget(bindings: ObjcBindings): MemorySegment {
            if (mainThreadTarget.address() != 0L || mainThreadTargetFailed) return mainThreadTarget
            runCatching {
                val arena = Arena.ofShared()
                mainThreadStubArena = arena
                val className = "LibtrayMainThread_${ProcessHandle.current().pid()}"
                val newClass = bindings.handle("objc_allocateClassPair")
                    .invokeExact(bindings.cls("NSObject"), arena.allocateFrom(className), 0L) as MemorySegment
                require(newClass.address() != 0L) { "objc_allocateClassPair failed for $className" }
                val added = bindings.handle("class_addMethod").invokeExact(
                    newClass, bindings.sel("wake:"), ON_MAIN_THREAD_WAKE.stub(arena), arena.allocateFrom("v@:@"),
                ) as Boolean
                require(added) { "class_addMethod failed for wake:" }
                bindings.handle("objc_registerClassPair").invokeExact(newClass) as Unit

                val pool = bindings.handle("objc_autoreleasePoolPush").invokeExact() as MemorySegment
                try {
                    val common = bindings.handle("objc_msgSend_id_id").invokeExact(
                        bindings.cls("NSArray"), bindings.sel("arrayWithObject:"), bindings.nsString(COMMON_RUN_LOOP_MODES),
                    ) as MemorySegment
                    val modes = bindings.handle("objc_msgSend_id_id").invokeExact(
                        common, bindings.sel("arrayByAddingObject:"), bindings.nsString(AWT_RUN_LOOP_MODE),
                    ) as MemorySegment
                    mainThreadModes = bindings.handle("objc_retain").invokeExact(modes) as MemorySegment
                } finally {
                    bindings.handle("objc_autoreleasePoolPop").invokeExact(pool) as Unit
                }
                val allocated = bindings.handle("class_createInstance").invokeExact(newClass, 0L) as MemorySegment
                mainThreadTarget = bindings.handle("objc_msgSend_id").invokeExact(allocated, bindings.sel("init")) as MemorySegment
            }.onFailure {
                mainThreadTargetFailed = true
                log.warn("Could not build the target that wakes the Cocoa main thread while AWT holds it", it)
            }
            return mainThreadTarget
        }

        /** The single shared upcall stub bridging GCD to [dispatchTrampoline]. Built once, process-lifetime. */
        @Synchronized
        private fun dispatchTrampolineStub(): MemorySegment {
            trampolineStub?.let { return it }
            val arena = Arena.ofShared()
            val stub = DISPATCH_TRAMPOLINE.stub(arena)
            trampolineArena = arena
            trampolineStub = stub
            return stub
        }

        /**
         * The shared `LibtrayMenuTarget_<pid>` Objective-C class built
         * once per JVM. `-onMenuItem:(id)sender` routes NSMenuItem clicks
         * back into Java via [onMenuItemEntry], and
         * `-onStatusItemClick:(id)sender` routes status button clicks via
         * [onStatusItemClickEntry].
         */
        @Volatile private var menuTargetClass: MemorySegment? = null
        @Volatile private var menuTargetInstance: MemorySegment = MemorySegment.NULL
        @Volatile private var menuActionStub: MemorySegment? = null
        @Volatile private var statusClickStub: MemorySegment? = null
        @Volatile private var stubArena: Arena? = null

        /**
         * The `void onMenuItem:(id sender)` IMP. Cocoa calls it as
         * `void f(id self, SEL _cmd, id sender)` per ObjC convention.
         * `sender` is the NSMenuItem the user picked, which carries all
         * that is needed: its tag is the owning tray's instance id and its
         * `representedObject` the libtray id. Nothing is looked up in state
         * setMenu replaces, so a click on an item it removed while the menu
         * was open still names that item, as it does on the other backends.
         * Only a tray closed meanwhile drops the click.
         */
        @JvmStatic
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onMenuItemEntry(self: MemorySegment, cmd: MemorySegment, sender: MemorySegment) {
            val bindings = sharedBindings ?: return
            // An exception leaving an upcall ends the JVM.
            try {
                val owner = (bindings.handle("objc_msgSend_long").invokeExact(sender, bindings.sel("tag")) as Long).toInt()
                val inst = INSTANCE_REGISTRY[owner] ?: return
                val idString = bindings.handle("objc_msgSend_id")
                    .invokeExact(sender, bindings.sel("representedObject")) as MemorySegment
                if (idString.address() == 0L) return
                val utf8 = bindings.handle("objc_msgSend_id").invokeExact(idString, bindings.sel("UTF8String")) as MemorySegment
                if (utf8.address() == 0L) return
                inst.fire(TrayEvent.MenuItemSelected(utf8.reinterpret(Long.MAX_VALUE).getString(0)))
            } catch (t: Throwable) {
                log.warn("onMenuItem: handling the click threw", t)
            }
        }

        /**
         * The `void onStatusItemClick:(id sender)` IMP. `sender` is the
         * status button, which identifies the tray it belongs to.
         */
        @JvmStatic
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onStatusItemClickEntry(self: MemorySegment, cmd: MemorySegment, sender: MemorySegment) {
            val inst = INSTANCE_REGISTRY.values.firstOrNull { it.statusButton.address() == sender.address() } ?: return
            runCatching { inst.handleStatusItemAction() }
                .onFailure { log.warn("onStatusItemClick: handling threw", it) }
        }

        /**
         * The IMP behind every mouse override of `LibtrayStatusView`:
         * `void f(id self, SEL _cmd, NSEvent *event)`. `self` is the overlay,
         * which identifies the tray, and `_cmd` says which override ran.
         */
        @JvmStatic
        fun onStatusViewMouseEntry(self: MemorySegment, cmd: MemorySegment, event: MemorySegment) {
            val inst = INSTANCE_REGISTRY.values.firstOrNull { it.statusView.address() == self.address() } ?: return
            runCatching { inst.handleStatusViewMouse(cmd, event) }
                .onFailure { log.warn("status view mouse handling threw", it) }
        }

        /** The `LibtrayStatusView_<pid>` class, built once per JVM. */
        @Volatile private var statusViewClass: MemorySegment = MemorySegment.NULL
        @Volatile private var statusViewStub: MemorySegment? = null

        // The press and release of every button, so none of them falls through
        // to the button underneath. Only some of them do anything.
        private val STATUS_VIEW_SELECTORS = listOf(
            "mouseDown:", "mouseUp:", "rightMouseDown:", "rightMouseUp:", "otherMouseDown:", "otherMouseUp:",
        )

        /**
         * Build the runtime `LibtrayStatusView_<pid>` class once per JVM: an
         * NSView subclass whose [STATUS_VIEW_SELECTORS] overrides all go to
         * one upcall, [onStatusViewMouseEntry], which tells them apart by
         * `_cmd`. Not calling super keeps the button underneath from
         * tracking the click itself.
         */
        @Synchronized
        private fun ensureStatusViewClass(bindings: ObjcBindings) {
            if (statusViewClass.address() != 0L) return
            val arena = Arena.ofShared()
            val stub = ON_STATUS_VIEW_MOUSE.stub(arena)
            val className = "LibtrayStatusView_${ProcessHandle.current().pid()}"
            val newClass = bindings.handle("objc_allocateClassPair")
                .invokeExact(bindings.cls("NSView"), arena.allocateFrom(className), 0L) as MemorySegment
            require(newClass.address() != 0L) { "objc_allocateClassPair failed for $className" }
            val typesSeg = arena.allocateFrom("v@:@")
            for (selector in STATUS_VIEW_SELECTORS) {
                val ok = bindings.handle("class_addMethod")
                    .invokeExact(newClass, bindings.sel(selector), stub, typesSeg) as Boolean
                require(ok) { "class_addMethod failed for $selector" }
            }
            bindings.handle("objc_registerClassPair").invokeExact(newClass) as Unit
            statusViewStub = stub
            statusViewClass = newClass
            log.info("Registered runtime ObjC class {} for status item clicks", className)
        }

        /**
         * The process's one set of bindings, also read by the static upcall
         * handler. Their arena is never closed (see [close]), so loading per
         * tray would keep another set of handles alive for every create().
         */
        @Volatile private var sharedBindings: ObjcBindings? = null

        @Synchronized
        private fun loadSharedBindings(): ObjcBindings? =
            sharedBindings ?: ObjcBindings.load()?.also { sharedBindings = it }

        fun create(builder: TrayBuilder): Tray? {
            val bindings = loadSharedBindings() ?: run {
                log.info("Objective-C runtime / AppKit not loadable — AppKit tray unavailable")
                return null
            }
            // NSStatusItem and the NSWindow behind it only work on the Cocoa
            // main thread (OS thread 0). In an AWT, Swing or Compose app that
            // thread belongs to AWT, so the caller is never on it.
            if (isCocoaMainThread(bindings)) {
                return createOnMainThread(bindings, builder, initialIcon(builder))
            }
            return createThroughMainQueue(bindings, builder)
        }

        /**
         * The builder's icon scaled for the status item, or no bytes when it is
         * past the pixel budget. The tray then keeps the text placeholder the
         * constructor puts up before any icon.
         */
        private fun initialIcon(builder: TrayBuilder): ByteArray =
            IconScaling.fit(builder.iconBytes, builder.maxIconSize, log, builder.subsampleOversizedIcons) ?: ByteArray(0)

        /**
         * Run [createOnMainThread] on the Cocoa main queue and wait for it.
         * The queue is served by whatever runs the main thread's Cocoa loop:
         * AWT in an AWT, Swing or Compose app, Glass in a JavaFX one.
         *
         * Only into an application a UI toolkit already runs. Before AWT
         * starts, the java launcher's own loop on the main thread serves the
         * queue too, and creating the tray then would create NSApp as a plain
         * NSApplication. AWT needs its own NSApplicationAWT there, and finding
         * the application taken it runs embedded. So that case returns null.
         *
         * The wait is bounded, because a main thread that is blocked, or a
         * process where nothing runs the Cocoa loop, would otherwise hang the
         * caller. A give-up never leaves a status item nobody owns: a creation
         * that has not started by then is skipped, and one that finishes
         * afterwards closes its tray again.
         */
        private fun createThroughMainQueue(bindings: ObjcBindings, builder: TrayBuilder): Tray? {
            // Plain JVM work, kept off the main thread, which in these apps is AWT's.
            val icon = initialIcon(builder)
            val handoff = Any()
            var abandoned = false
            var finished = false
            var created: Tray? = null
            val done = CountDownLatch(1)

            fun createQueued() {
                try {
                    if (synchronized(handoff) { abandoned }) return
                    val tray = if (hostRunsApplication(bindings)) {
                        createOnMainThread(bindings, builder, icon)
                    } else {
                        log.warn(
                            "No UI toolkit runs the Cocoa application yet, so the tray is not created. " +
                                "Create it once the app's first window is up, or on the main thread.",
                        )
                        null
                    }
                    val unwanted = synchronized(handoff) {
                        finished = true
                        if (abandoned) tray else {
                            created = tray
                            null
                        }
                    }
                    // On the main thread, so this tears down inline.
                    unwanted?.close()
                } finally {
                    done.countDown()
                }
            }

            val queued = enqueueOnMainQueue(bindings, ::createQueued) ?: run {
                log.warn("Could not queue the tray creation on the Cocoa main queue")
                return null
            }
            done.awaitPastInterrupt(CREATE_ON_MAIN_TIMEOUT_MS)
            synchronized(handoff) {
                if (finished) return created
                abandoned = true
            }
            // Not run yet: drop it, so a main thread that never gets to it does
            // not keep the builder and its icon for the life of the process.
            MAIN_ACTIONS.remove(queued)
            log.warn(
                "The Cocoa main queue did not run the tray creation within {} ms. The main thread is " +
                    "blocked, or nothing runs a Cocoa loop on it.",
                CREATE_ON_MAIN_TIMEOUT_MS,
            )
            return null
        }

        /**
         * Whether a UI toolkit already runs NSApp. Reads AppKit's `NSApp`
         * global instead of `+sharedApplication`, which would create the
         * application. Main thread, like every other use of NSApp here.
         */
        private fun hostRunsApplication(bindings: ObjcBindings): Boolean {
            val global = bindings.applicationGlobal
            if (global.address() == 0L) return false
            val app = global.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0)
            return app.address() != 0L && isNSAppRunning(bindings, app)
        }

        /** Build the status item and the tray around it, showing the already scaled [icon]. Cocoa main thread only. */
        private fun createOnMainThread(bindings: ObjcBindings, builder: TrayBuilder, icon: ByteArray): Tray? {
            return runCatching {
                log.info("[create] step 1/5: ensure NSApplication is initialised")
                ensureNSApplicationInitialised(bindings)

                log.info("[create] step 2/5: register LibtrayMenuTarget and LibtrayStatusView classes")
                ensureMenuTargetClass(bindings)
                ensureStatusViewClass(bindings)

                log.info("[create] step 3/5: get [NSStatusBar systemStatusBar]")
                val nsStatusBarCls = bindings.cls("NSStatusBar")
                val systemStatusBar = bindings.handle("objc_msgSend_id")
                    .invokeExact(nsStatusBarCls, bindings.sel("systemStatusBar")) as MemorySegment
                if (systemStatusBar.address() == 0L) {
                    log.warn("[NSStatusBar systemStatusBar] returned NULL")
                    return@runCatching null
                }

                log.info("[create] step 4/5: statusItemWithLength: (square)")
                // Square length forces a fixed visible slot — variable
                // sizing can collapse to zero width when the icon
                // pipeline doesn't fully register, leaving an
                // invisible status item that's "there" technically
                // but indistinguishable from absence.
                val statusItem = bindings.handle("objc_msgSend_id_double")
                    .invokeExact(
                        systemStatusBar, bindings.sel("statusItemWithLength:"),
                        ObjcBindings.NS_SQUARE_STATUS_ITEM_LENGTH,
                    ) as MemorySegment
                if (statusItem.address() == 0L) {
                    log.warn("statusItemWithLength: returned NULL")
                    return@runCatching null
                }
                val retainedItem = bindings.handle("objc_retain")
                    .invokeExact(statusItem) as MemorySegment

                log.info("[create] step 5/5: get statusItem.button")
                val button = bindings.handle("objc_msgSend_id")
                    .invokeExact(retainedItem, bindings.sel("button")) as MemorySegment
                if (button.address() == 0L) {
                    log.warn("statusItem.button returned NULL — older macOS without NSStatusBarButton API?")
                    bindings.handle("objc_release").invokeExact(retainedItem) as Unit
                    return@runCatching null
                }

                // Force visibility — newer macOS sometimes treats status
                // items as hidden by default until an explicit setVisible:.
                runCatching {
                    bindings.handle("objc_msgSend_void_long").invokeExact(
                        retainedItem, bindings.sel("setVisible:"), 1L,
                    ) as Unit
                }
                log.info("AppKit status item up: 0x{}", retainedItem.address().toString(16))
                AppKitTrayImpl(bindings, retainedItem, button, instanceCounter.incrementAndGet(), builder, icon) as Tray
            }.onFailure { log.warn("AppKit tray construction threw", it) }.getOrNull()
        }

        /**
         * Ask Cocoa whether the caller is on the main thread.
         * Equivalent to `[NSThread isMainThread]`: true only when the calling
         * thread is OS thread 0, which is where every AppKit operation has to
         * run. Decides whether create(), the setters and close() call AppKit
         * inline or go through the main queue.
         */
        private fun isCocoaMainThread(bindings: ObjcBindings): Boolean {
            return runCatching {
                val nsThreadCls = bindings.cls("NSThread")
                // Class method — the receiver is the class itself.
                // Returns BOOL (1 byte but msgSend treats as long here
                // since we declared the variant that way).
                val ret = bindings.handle("objc_msgSend_long")
                    .invokeExact(nsThreadCls, bindings.sel("isMainThread")) as Long
                // BOOL comes back in the low byte. The rest of the register
                // is unspecified, as in isNSAppRunning.
                (ret and 0xffL) != 0L
            }.getOrElse {
                log.warn("[NSThread isMainThread] threw, assuming non-main", it)
                false
            }
        }

        /**
         * Cocoa requires `NSApplication` to be initialised AND to have
         * an activation policy that permits UI surfaces before
         * `NSStatusBar` items become visible in the menu bar.
         *
         *   1. `[NSApplication sharedApplication]` — materialises the
         *      singleton + AppKit infrastructure. Idempotent.
         *   2. `[NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory]`
         *      — accessory mode is the canonical "menu-bar agent" policy:
         *      no Dock icon, no ⌘-tab presence, but full eligibility to
         *      install NSStatusBar items. `Regular` (the previous choice)
         *      requires a real `.app` bundle to behave sanely; running
         *      `java …` with `Regular` gets weird half-state from
         *      Launch Services ("application is damaged", missing main
         *      menu). `Accessory` works for both bundled `.app` AND
         *      plain-JVM launches, so it's the correct default for a
         *      tray library.
         *
         * **Only when WE own NSApp.** Steps 1-2 are correct *only* when
         * libtray is bootstrapping NSApplication itself (headless / the
         * smoke harness). When a host UI toolkit (JavaFX/Glass, Compose
         * Desktop/Skiko, AWT) already created NSApp and is running its
         * event loop, re-running `finishLaunching` re-posts
         * `NSApplicationDidFinishLaunching` and flipping the activation
         * policy stomps the host's Dock/UI presence. Both destabilise the
         * host's run-loop machinery: on JavaFX/Intel this crashed Glass's
         * CVDisplayLink-based pulse timer with a SIGSEGV in `objc_msgSend`
         * (issue #5). So when `[NSApp isRunning]` reports the host already
         * owns the loop, leave NSApp untouched and just install the status
         * item: the host has already done the bootstrap we'd otherwise do.
         *
         * **Run loop ownership.** This method never calls `[NSApp run]`.
         * The host application owns the Cocoa main run loop; Compose
         * Desktop / Skiko / JavaFX do this by virtue of being Cocoa apps.
         * Headless callers (the smoke harness) must run their own
         * `[NSApp run]` on the main thread, otherwise NSStatusItem
         * registration messages queue up but never reach SystemUIServer
         * via mach IPC: the item exists in our process but never paints.
         */
        @Synchronized
        private fun ensureNSApplicationInitialised(bindings: ObjcBindings) {
            runCatching {
                val cls = bindings.cls("NSApplication")
                val app = bindings.handle("objc_msgSend_id")
                    .invokeExact(cls, bindings.sel("sharedApplication")) as MemorySegment
                if (app.address() == 0L) return@runCatching

                if (isNSAppRunning(bindings, app)) {
                    log.info("[create] NSApplication already running (host-owned); leaving activation policy + launch state to the host")
                    return@runCatching
                }

                bindings.handle("objc_msgSend_void_long").invokeExact(
                    app, bindings.sel("setActivationPolicy:"),
                    ObjcBindings.NS_APP_POLICY_ACCESSORY,
                ) as Unit
                // Required for status items to register with SystemUIServer:
                // without finishLaunching the app is "in flux" from
                // SystemUIServer's POV and refuses to paint our entry.
                runCatching {
                    bindings.handle("objc_msgSend_id")
                        .invokeExact(app, bindings.sel("finishLaunching")) as MemorySegment
                }
                log.info("[create] NSApplication activation policy = Accessory, finishLaunching fired")
            }.onFailure {
                log.warn("[NSApplication sharedApplication / setActivationPolicy:] threw, proceeding anyway", it)
            }
        }

        /**
         * `[NSApp isRunning]`: true once a host UI toolkit has called
         * `[NSApp run]` and its event loop is live. Used to decide whether
         * libtray must bootstrap NSApplication or should keep its hands off
         * a host-owned one (see [ensureNSApplicationInitialised]).
         *
         * `isRunning` returns a `BOOL` (one byte in `al` on x86_64); we
         * mask the low byte rather than trust the upper bits of the
         * 64-bit return register, which the ABI leaves undefined for
         * sub-word returns.
         */
        private fun isNSAppRunning(bindings: ObjcBindings, app: MemorySegment): Boolean {
            return runCatching {
                val ret = bindings.handle("objc_msgSend_long")
                    .invokeExact(app, bindings.sel("isRunning")) as Long
                (ret and 0xffL) != 0L
            }.getOrElse {
                log.warn("[NSApp isRunning] threw, assuming not running", it)
                false
            }
        }

        /**
         * Build the runtime `LibtrayMenuTarget_<pid>` class once per
         * JVM: subclass NSObject, add `-onMenuItem:(id)` and
         * `-onStatusItemClick:(id)`, each mapped to a Panama upcall stub,
         * register, instantiate.
         *
         * The class name carries the PID. That does not help a second copy of
         * libtray loaded by another classloader in the same process: it gets
         * the same name, objc_allocateClassPair returns nil and its create()
         * returns null. Idempotent, it returns early when already built.
         */
        @Synchronized
        private fun ensureMenuTargetClass(bindings: ObjcBindings) {
            if (menuTargetClass != null) return

            val arena = Arena.ofShared()
            val stub = ON_MENU_ITEM.stub(arena)
            val clickStub = ON_STATUS_ITEM_CLICK.stub(arena)
            menuActionStub = stub
            statusClickStub = clickStub
            stubArena = arena

            val nsObjectCls = bindings.cls("NSObject")
            val className = "LibtrayMenuTarget_${ProcessHandle.current().pid()}"
            val classNameSeg = arena.allocateFrom(className)
            val newClass = bindings.handle("objc_allocateClassPair")
                .invokeExact(nsObjectCls, classNameSeg, 0L) as MemorySegment
            require(newClass.address() != 0L) { "objc_allocateClassPair failed for $className" }

            // Method type encoding: "v@:@" = void return, takes (id self, SEL cmd, id sender)
            val typesSeg = arena.allocateFrom("v@:@")
            val sel = bindings.sel("onMenuItem:")
            val ok = bindings.handle("class_addMethod")
                .invokeExact(newClass, sel, stub, typesSeg) as Boolean
            require(ok) { "class_addMethod failed for onMenuItem:" }
            val clickOk = bindings.handle("class_addMethod")
                .invokeExact(newClass, bindings.sel("onStatusItemClick:"), clickStub, typesSeg) as Boolean
            require(clickOk) { "class_addMethod failed for onStatusItemClick:" }

            bindings.handle("objc_registerClassPair").invokeExact(newClass) as Unit

            // Single shared instance — alloc + init + retain to keep
            // it past any autorelease pool. NSObject's default init
            // is fine (no state needed; method routes via instance map).
            val allocated = bindings.handle("class_createInstance")
                .invokeExact(newClass, 0L) as MemorySegment
            val initialised = bindings.handle("objc_msgSend_id")
                .invokeExact(allocated, bindings.sel("init")) as MemorySegment
            val retained = bindings.handle("objc_retain")
                .invokeExact(initialised) as MemorySegment

            menuTargetClass = newClass
            menuTargetInstance = retained
            log.info("Registered runtime ObjC class {} + instance for menu target-action", className)
        }
    }
}
