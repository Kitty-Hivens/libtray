package dev.hivens.libtray.macos

import dev.hivens.libtray.EventDispatcher
import dev.hivens.libtray.IconScaling
import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayEventListener
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.TraySubscription
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 *   2. Lazily register one custom Objective-C target class per JVM —
 *      `LibtrayMenuTarget` — with `-onMenuItem:` and `-onStatusItemClick:`,
 *      each IMP a Panama upcall stub. Cocoa target-action requires an
 *      object, not a function pointer; this is the canonical bridge.
 *   3. Build the `NSStatusItem` via `[[NSStatusBar systemStatusBar]
 *      statusItemWithLength:NSVariableStatusItemLength]`. Retain it
 *      so it lives past the autorelease pool.
 *   4. Install initial icon (PNG → NSData → NSImage → button.image)
 *      and tooltip (button.toolTip).
 *   5. NSMenu rebuilt on every [setMenu] call — short-lived menus,
 *      always cheap to rebuild — with target-action wired to our
 *      shared LibtrayMenuTarget instance + `onMenuItem:` selector.
 *      Per-item NSInteger tag indexes the libtray id slot for reverse
 *      lookup in the upcall handler.
 *   6. A transparent `LibtrayStatusView` is laid over the status button
 *      and receives its mouse events, each with the `NSEvent` as an
 *      argument, so the button and modifiers are read from the click
 *      itself (see [handleStatusViewMouse]). The menu is not left attached
 *      to the status item, since an attached menu makes AppKit take the
 *      click: [popUpMenu] attaches it only for one `performClick:`. The
 *      button's own target-action stays for presses that do not come from
 *      the mouse, such as VoiceOver.
 *
 * **Threading.** Mutating calls (setIcon / setTooltip / setMenu) marshal
 * onto the Cocoa main queue via libdispatch (`dispatch_async_f`, see
 * [runOnMainQueue]): a call already on the main thread runs inline, any other
 * thread enqueues. AppKit therefore always runs where it wants regardless of
 * which thread the consumer calls from, and the public setters still return as
 * soon as the enqueue succeeds. The `onMenuItem:`, `onStatusItemClick:` and
 * status view mouse upcalls already arrive on the main thread by AppKit's
 * contract. [close] queues its teardown on the
 * main queue too, but waits for it, so the icon is gone when close()
 * returns (see [tearDownOnMainThread]).
 */
