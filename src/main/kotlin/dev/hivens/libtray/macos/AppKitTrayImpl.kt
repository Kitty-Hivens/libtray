package dev.hivens.libtray.macos

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
 *   2. Lazily register one custom Objective-C target class per JVM —
 *      `LibtrayMenuTarget` — with one method `-onMenuItem:` whose IMP
 *      is a Panama upcall stub. Cocoa target-action requires an
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
 *
 * **Threading.** All AppKit calls below run on the caller's thread.
 * macOS technically wants AppKit on the main thread; in practice
 * NSStatusItem operations are forgiving, but rare edge cases (high-load
 * menu rebuilds during animations) can warn. Aura's tray lifecycle
 * runs on a dedicated I/O coroutine, single-threaded for tray-state
 * mutations — no concurrent access. Smoke harness is single-threaded
 * too. If a downstream emerges that hammers the API concurrently, wrap
 * calls in `dispatch_async(dispatch_get_main_queue, ^{ ... })` — needs
 * libdispatch bindings, deferred until needed.
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
    private val handlers = CopyOnWriteArrayList<(TrayEvent) -> Unit>()

    /**
     * Per-item tag → libtray id. Tags grow monotonically from 1 within
     * an instance and are rebuilt on every [setMenu]; the upcall
     * handler walks live instances to find the owner.
     */
    private val tagToId = ConcurrentHashMap<Int, String>()
    private val nextTag = AtomicInteger(1)

    /** Currently-installed NSMenu* (retained), or NULL when no menu set. */
    @Volatile private var currentMenu: MemorySegment = MemorySegment.NULL

    init {
        INSTANCE_REGISTRY[instanceId] = this
        if (initial.iconBytes.isNotEmpty()) {
            applyIcon(initial.iconBytes)
        }
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

    override fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        runCatching { clearMenu() }
        // [[NSStatusBar systemStatusBar] removeStatusItem:item]
        runCatching {
            val nsStatusBarCls = bindings.cls("NSStatusBar")
            val systemStatusBar = bindings.handle("objc_msgSend_id")
                .invokeExact(nsStatusBarCls, bindings.sel("systemStatusBar")) as MemorySegment
            bindings.handle("objc_msgSend_void_id").invokeExact(
                systemStatusBar, bindings.sel("removeStatusItem:"), statusItem,
            ) as Unit
        }
        // Release our retained reference to the status item itself
        runCatching {
            bindings.handle("objc_release").invokeExact(statusItem) as Unit
        }
        INSTANCE_REGISTRY.remove(instanceId)
    }

    // ── Internals: AppKit operations ─────────────────────────────────────

    /**
     * PNG bytes → NSImage → install on the status item's button.
     * `[NSImage initWithData:]` decodes any format AppKit understands;
     * PNG is universally supported. Released to autorelease pool —
     * the button retains the image when set.
     */
    private fun applyIcon(pngBytes: ByteArray) {
        autoreleasepool {
            val nsData = bindings.nsData(pngBytes)
            val nsImageCls = bindings.cls("NSImage")
            val allocated = bindings.handle("objc_msgSend_id")
                .invokeExact(nsImageCls, bindings.sel("alloc")) as MemorySegment
            val image = bindings.handle("objc_msgSend_id_id")
                .invokeExact(allocated, bindings.sel("initWithData:"), nsData) as MemorySegment
            if (image.address() == 0L) {
                log.warn("[NSImage initWithData:] returned NULL — invalid PNG?")
                return@autoreleasepool
            }
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusButton, bindings.sel("setImage:"), image,
            ) as Unit
        }
    }

    private fun applyTooltip(text: String) {
        autoreleasepool {
            val nsString = bindings.nsString(text)
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusButton, bindings.sel("setToolTip:"), nsString,
            ) as Unit
        }
    }

    /**
     * Build a fresh NSMenu, wire each item's target-action to
     * [menuTargetInstance] + onMenuItem: selector, attach via
     * `setMenu:`. Old menu released — NSStatusItem retains the new
     * one when [setMenu:] lands, so we drop our previous strong ref.
     */
    private fun applyMenu(menu: TrayMenu) {
        autoreleasepool {
            tagToId.clear()
            val nsMenuCls = bindings.cls("NSMenu")
            val emptyTitle = bindings.nsString("")
            val allocatedMenu = bindings.handle("objc_msgSend_id")
                .invokeExact(nsMenuCls, bindings.sel("alloc")) as MemorySegment
            val newMenu = bindings.handle("objc_msgSend_id_id")
                .invokeExact(allocatedMenu, bindings.sel("initWithTitle:"), emptyTitle) as MemorySegment

            appendItems(newMenu, menu.items)

            // Retain BEFORE swapping so we own the new menu beyond
            // the autorelease pool. setMenu: also retains internally,
            // but we want a clean lifecycle owned at our level.
            val retainedNew = bindings.handle("objc_retain")
                .invokeExact(newMenu) as MemorySegment

            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusItem, bindings.sel("setMenu:"), retainedNew,
            ) as Unit

            val prev = currentMenu
            currentMenu = retainedNew
            if (prev.address() != 0L) {
                bindings.handle("objc_release").invokeExact(prev) as Unit
            }
        }
    }

    private fun clearMenu() {
        autoreleasepool {
            bindings.handle("objc_msgSend_void_id").invokeExact(
                statusItem, bindings.sel("setMenu:"), MemorySegment.NULL,
            ) as Unit
            val prev = currentMenu
            currentMenu = MemorySegment.NULL
            if (prev.address() != 0L) {
                runCatching { bindings.handle("objc_release").invokeExact(prev) as Unit }
            }
            tagToId.clear()
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
                    appendItems(childMenu, item.items)
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        parentItem, bindings.sel("setSubmenu:"), childMenu,
                    ) as Unit
                    bindings.handle("objc_msgSend_void_id").invokeExact(
                        parentMenu, bindings.sel("addItem:"), parentItem,
                    ) as Unit
                }
            }
        }
    }

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

    private fun fire(event: TrayEvent) {
        for (h in handlers) {
            runCatching { h(event) }.onFailure { log.warn("event handler threw", it) }
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libtray.AppKitTray")
        private val instanceCounter = AtomicInteger(0)

        /** instance id → impl, for upcall dispatch. */
        private val INSTANCE_REGISTRY = ConcurrentHashMap<Int, AppKitTrayImpl>()

        /**
         * The shared `LibtrayMenuTarget_<pid>` Objective-C class built
         * once per JVM. Single method `-onMenuItem:(id)sender` routes
         * NSMenuItem clicks back into Java via [onMenuItemEntry].
         */
        @Volatile private var menuTargetClass: MemorySegment? = null
        @Volatile private var menuTargetInstance: MemorySegment = MemorySegment.NULL
        @Volatile private var menuActionStub: MemorySegment? = null
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

        /** Last-loaded bindings, used by the static upcall handler. */
        @Volatile private var lastBindings: ObjcBindings? = null

        fun create(builder: TrayBuilder): Tray? {
            val bindings = ObjcBindings.load() ?: run {
                log.info("Objective-C runtime / AppKit not loadable — AppKit tray unavailable")
                return null
            }
            lastBindings = bindings

            return runCatching {
                ensureMenuTargetClass(bindings)

                val nsStatusBarCls = bindings.cls("NSStatusBar")
                val systemStatusBar = bindings.handle("objc_msgSend_id")
                    .invokeExact(nsStatusBarCls, bindings.sel("systemStatusBar")) as MemorySegment
                if (systemStatusBar.address() == 0L) {
                    log.warn("[NSStatusBar systemStatusBar] returned NULL")
                    return@runCatching null
                }
                val statusItem = bindings.handle("objc_msgSend_id_double")
                    .invokeExact(
                        systemStatusBar, bindings.sel("statusItemWithLength:"),
                        ObjcBindings.NS_VARIABLE_STATUS_ITEM_LENGTH,
                    ) as MemorySegment
                if (statusItem.address() == 0L) {
                    log.warn("statusItemWithLength: returned NULL")
                    return@runCatching null
                }
                // Retain so the system status bar's autorelease pool
                // doesn't reclaim it the moment the calling thread
                // returns to its run loop.
                val retainedItem = bindings.handle("objc_retain")
                    .invokeExact(statusItem) as MemorySegment

                val button = bindings.handle("objc_msgSend_id")
                    .invokeExact(retainedItem, bindings.sel("button")) as MemorySegment
                if (button.address() == 0L) {
                    log.warn("statusItem.button returned NULL — older macOS without NSStatusBarButton API?")
                    bindings.handle("objc_release").invokeExact(retainedItem) as Unit
                    return@runCatching null
                }

                log.info("AppKit status item up: 0x{}", retainedItem.address().toString(16))
                AppKitTrayImpl(bindings, retainedItem, button, instanceCounter.incrementAndGet(), builder) as Tray
            }.onFailure { log.warn("AppKit tray construction threw: {}", it.message) }.getOrNull()
        }

        /**
         * Build the runtime `LibtrayMenuTarget_<pid>` class once per
         * JVM: subclass NSObject, add one method `-onMenuItem:(id)`
         * mapped to our Panama upcall stub, register, instantiate.
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
            menuActionStub = stub
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