internal class AppKitTrayImpl private constructor(
    private val bindings: ObjcBindings,
    private val statusItem: MemorySegment,   // NSStatusItem* (retained)
    private val statusButton: MemorySegment, // NSStatusBarButton* (owned by statusItem)
    private val instanceId: Int,
    initial: TrayBuilder,
) : Tray {

    private val log = LoggerFactory.getLogger("libtray.AppKitTray")

    @Volatile private var open = AtomicBoolean(true)

    /** Set by the one [tearDown] that runs, wherever close() ended up running it. */
    private val tornDown = AtomicBoolean(false)
    private val events = EventDispatcher("libtray-events-${ProcessHandle.current().pid()}")

    /**
     * Per-item tag → libtray id. Tags grow monotonically from 1 within
     * an instance and are rebuilt on every [setMenu]; the upcall
     * handler walks live instances to find the owner.
     */
    private val tagToId = ConcurrentHashMap<Int, String>()
    private val nextTag = AtomicInteger(1)

    /** Currently-installed NSMenu* (retained), or NULL when no menu set. */
    @Volatile private var currentMenu: MemorySegment = MemorySegment.NULL

    /** See [dev.hivens.libtray.TrayBuilder.maxIconSize]; null disables scaling. */
    private val maxIconSize: Int? = initial.maxIconSize

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

    init {
        INSTANCE_REGISTRY[instanceId] = this
        installClickHandler()
        // Belt-and-suspenders: set a short text title FIRST so even if
        // the image pipeline silently fails (NSImage decoded fine but
        // SystemUIServer drops it), the menu bar shows SOMETHING. The
        // title gets overwritten visually by setImage if the image
        // does succeed; otherwise it stays as the visible fallback.
        applyTitleFallback("●")
        if (initial.iconBytes.isNotEmpty()) applyIcon(initial.iconBytes)
        initial.tooltip?.takeIf { it.isNotEmpty() }?.let { applyTooltip(it) }
        initial.menu?.let { applyMenu(it) }
    }

    override val isOpen: Boolean get() = open.get()

    override fun setIcon(iconBytes: ByteArray): Boolean {
        if (!open.get()) return false
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
        return runCatching { applyIcon(iconBytes); true }.getOrElse { t ->
            log.warn("setIcon threw: {}", t.message); false
        }
    }

    override fun setTooltip(text: String): Boolean {
        if (!open.get()) return false
        return runCatching { applyTooltip(text); true }.getOrElse { t ->
            log.warn("setTooltip threw: {}", t.message); false
        }
    }

    override fun setMenu(menu: TrayMenu?): Boolean {
        if (!open.get()) return false
        return runCatching {
            if (menu == null) clearMenu() else applyMenu(menu)
            true
        }.getOrElse { t ->
            log.warn("setMenu threw: {}", t.message); false
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
        // Linux / Win32 backends). runOnMainQueue can leave actions queued on
        // the GCD main queue that still reference this instance's msgSend
        // handles, and the menu-target class + its upcall stub are already
        // process-lifetime; closing the arena would risk a use-after-free for a
        // deferred action. The cost is a one-time set of downcall handles,
        // reclaimed at process exit.
    }

    /**
     * Run [tearDown] on the Cocoa main thread and wait for it, bounded.
     *
     * `NSStatusItem` is main-thread-only, and close() is routinely called
     * from a listener, which runs on the libtray event thread. Queuing the
     * teardown also puts it behind any apply* the main queue is already
     * running or holding, so none of them can message a released status
     * item. Waiting keeps close() synchronous: the icon is gone when it
     * returns. The wait is bounded because a main thread that is blocked,
     * or a process that never runs a Cocoa loop, would otherwise hang the
     * caller. On timeout the teardown runs on the calling thread instead,
     * since leaving the icon up is worse, and [tornDown] keeps the queued
     * copy from running it a second time.
     */
    private fun tearDownOnMainThread() {
        if (isCocoaMainThread(bindings) || bindings.mainQueue.address() == 0L) {
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
        if (enqueued && done.await(CLOSE_ON_MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return
        log.warn(
            "Cocoa main queue did not run the tray teardown within {} ms; tearing down on {} instead",
            CLOSE_ON_MAIN_TIMEOUT_MS, Thread.currentThread().name,
        )
        tearDown()
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
            val prev = currentMenu
            currentMenu = MemorySegment.NULL
            if (prev.address() != 0L) {
                runCatching {
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        statusItem, bindings.sel("setMenu:"), MemorySegment.NULL,
                    ) as Unit
                }
                runCatching { bindings.handle("objc_release").invokeExact(prev) as Unit }
            }
            tagToId.clear()
            if (statusView.address() != 0L) {
                runCatching {
                    bindings.handle("objc_msgSend_void").invokeExact(statusView, bindings.sel("removeFromSuperview")) as Unit
                }
                runCatching { bindings.handle("objc_release").invokeExact(statusView) as Unit }
                statusView = MemorySegment.NULL
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
    private fun applyIcon(rawPngBytes: ByteArray) {
        // Scale on the calling thread, before the marshal: it is pure JVM
        // image work with no AppKit involvement, and doing it here keeps
        // the main queue free of a full-resolution decode + re-encode.
        val pngBytes = IconScaling.fit(rawPngBytes, maxIconSize, log)
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
     * Build a fresh NSMenu, wire each item's target-action to
     * [menuTargetInstance] + onMenuItem: selector, and keep it in
     * [currentMenu] for [popUpMenu]. The previous menu is released. If it
     * is on screen right now, the status item still holds its own
     * reference for as long as [popUpMenu] keeps it attached.
     */
    private fun applyMenu(menu: TrayMenu) {
        runOnMainQueue {
            autoreleasepool {
                tagToId.clear()
                val nsMenuCls = bindings.cls("NSMenu")
                val emptyTitle = bindings.nsString("")
                val allocatedMenu = bindings.handle("objc_msgSend_id")
                    .invokeExact(nsMenuCls, bindings.sel("alloc")) as MemorySegment
                val newMenu = bindings.handle("objc_msgSend_id_id")
                    .invokeExact(allocatedMenu, bindings.sel("initWithTitle:"), emptyTitle) as MemorySegment
                disableAutoenabling(newMenu)

                appendItems(newMenu, menu.items)

                // The alloc +1 IS our strong ref, parked in currentMenu.
                val prev = currentMenu
                currentMenu = newMenu
                if (prev.address() != 0L) {
                    bindings.handle("objc_release").invokeExact(prev) as Unit
                }
            }
        }
    }

    private fun clearMenu() {
        runOnMainQueue {
            autoreleasepool {
                val prev = currentMenu
                currentMenu = MemorySegment.NULL
                if (prev.address() != 0L) {
                    runCatching { bindings.handle("objc_release").invokeExact(prev) as Unit }
                }
                tagToId.clear()
            }
        }
    }

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

    /**
     * Recursively append [items] into [parentMenu] (NSMenu*). Standard
     * items get a fresh tag mapped to their libtray id; submenus build
     * a child NSMenu attached via setSubmenu:. Separators use
     * `[NSMenuItem separatorItem]`.
     */
    private fun appendItems(parentMenu: MemorySegment, items: List<TrayMenuItem>) {
        val nsMenuItemCls = bindings.cls("NSMenuItem")
        val onSelector = bindings.sel("onMenuItem:")
        val emptyKey = bindings.nsString("")

        for (item in items) {
            when (item) {
                is TrayMenuItem.Separator -> {
                    val sep = bindings.handle("objc_msgSend_id")
                        .invokeExact(nsMenuItemCls, bindings.sel("separatorItem")) as MemorySegment
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        parentMenu, bindings.sel("addItem:"), sep,
                    ) as Unit
                }
                is TrayMenuItem.Standard -> {
                    val tag = nextTag.getAndIncrement()
                    tagToId[tag] = item.id
                    val title = bindings.nsString(item.label)
                    val allocated = bindings.handle("objc_msgSend_id")
                        .invokeExact(nsMenuItemCls, bindings.sel("alloc")) as MemorySegment
                    val menuItem = bindings.handle("objc_msgSend_id_id_sel_id")
                        .invokeExact(
                            allocated, bindings.sel("initWithTitle:action:keyEquivalent:"),
                            title, onSelector, emptyKey,
                        ) as MemorySegment
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        menuItem, bindings.sel("setTarget:"), menuTargetInstance,
                    ) as Unit
                    bindings.handle("objc_msgSend_void_long").invokeExact(
                        menuItem, bindings.sel("setTag:"), tag.toLong(),
                    ) as Unit
                    if (!item.enabled) {
                        bindings.handle("objc_msgSend_void_long").invokeExact(
                            menuItem, bindings.sel("setEnabled:"), 0L,
                        ) as Unit
                    }
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        parentMenu, bindings.sel("addItem:"), menuItem,
                    ) as Unit
                    // addItem: retains the item; release our alloc +1 so each
                    // NSMenuItem doesn't leak on every setMenu.
                    bindings.handle("objc_release").invokeExact(menuItem) as Unit
                }
                is TrayMenuItem.Submenu -> {
                    val title = bindings.nsString(item.label)
                    val allocated = bindings.handle("objc_msgSend_id")
                        .invokeExact(nsMenuItemCls, bindings.sel("alloc")) as MemorySegment
                    val parentItem = bindings.handle("objc_msgSend_id_id_sel_id")
                        .invokeExact(
                            allocated, bindings.sel("initWithTitle:action:keyEquivalent:"),
                            title, MemorySegment.NULL, emptyKey,
                        ) as MemorySegment
                    if (!item.enabled) {
                        bindings.handle("objc_msgSend_void_long").invokeExact(
                            parentItem, bindings.sel("setEnabled:"), 0L,
                        ) as Unit
                    }
                    val nsMenuCls = bindings.cls("NSMenu")
                    val emptyTitle = bindings.nsString("")
                    val childAllocated = bindings.handle("objc_msgSend_id")
                        .invokeExact(nsMenuCls, bindings.sel("alloc")) as MemorySegment
                    val childMenu = bindings.handle("objc_msgSend_id_id")
                        .invokeExact(childAllocated, bindings.sel("initWithTitle:"), emptyTitle) as MemorySegment
                    disableAutoenabling(childMenu)
                    appendItems(childMenu, item.items)
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        parentItem, bindings.sel("setSubmenu:"), childMenu,
                    ) as Unit
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        parentMenu, bindings.sel("addItem:"), parentItem,
                    ) as Unit
                    // setSubmenu: / addItem: each retain; release our alloc +1s.
                    bindings.handle("objc_release").invokeExact(childMenu) as Unit
                    bindings.handle("objc_release").invokeExact(parentItem) as Unit
                }
            }
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
     * constraints, which avoids passing an `NSRect` by value. The
     * target-action only fires for presses that do not reach the overlay,
     * such as VoiceOver.
     */
    private fun installClickHandler() {
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
            log.warn("Installing the status item click handling failed, clicks will not reach the app: {}", it.message)
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

    /** A press that did not come through the overlay, such as VoiceOver. Counts as a primary click. */
    private fun handleStatusItemAction() {
        if (menuOpen) return
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
            if (!tornDown.get()) {
                runCatching {
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        statusItem, bindings.sel("setMenu:"), MemorySegment.NULL,
                    ) as Unit
                }
            }
            runCatching { bindings.handle("objc_release").invokeExact(menu) as Unit }
            runCatching { bindings.handle("objc_release").invokeExact(statusItem) as Unit }
        }
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
     * Run [action] on the Cocoa main queue (issue #3). Already on the main
     * thread -> run inline (dispatch_async would needlessly defer a tick).
     * Otherwise enqueue via `dispatch_async_f`. If libdispatch did not resolve
     * ([ObjcBindings.mainQueue] is NULL) fall back to running inline.
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
        if (bindings.mainQueue.address() == 0L) {
            runCatching { action() }
            return
        }
        enqueueOnMainQueue { if (open.get()) action() }
    }

    /** `dispatch_async_f` [action] onto the main queue. False when the enqueue itself failed. */
    private fun enqueueOnMainQueue(action: () -> Unit): Boolean {
        val id = dispatchCounter.getAndIncrement()
        PENDING[id] = action
        val enqueued = runCatching {
            bindings.handle("dispatch_async_f").invokeExact(
                bindings.mainQueue, MemorySegment.ofAddress(id), dispatchTrampolineStub(),
            ) as Unit
            true
        }.getOrDefault(false)
        if (!enqueued) PENDING.remove(id)  // never came back; don't strand the entry
        return enqueued
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libtray.AppKitTray")
        private val instanceCounter = AtomicInteger(0)

        /** instance id → impl, for upcall dispatch. */
        private val INSTANCE_REGISTRY = ConcurrentHashMap<Int, AppKitTrayImpl>()

        /** How long close() waits for the main queue to run the teardown. Matches the other backends' join budget. */
        private const val CLOSE_ON_MAIN_TIMEOUT_MS: Long = 2_000

        /** `NSEventModifierFlagControl` from NSEvent.h. */
        private const val NS_EVENT_MODIFIER_FLAG_CONTROL: Long = 1L shl 18

        /** `[NSEvent buttonNumber]` of the middle button. 0 is left, 1 right. */
        private const val MIDDLE_BUTTON_NUMBER: Long = 2

        // ── Main-queue marshaling (issue #3) ──────────────────────────────
        // Pending runOnMainQueue actions, keyed by a monotonic id handed to
        // dispatch_async_f as the opaque context pointer. The trampoline looks
        // the action up by id, runs it, drops it. One process-wide upcall stub
        // serves every instance.
        private val PENDING = ConcurrentHashMap<Long, () -> Unit>()
        private val dispatchCounter = AtomicLong(0)
        @Volatile private var trampolineStub: MemorySegment? = null
        @Volatile private var trampolineArena: Arena? = null

        /** GCD work function `void f(void* context)`: run the pending action the context id names. */
        @JvmStatic
        fun dispatchTrampoline(context: MemorySegment) {
            val action = PENDING.remove(context.address()) ?: return
            runCatching { action() }.onFailure { log.warn("main-queue action threw: {}", it.message) }
        }

        /** The single shared upcall stub bridging GCD to [dispatchTrampoline]. Built once, process-lifetime. */
        @Synchronized
        private fun dispatchTrampolineStub(): MemorySegment {
            trampolineStub?.let { return it }
            val arena = Arena.ofShared()
            val handle = MethodHandles.lookup().findStatic(
                AppKitTrayImpl::class.java, "dispatchTrampoline",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java),
            )
            val stub = Linker.nativeLinker().upcallStub(
                handle, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), arena,
            )
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
         * We only care about `sender` (the NSMenuItem*); read its tag,
         * find the owning Tray instance, fire the libtray event.
         */
        @JvmStatic
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onMenuItemEntry(self: MemorySegment, cmd: MemorySegment, sender: MemorySegment) {
            val bindings = lastBindings ?: return
            val tag = try {
                (bindings.handle("objc_msgSend_long")
                    .invokeExact(sender, bindings.sel("tag")) as Long).toInt()
            } catch (t: Throwable) {
                log.warn("onMenuItem: tag read threw: {}", t.message)
                return
            }
            for (inst in INSTANCE_REGISTRY.values) {
                val id = inst.tagToId[tag] ?: continue
                inst.fire(TrayEvent.MenuItemSelected(id))
                return
            }
            // Tag fell through every instance — typically means the
            // menu was rebuilt between Cocoa's mouse-down and our
            // handler running (race on rapid setMenu calls). Drop
            // silently; no event but no crash either.
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
                .onFailure { log.warn("onStatusItemClick: handling threw: {}", it.message) }
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
                .onFailure { log.warn("status view mouse handling threw: {}", it.message) }
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
            val handle = MethodHandles.lookup().findStatic(
                AppKitTrayImpl::class.java,
                "onStatusViewMouseEntry",
                MethodType.methodType(
                    Void.TYPE,
                    MemorySegment::class.java,   // self (the overlay)
                    MemorySegment::class.java,   // _cmd (SEL)
                    MemorySegment::class.java,   // NSEvent*
                ),
            )
            val stub = Linker.nativeLinker().upcallStub(
                handle,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                arena,
            )
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

        /** Last-loaded bindings, used by the static upcall handler. */
        @Volatile private var lastBindings: ObjcBindings? = null

        fun create(builder: TrayBuilder): Tray? {
            val bindings = ObjcBindings.load() ?: run {
                log.info("Objective-C runtime / AppKit not loadable — AppKit tray unavailable")
                return null
            }
            lastBindings = bindings

            // Caller-thread check. NSStatusItem and every NSWindow
            // constructor it delegates to are pinned to OS thread 0
            // (Cocoa main thread). On a JVM that's the JVM main thread
            // ONLY when launched with `-XstartOnFirstThread`. Without
            // it, AppKit will throw `NSInternalInconsistencyException`
            // mid-construction — surface a clean nullable return + a
            // pointer at the fix instead, which downstream consumers
            // can act on. (Also detects calls from worker threads of
            // a properly-flagged process.)
            if (!isCocoaMainThread(bindings)) {
                log.warn(
                    "AppKit tray must be created on the Cocoa main thread (OS thread 0). " +
                        "Add `-XstartOnFirstThread` to the JVM launch args, and call Tray.create " +
                        "from your application's main thread. Current thread: {}",
                    Thread.currentThread().name,
                )
                return null
            }

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
                AppKitTrayImpl(bindings, retainedItem, button, instanceCounter.incrementAndGet(), builder) as Tray
            }.onFailure { log.warn("AppKit tray construction threw: {}", it.message) }.getOrNull()
        }

        /**
         * Ask Cocoa whether the caller is on the main thread.
         * Equivalent to `[NSThread isMainThread]` — returns true only
         * when the calling thread is OS thread 0, which is what every
         * AppKit ops MUST run on. Used as a fail-fast guard before any
         * NSStatusBar interaction, with a clear log pointing at the
         * `-XstartOnFirstThread` JVM flag for the typical
         * misconfiguration.
         */
        private fun isCocoaMainThread(bindings: ObjcBindings): Boolean {
            return runCatching {
                val nsThreadCls = bindings.cls("NSThread")
                // Class method — the receiver is the class itself.
                // Returns BOOL (1 byte but msgSend treats as long here
                // since we declared the variant that way).
                val ret = bindings.handle("objc_msgSend_long")
                    .invokeExact(nsThreadCls, bindings.sel("isMainThread")) as Long
                ret != 0L
            }.getOrElse {
                log.warn("[NSThread isMainThread] threw: {} — assuming non-main", it.message)
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
                log.warn("[NSApplication sharedApplication / setActivationPolicy:] threw: {}; proceeding anyway", it.message)
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
                log.warn("[NSApp isRunning] threw: {}; assuming not running", it.message)
                false
            }
        }

        /**
         * Build the runtime `LibtrayMenuTarget_<pid>` class once per
         * JVM: subclass NSObject, add `-onMenuItem:(id)` and
         * `-onStatusItemClick:(id)`, each mapped to a Panama upcall stub,
         * register, instantiate.
         *
         * Class name is suffixed with PID so re-loading libtray (e.g.
         * test harnesses, isolated classloaders) doesn't trip
         * "class already registered". Idempotent — early-returns when
         * already built.
         */
        @Synchronized
        private fun ensureMenuTargetClass(bindings: ObjcBindings) {
            if (menuTargetClass != null) return

            val arena = Arena.ofShared()
            val handle = MethodHandles.lookup().findStatic(
                AppKitTrayImpl::class.java,
                "onMenuItemEntry",
                MethodType.methodType(
                    Void.TYPE,
                    MemorySegment::class.java,   // self (id)
                    MemorySegment::class.java,   // _cmd (SEL)
                    MemorySegment::class.java,   // sender (id)
                ),
            )
            val descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            )
            val stub = Linker.nativeLinker().upcallStub(handle, descriptor, arena)
            val clickHandle = MethodHandles.lookup().findStatic(
                AppKitTrayImpl::class.java,
                "onStatusItemClickEntry",
                handle.type(),
            )
            val clickStub = Linker.nativeLinker().upcallStub(clickHandle, descriptor, arena)
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
