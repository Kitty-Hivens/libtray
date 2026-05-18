package dev.hivens.libtray.linux

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Linux tray backend — `org.kde.StatusNotifierItem` over D-Bus session
 * bus, registered with `org.kde.StatusNotifierWatcher`. Pure libdbus via
 * Project Panama (see [DBusBindings]); no GTK / GLib / libayatana
 * transitive dependencies.
 *
 * Wire-level dance, in order:
 *   1. Connect to the session bus.
 *   2. Request our own well-known name `org.kde.StatusNotifierItem-PID-N`
 *      (PID + a per-process counter for the rare multi-tray case).
 *   3. The desktop's tray host (KDE plasmashell, GNOME's SNI extension,
 *      waybar on Hyprland, etc.) listens on
 *      `org.kde.StatusNotifierWatcher.RegisterStatusNotifierItem` —
 *      we call it with our well-known name.
 *   4. The host then queries our `/StatusNotifierItem` object's
 *      properties via `org.freedesktop.DBus.Properties.Get` and
 *      `GetAll` and renders the icon. Property responses are the bulk
 *      of [dispatchOne] below.
 *   5. User clicks: host invokes `Activate` / `SecondaryActivate` /
 *      `ContextMenu` / `Scroll` on us. We fire the matching
 *      [TrayEvent] to subscribers.
 *   6. State change: we emit `NewIcon` / `NewToolTip` / `NewStatus`
 *      signals so the host re-fetches.
 *
 * Polling-loop dispatch (thread `libtray-sni-PID`): no Panama upcall
 * stubs needed; libdbus messages are pulled with
 * `dbus_connection_pop_message` and dispatched on our thread. Trade-
 * off vs `dbus_connection_register_object_path` (would need
 * `Linker.upcallStub` to give libdbus a callable C function pointer
 * — significantly more complex).
 *
 * Menu: this commit ships click-only. The right-click `ContextMenu`
 * method fires a [TrayEvent.MenuRequested] event so the host app can
 * show its own popup (Compose / Swing / JavaFX). The full DBusMenu
 * (`com.canonical.dbusmenu`) protocol implementation that lets the
 * desktop's own menu UI render the [TrayMenu] is a follow-up commit.
 * `Menu` property reports "/" (no DBusMenu) until then.
 */
internal class SniTrayImpl private constructor(
    private val bindings: DBusBindings,
    private val connection: MemorySegment,
    private val itemId: String,
    initial: TrayBuilder,
) : Tray {

    private val log = LoggerFactory.getLogger("libtray.SniTray")

    /**
     * SNI `Id` property — must be a stable, application-wide slug per spec
     * ("name that should be unique for this application and consistent
     * between sessions"). KDE Plasma surfaces this in tooltips when it
     * doesn't recognise the app, so leaking the auto-generated bus name
     * (`org.kde.StatusNotifierItem-PID-N`) here produces visually broken
     * tooltips of the form `<bus-name> • <Title> — <ToolTip.body>`.
     * Derive a slug from the human title instead.
     */
    private val appId: String = slugifyForSni(initial.title)

    /**
     * SNI `Title` property base — used when no [setTooltip] override has
     * been pushed yet. After the first setTooltip call, the override
     * wins. We never *compose* (e.g. "$appTitle — $tooltip"): hosts that
     * also resolve a `.desktop` Name from [appId] would render that
     * resolved name AND our composed Title, producing visible
     * duplication ("App — App — status"). Single string, fully
     * caller-controlled, no host can double-up.
     */
    private val appTitle: String = initial.title

    /** Returns the Title currently shown to hosts: tooltip override if any, else appTitle. */
    private fun currentTitle(): String = tooltip.ifBlank { appTitle }

    @Volatile private var iconBytes: ByteArray = initial.iconBytes
    @Volatile private var iconPixmap: List<Pixmap> = pngToPixmaps(initial.iconBytes)
    @Volatile private var tooltip: String = initial.tooltip ?: ""
    @Volatile private var menu: TrayMenu? = initial.menu
    @Volatile private var menuLayout: DBusMenuLayout? = initial.menu?.let(::DBusMenuLayout)
    private val layoutRevision = AtomicInteger(0)
    @Volatile private var status: String = "Active"
    @Volatile private var open = AtomicBoolean(true)

    private val handlers = CopyOnWriteArrayList<(TrayEvent) -> Unit>()

    /** Background dispatch thread — pulls messages, hands to [dispatchOne]. */
    private val pumpThread = Thread({ pumpLoop() }, "libtray-sni-${ProcessHandle.current().pid()}").apply {
        isDaemon = true
    }

    /**
     * Outgoing D-Bus messages awaiting send. Public API methods (setTooltip,
     * setMenu, setIcon, replyEmpty etc.) enqueue here and return immediately;
     * the [senderThread] picks each one up, calls `dbus_connection_send` +
     * flush + unref, and moves on.
     *
     * Pre-fix: every public call did send + flush + unref synchronously on
     * the caller thread. `dbus_connection_flush` blocks until the message
     * actually leaves on the wire, which on a busy session bus can take
     * up to several hundred ms per call. UI consumers (Compose Desktop,
     * Swing) call this from the EDT — so during a state-change burst
     * (e.g. a "launch starting" tray-status update emits two signals back
     * to back) the EDT could stall for 1-2 s, producing a fully-frozen
     * application window. Captured in the field via Aura's puppet diag
     * snapshot on 2026-05-18: `AWT-EventQueue-0` `RUNNABLE inNative=true`
     * deep in `DowncallStub.invoke` -> `sendAndUnref`.
     *
     * Unbounded by design. The realistic upper bound on queue depth is
     * tens of messages (setMenu fans out into a single LayoutUpdated;
     * setTooltip fans out into two; setIcon into one). A misbehaving
     * caller hammering setMenu() in a tight loop would still bottleneck
     * on the sender thread's flush rate, not on memory.
     */
    private val outgoing = LinkedBlockingQueue<MemorySegment>()

    /** Sender thread — drains [outgoing], performs the blocking flush there instead of on the caller. */
    private val senderThread = Thread({ senderLoop() }, "libtray-sni-sender-${ProcessHandle.current().pid()}").apply {
        isDaemon = true
    }

    init {
        pumpThread.start()
        senderThread.start()
    }

    override val isOpen: Boolean get() = open.get()

    override fun setTooltip(text: String): Boolean {
        if (!open.get()) return false
        tooltip = text
        // Both signals: Title is what most hosts read for the hover label
        // (since ToolTip is left empty deliberately — see comment on the
        // "ToolTip" property branch below for the why), so NewTitle has to
        // fire when tooltip text changes. NewToolTip is kept for hosts
        // that strictly key on the ToolTip-changed signal.
        emitSignal("NewTitle")
        emitSignal("NewToolTip")
        return true
    }

    override fun setIcon(iconBytes: ByteArray): Boolean {
        if (!open.get()) return false
        require(iconBytes.isNotEmpty()) { "iconBytes must be non-empty" }
        this.iconBytes = iconBytes
        this.iconPixmap = pngToPixmaps(iconBytes)
        emitSignal("NewIcon")
        return true
    }

    override fun setMenu(menu: TrayMenu?): Boolean {
        if (!open.get()) return false
        this.menu = menu
        this.menuLayout = menu?.let(::DBusMenuLayout)
        layoutRevision.incrementAndGet()
        emitLayoutUpdated()
        return true
    }

    override fun onEvent(handler: (TrayEvent) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        // pumpLoop + senderLoop both check open.get() and exit within
        // their next poll (~1s). Don't hard-interrupt — that could
        // leave the connection in a weird half-closed state with the
        // watcher still expecting our service, AND in the sender's
        // case could lose half-built outgoing messages mid-FFM call.
        senderThread.join(2_000)
        pumpThread.join(2_000)
        try {
            bindings.handle("dbus_connection_unref").invokeExact(connection) as Unit
        } catch (t: Throwable) {
            log.warn("dbus_connection_unref threw on shutdown: {}", t.message)
        }
    }

    // ── Background dispatch loop ─────────────────────────────────────────

    private fun pumpLoop() {
        val popMessage = bindings.handle("dbus_connection_pop_message")
        val readWrite  = bindings.handle("dbus_connection_read_write")
        val unref      = bindings.handle("dbus_message_unref")
        while (open.get()) {
            try {
                readWrite.invokeExact(connection, 1_000) as Int  // 1s blocking poll
                while (open.get()) {
                    val msg = popMessage.invokeExact(connection) as MemorySegment
                    if (msg.address() == 0L) break
                    try {
                        dispatchOne(msg)
                    } catch (t: Throwable) {
                        log.warn("Message dispatch threw, dropping message: {}", t.message)
                    } finally {
                        runCatching { unref.invokeExact(msg) as Unit }
                    }
                }
            } catch (t: Throwable) {
                log.warn("D-Bus pump iteration threw: {}", t.message)
                // Sleep briefly so a permanently-broken bus doesn't
                // burn CPU at 100% logging.
                Thread.sleep(500)
            }
        }
    }

    private fun dispatchOne(msg: MemorySegment) {
        val iface  = readMessageString(bindings.handle("dbus_message_get_interface"), msg) ?: return
        val member = readMessageString(bindings.handle("dbus_message_get_member"),    msg) ?: return
        val path   = readMessageString(bindings.handle("dbus_message_get_path"),      msg) ?: ""

        when (iface) {
            "org.freedesktop.DBus.Properties" -> when (member) {
                "Get"     -> handlePropertiesGet(msg)
                "GetAll"  -> handlePropertiesGetAll(msg)
                else      -> log.debug("Unhandled Properties.{}", member)
            }
            "org.kde.StatusNotifierItem" -> when (member) {
                "Activate"           -> { fire(TrayEvent.Activated);          replyEmpty(msg) }
                "SecondaryActivate"  -> { fire(TrayEvent.MiddleActivated);    replyEmpty(msg) }
                "ContextMenu"        -> { fire(TrayEvent.MenuRequested);      replyEmpty(msg) }
                "Scroll"             -> replyEmpty(msg)  // no scroll events in TrayEvent for now
                else                 -> log.debug("Unhandled SNI.{}", member)
            }
            "org.freedesktop.DBus.Introspectable" -> when (member) {
                "Introspect" -> handleIntrospect(msg, path)
                else         -> log.debug("Unhandled Introspectable.{}", member)
            }
            "com.canonical.dbusmenu" -> when (member) {
                "GetLayout"           -> handleMenuGetLayout(msg)
                "GetGroupProperties"  -> handleMenuGetGroupProperties(msg)
                "GetProperty"         -> handleMenuGetProperty(msg)
                "Event"               -> handleMenuEvent(msg)
                "EventGroup"          -> handleMenuEventGroup(msg)
                "AboutToShow"         -> handleMenuAboutToShow(msg)
                "AboutToShowGroup"    -> handleMenuAboutToShowGroup(msg)
                else                  -> log.debug("Unhandled dbusmenu.{}", member)
            }
            "org.freedesktop.DBus" -> {
                // Bus signals (NameOwnerChanged etc) — informational, ignore.
            }
            else -> log.debug("Unhandled iface={} member={} path={}", iface, member, path)
        }
    }

    private fun fire(event: TrayEvent) {
        handlers.forEach { handler ->
            runCatching { handler(event) }
                .onFailure { log.warn("onEvent handler threw: {}", it.message) }
        }
    }

    // ── Properties dispatch ──────────────────────────────────────────────

    private fun handlePropertiesGet(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            (bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int)
            val ifaceName = readBasicString(call, iter) ?: return replyEmpty(msg)
            (bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int)
            val propName  = readBasicString(call, iter) ?: return replyEmpty(msg)

            if (ifaceName != "org.kde.StatusNotifierItem") {
                replyEmpty(msg)
                return
            }
            val reply = (bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment)
            val replyIter = call.allocate(bindings.messageIterLayout)
            (bindings.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit)
            appendVariantForProperty(call, replyIter, propName)
            sendAndUnref(reply)
        }
    }

    private fun handlePropertiesGetAll(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            (bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int)
            val ifaceName = readBasicString(call, iter) ?: return replyEmpty(msg)
            if (ifaceName != "org.kde.StatusNotifierItem") {
                replyEmpty(msg)
                return
            }

            val reply = (bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment)
            val replyIter = call.allocate(bindings.messageIterLayout)
            (bindings.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit)

            // a{sv} dict
            val sigPtr = call.allocateUtf8("{sv}")
            val arrIter = call.allocate(bindings.messageIterLayout)
            openContainer(replyIter, DBusBindings.DBUS_TYPE_ARRAY, sigPtr, arrIter)
            for (prop in PROPERTY_NAMES) {
                appendDictEntry(call, arrIter, prop)
            }
            closeContainer(replyIter, arrIter)
            sendAndUnref(reply)
        }
    }

    private fun appendDictEntry(call: Arena, parent: MemorySegment, propName: String) {
        val entry = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
        appendBasicString(call, entry, DBusBindings.DBUS_TYPE_STRING, propName)
        appendVariantForProperty(call, entry, propName)
        closeContainer(parent, entry)
    }

    private fun appendVariantForProperty(call: Arena, parent: MemorySegment, propName: String) {
        when (propName) {
            "Category"      -> appendVariantString(call, parent, "ApplicationStatus")
            "Id"            -> appendVariantString(call, parent, appId)
            // Title is the single host-visible hover string. Caller-
            // controlled via [setTooltip] — if you want "AppName —
            // status" the caller passes that whole string in. libtray
            // does NOT add a prefix because some hosts resolve the
            // application name from the SNI Id and prepend it on their
            // own; with libtray composing the same prefix the result
            // would be "App — App — status".
            "Title"         -> appendVariantString(call, parent, currentTitle())
            "Status"        -> appendVariantString(call, parent, status)
            "WindowId"      -> appendVariantInt(call, parent, 0)
            "IconName"      -> appendVariantString(call, parent, "")
            "IconPixmap"    -> appendVariantIconPixmap(call, parent, iconPixmap)
            "OverlayIconName"   -> appendVariantString(call, parent, "")
            "AttentionIconName" -> appendVariantString(call, parent, "")
            // ToolTip deliberately empty — see [composedTitle] for the
            // single-source-of-truth rationale. Sending an empty struct
            // (rather than dropping the property) keeps the variant well-
            // typed for hosts that GET it before any LayoutUpdated.
            "ToolTip"       -> appendVariantToolTip(call, parent, "")
            // Always advertise /MenuBar so the host knows where to fetch
            // the dbusmenu, even when there's currently no menu set —
            // GetLayout responds with an empty root in that case. Toggling
            // Menu between "/" and "/MenuBar" mid-life confuses some hosts
            // (KDE plasmashell drops the icon on the toggle).
            "Menu"          -> appendVariantObjectPath(call, parent, "/MenuBar")
            "ItemIsMenu"    -> appendVariantBoolean(call, parent, false)
            else -> appendVariantString(call, parent, "")
        }
    }

    // ── Property variant builders ────────────────────────────────────────

    private fun appendVariantString(call: Arena, parent: MemorySegment, value: String) {
        val sig = call.allocateUtf8("s")
        val variant = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)
        appendBasicString(call, variant, DBusBindings.DBUS_TYPE_STRING, value)
        closeContainer(parent, variant)
    }

    private fun appendVariantObjectPath(call: Arena, parent: MemorySegment, value: String) {
        val sig = call.allocateUtf8("o")
        val variant = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)
        appendBasicString(call, variant, DBusBindings.DBUS_TYPE_OBJECT_PATH, value)
        closeContainer(parent, variant)
    }

    private fun appendVariantInt(call: Arena, parent: MemorySegment, value: Int) {
        val sig = call.allocateUtf8("i")
        val variant = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)
        val intBuf = call.allocate(ValueLayout.JAVA_INT)
        intBuf.set(ValueLayout.JAVA_INT, 0, value)
        bindings.handle("dbus_message_iter_append_basic")
            .invokeExact(variant, DBusBindings.DBUS_TYPE_INT32.toInt(), intBuf) as Int
        closeContainer(parent, variant)
    }

    private fun appendVariantBoolean(call: Arena, parent: MemorySegment, value: Boolean) {
        val sig = call.allocateUtf8("b")
        val variant = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)
        val boolBuf = call.allocate(ValueLayout.JAVA_INT)  // dbus_bool_t is 4 bytes
        boolBuf.set(ValueLayout.JAVA_INT, 0, if (value) 1 else 0)
        bindings.handle("dbus_message_iter_append_basic")
            .invokeExact(variant, DBusBindings.DBUS_TYPE_BOOLEAN.toInt(), boolBuf) as Int
        closeContainer(parent, variant)
    }

    private fun appendVariantToolTip(call: Arena, parent: MemorySegment, body: String) {
        // ToolTip signature: (sa(iiay)ss) — (icon_name, icon_data, title, body).
        //
        // KDE Plasma renders ToolTip as `<title> (bold) <body>` *and* shows
        // SNI `Title` above. If we set tooltip-title=appTitle we get the
        // app name twice; if we set it to itemId we leak the bus name.
        // Empty title gives the cleanest result: `<Title>  <body>`.
        val sig = call.allocateUtf8("(sa(iiay)ss)")
        val variant = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, sig, variant)

        val struct = call.allocate(bindings.messageIterLayout)
        openContainer(variant, DBusBindings.DBUS_TYPE_STRUCT, MemorySegment.NULL, struct)
        appendBasicString(call, struct, DBusBindings.DBUS_TYPE_STRING, "")           // icon_name
        // empty a(iiay)
        val emptyArrSig = call.allocateUtf8("(iiay)")
        val emptyArr = call.allocate(bindings.messageIterLayout)
        openContainer(struct, DBusBindings.DBUS_TYPE_ARRAY, emptyArrSig, emptyArr)
        closeContainer(struct, emptyArr)
        appendBasicString(call, struct, DBusBindings.DBUS_TYPE_STRING, "")           // title (deliberately empty — see comment above)
        appendBasicString(call, struct, DBusBindings.DBUS_TYPE_STRING, body)         // body
        closeContainer(variant, struct)

        closeContainer(parent, variant)
    }

    private fun appendVariantIconPixmap(call: Arena, parent: MemorySegment, pixmaps: List<Pixmap>) {
        // IconPixmap signature: a(iiay) — array of (width, height, ARGB32-network-order bytes).
        // Variant outer signature is the FULL type "a(iiay)"; the array's
        // element signature is the struct alone "(iiay)". Initial commit
        // had both as "(iiay)" which made libdbus reject the array open
        // ("Writing an element of type array, but the expected type here
        // is struct") — caught by manual smoke on Hyprland.
        val variantSig = call.allocateUtf8("a(iiay)")
        val elementSig = call.allocateUtf8("(iiay)")
        val variant = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_VARIANT, variantSig, variant)
        val arr = call.allocate(bindings.messageIterLayout)
        openContainer(variant, DBusBindings.DBUS_TYPE_ARRAY, elementSig, arr)

        for (px in pixmaps) {
            val struct = call.allocate(bindings.messageIterLayout)
            openContainer(arr, DBusBindings.DBUS_TYPE_STRUCT, MemorySegment.NULL, struct)

            val intBuf = call.allocate(ValueLayout.JAVA_INT)
            intBuf.set(ValueLayout.JAVA_INT, 0, px.width)
            bindings.handle("dbus_message_iter_append_basic")
                .invokeExact(struct, DBusBindings.DBUS_TYPE_INT32.toInt(), intBuf) as Int
            intBuf.set(ValueLayout.JAVA_INT, 0, px.height)
            bindings.handle("dbus_message_iter_append_basic")
                .invokeExact(struct, DBusBindings.DBUS_TYPE_INT32.toInt(), intBuf) as Int

            // ay (byte array) — open, append each byte, close.
            val byteSig = call.allocateUtf8("y")
            val byteArr = call.allocate(bindings.messageIterLayout)
            openContainer(struct, DBusBindings.DBUS_TYPE_ARRAY, byteSig, byteArr)
            // Append the ARGB bytes one by one — libdbus has a fixed-array
            // append helper but it's not in the LOAD_SET; per-byte is
            // slower but safe. Tray icons are 16-256px so worst case ~256KB.
            val byteBuf = call.allocate(ValueLayout.JAVA_BYTE)
            for (b in px.argbNetworkOrder) {
                byteBuf.set(ValueLayout.JAVA_BYTE, 0, b)
                bindings.handle("dbus_message_iter_append_basic")
                    .invokeExact(byteArr, DBusBindings.DBUS_TYPE_BYTE.toInt(), byteBuf) as Int
            }
            closeContainer(struct, byteArr)

            closeContainer(arr, struct)
        }

        closeContainer(variant, arr)
        closeContainer(parent, variant)
    }

    // ── Introspection (for hosts that probe before subscribing) ──────────

    private fun handleIntrospect(msg: MemorySegment, path: String) {
        Arena.ofConfined().use { call ->
            val reply = (bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment)
            val iter = call.allocate(bindings.messageIterLayout)
            (bindings.handle("dbus_message_iter_init_append").invokeExact(reply, iter) as Unit)
            val xml = when (path) {
                "/MenuBar" -> INTROSPECTION_XML_MENU
                else       -> INTROSPECTION_XML_ITEM
            }
            appendBasicString(call, iter, DBusBindings.DBUS_TYPE_STRING, xml)
            sendAndUnref(reply)
        }
    }

    // ── DBusMenu protocol (com.canonical.dbusmenu) ───────────────────────

    /**
     * GetLayout(parentId, recursionDepth, propertyNames)
     *   → (revision, root) where root is (id, properties, children).
     *
     * Hosts call this to fetch the menu structure on first show + on
     * every LayoutUpdated signal we emit. recursionDepth = -1 means
     * "give me everything"; finite values let the host page the tree.
     */
    private fun handleMenuGetLayout(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int
            val parentId = readInt(call, iter) ?: 0
            bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int
            val depth = readInt(call, iter) ?: -1
            // We ignore propertyNames and just send everything we know —
            // the protocol allows unrequested properties in the response,
            // and our property set is small enough that filtering would
            // cost more code than it saves.

            val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
            val replyIter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit

            // Revision (uint32)
            val intBuf = call.allocate(ValueLayout.JAVA_INT)
            intBuf.set(ValueLayout.JAVA_INT, 0, layoutRevision.get())
            bindings.handle("dbus_message_iter_append_basic")
                .invokeExact(replyIter, DBusBindings.DBUS_TYPE_UINT32.toInt(), intBuf) as Int

            // Root struct (ia{sv}av) — recursive subtree from parentId.
            marshallNode(call, replyIter, parentId, depth)
            sendAndUnref(reply)
        }
    }

    private fun marshallNode(call: Arena, parent: MemorySegment, nodeId: Int, depthRemaining: Int) {
        val struct = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_STRUCT, MemorySegment.NULL, struct)

        // int32 id
        val intBuf = call.allocate(ValueLayout.JAVA_INT)
        intBuf.set(ValueLayout.JAVA_INT, 0, nodeId)
        bindings.handle("dbus_message_iter_append_basic")
            .invokeExact(struct, DBusBindings.DBUS_TYPE_INT32.toInt(), intBuf) as Int

        // a{sv} properties dict
        val dictSig = call.allocateUtf8("{sv}")
        val dictArr = call.allocate(bindings.messageIterLayout)
        openContainer(struct, DBusBindings.DBUS_TYPE_ARRAY, dictSig, dictArr)
        val props = menuLayout?.propertiesOf(nodeId).orEmpty()
        for ((name, value) in props) {
            appendMenuPropertyEntry(call, dictArr, name, value)
        }
        closeContainer(struct, dictArr)

        // av children — array of variants of (ia{sv}av)
        val childSig = call.allocateUtf8("v")
        val childArr = call.allocate(bindings.messageIterLayout)
        openContainer(struct, DBusBindings.DBUS_TYPE_ARRAY, childSig, childArr)
        if (depthRemaining != 0) {
            val nextDepth = if (depthRemaining < 0) -1 else depthRemaining - 1
            for (childId in menuLayout?.childrenOf(nodeId).orEmpty()) {
                val variant = call.allocate(bindings.messageIterLayout)
                val variantSig = call.allocateUtf8("(ia{sv}av)")
                openContainer(childArr, DBusBindings.DBUS_TYPE_VARIANT, variantSig, variant)
                marshallNode(call, variant, childId, nextDepth)
                closeContainer(childArr, variant)
            }
        }
        closeContainer(struct, childArr)

        closeContainer(parent, struct)
    }

    private fun appendMenuPropertyEntry(call: Arena, parent: MemorySegment, name: String, value: DBusMenuLayout.PropertyValue) {
        val entry = call.allocate(bindings.messageIterLayout)
        openContainer(parent, DBusBindings.DBUS_TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
        appendBasicString(call, entry, DBusBindings.DBUS_TYPE_STRING, name)
        when (value) {
            is DBusMenuLayout.PropertyValue.Str  -> appendVariantString(call, entry, value.value)
            is DBusMenuLayout.PropertyValue.Bool -> appendVariantBoolean(call, entry, value.value)
        }
        closeContainer(parent, entry)
    }

    /**
     * GetGroupProperties(ids, propertyNames) → a(ia{sv})
     *   array of (id, properties_dict).
     */
    private fun handleMenuGetGroupProperties(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int
            val ids = readIntArray(call, iter)
            // We ignore propertyNames; same reasoning as GetLayout.

            val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
            val replyIter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit

            val arrSig = call.allocateUtf8("(ia{sv})")
            val arr = call.allocate(bindings.messageIterLayout)
            openContainer(replyIter, DBusBindings.DBUS_TYPE_ARRAY, arrSig, arr)
            for (id in ids) {
                val struct = call.allocate(bindings.messageIterLayout)
                openContainer(arr, DBusBindings.DBUS_TYPE_STRUCT, MemorySegment.NULL, struct)
                val intBuf = call.allocate(ValueLayout.JAVA_INT)
                intBuf.set(ValueLayout.JAVA_INT, 0, id)
                bindings.handle("dbus_message_iter_append_basic")
                    .invokeExact(struct, DBusBindings.DBUS_TYPE_INT32.toInt(), intBuf) as Int
                val dictSig = call.allocateUtf8("{sv}")
                val dict = call.allocate(bindings.messageIterLayout)
                openContainer(struct, DBusBindings.DBUS_TYPE_ARRAY, dictSig, dict)
                for ((name, value) in menuLayout?.propertiesOf(id).orEmpty()) {
                    appendMenuPropertyEntry(call, dict, name, value)
                }
                closeContainer(struct, dict)
                closeContainer(arr, struct)
            }
            closeContainer(replyIter, arr)
            sendAndUnref(reply)
        }
    }

    /** GetProperty(id, name) → variant. Niche; hosts mostly use GetGroupProperties. */
    private fun handleMenuGetProperty(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int
            val id = readInt(call, iter) ?: return replyEmpty(msg)
            bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int
            val propName = readBasicString(call, iter) ?: return replyEmpty(msg)

            val value = menuLayout?.propertiesOf(id)?.get(propName)
            val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
            val replyIter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit
            when (value) {
                is DBusMenuLayout.PropertyValue.Str  -> appendVariantString(call, replyIter, value.value)
                is DBusMenuLayout.PropertyValue.Bool -> appendVariantBoolean(call, replyIter, value.value)
                null                                  -> appendVariantString(call, replyIter, "")
            }
            sendAndUnref(reply)
        }
    }

    /**
     * Event(id, eventId, data, timestamp) — host tells us about user
     * interaction. We care about "clicked"; everything else is acked
     * with an empty reply.
     */
    private fun handleMenuEvent(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int
            val id = readInt(call, iter) ?: return replyEmpty(msg)
            bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int
            val eventId = readBasicString(call, iter) ?: return replyEmpty(msg)
            // data + timestamp ignored — we don't expose them as event fields.
            if (eventId == "clicked") {
                val originalId = menuLayout?.nodeOf(id)?.originalId
                if (originalId != null && originalId != "<root>") {
                    fire(TrayEvent.MenuItemSelected(originalId))
                }
            }
            replyEmpty(msg)
        }
    }

    /** EventGroup(events: a(isvu)) — batched Event. Reply: a(i) of unhandled ids. */
    private fun handleMenuEventGroup(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int
            val arrIter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_recurse").invokeExact(iter, arrIter) as Unit
            // Walk each (i s v u) tuple — pull id + event_id, ignore data + ts.
            while (true) {
                val argType = bindings.handle("dbus_message_iter_get_arg_type").invokeExact(arrIter) as Int
                if (argType.toByte() == DBusBindings.DBUS_TYPE_INVALID) break
                val structIter = call.allocate(bindings.messageIterLayout)
                bindings.handle("dbus_message_iter_recurse").invokeExact(arrIter, structIter) as Unit
                val id = readInt(call, structIter) ?: 0
                bindings.handle("dbus_message_iter_next").invokeExact(structIter) as Int
                val eventId = readBasicString(call, structIter) ?: ""
                if (eventId == "clicked") {
                    val originalId = menuLayout?.nodeOf(id)?.originalId
                    if (originalId != null && originalId != "<root>") {
                        fire(TrayEvent.MenuItemSelected(originalId))
                    }
                }
                bindings.handle("dbus_message_iter_next").invokeExact(arrIter) as Int
            }

            // Reply: empty array of int (no unhandled events).
            val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
            val replyIter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit
            val arrSig = call.allocateUtf8("i")
            val replyArr = call.allocate(bindings.messageIterLayout)
            openContainer(replyIter, DBusBindings.DBUS_TYPE_ARRAY, arrSig, replyArr)
            closeContainer(replyIter, replyArr)
            sendAndUnref(reply)
        }
    }

    /** AboutToShow(id) → bool needsUpdate. We never need a layout refresh on demand. */
    private fun handleMenuAboutToShow(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(reply, iter) as Unit
            val boolBuf = call.allocate(ValueLayout.JAVA_INT)
            boolBuf.set(ValueLayout.JAVA_INT, 0, 0)  // no update needed
            bindings.handle("dbus_message_iter_append_basic")
                .invokeExact(iter, DBusBindings.DBUS_TYPE_BOOLEAN.toInt(), boolBuf) as Int
            sendAndUnref(reply)
        }
    }

    /** AboutToShowGroup(ids) → (a(i) updatesNeeded, a(i) idErrors). Both empty. */
    private fun handleMenuAboutToShowGroup(msg: MemorySegment) {
        Arena.ofConfined().use { call ->
            val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(reply, iter) as Unit
            val arrSig = call.allocateUtf8("i")
            for (i in 0 until 2) {
                val arr = call.allocate(bindings.messageIterLayout)
                openContainer(iter, DBusBindings.DBUS_TYPE_ARRAY, arrSig, arr)
                closeContainer(iter, arr)
            }
            sendAndUnref(reply)
        }
    }

    /** Tell hosts the menu layout changed. They re-call GetLayout. */
    private fun emitLayoutUpdated() {
        Arena.ofConfined().use { call ->
            val path = call.allocateUtf8("/MenuBar")
            val iface = call.allocateUtf8("com.canonical.dbusmenu")
            val member = call.allocateUtf8("LayoutUpdated")
            val sig = bindings.handle("dbus_message_new_signal")
                .invokeExact(path, iface, member) as MemorySegment
            if (sig.address() == 0L) return
            val iter = call.allocate(bindings.messageIterLayout)
            bindings.handle("dbus_message_iter_init_append").invokeExact(sig, iter) as Unit
            val intBuf = call.allocate(ValueLayout.JAVA_INT)
            intBuf.set(ValueLayout.JAVA_INT, 0, layoutRevision.get())
            bindings.handle("dbus_message_iter_append_basic")
                .invokeExact(iter, DBusBindings.DBUS_TYPE_UINT32.toInt(), intBuf) as Int
            intBuf.set(ValueLayout.JAVA_INT, 0, 0)  // root id 0 — entire layout updated
            bindings.handle("dbus_message_iter_append_basic")
                .invokeExact(iter, DBusBindings.DBUS_TYPE_INT32.toInt(), intBuf) as Int
            sendAndUnref(sig)
        }
    }

    // ── Signal emission ──────────────────────────────────────────────────

    private fun emitSignal(name: String) {
        Arena.ofConfined().use { call ->
            val path = call.allocateUtf8("/StatusNotifierItem")
            val iface = call.allocateUtf8("org.kde.StatusNotifierItem")
            val member = call.allocateUtf8(name)
            val sig = (bindings.handle("dbus_message_new_signal")
                .invokeExact(path, iface, member) as MemorySegment)
            if (sig.address() == 0L) {
                log.warn("dbus_message_new_signal returned NULL for {}", name)
                return
            }
            sendAndUnref(sig)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun readMessageString(handle: java.lang.invoke.MethodHandle, msg: MemorySegment): String? {
        val ptr = handle.invokeExact(msg) as MemorySegment
        return if (ptr.address() == 0L) null else ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    private fun readBasicString(call: Arena, iter: MemorySegment): String? {
        val argType = bindings.handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int
        if (argType.toByte() != DBusBindings.DBUS_TYPE_STRING) return null
        val out = call.allocate(ValueLayout.ADDRESS)
        bindings.handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
        val ptr = out.get(ValueLayout.ADDRESS, 0)
        return if (ptr.address() == 0L) null else ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    private fun readInt(call: Arena, iter: MemorySegment): Int? {
        val argType = bindings.handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int
        // Accept INT32 / UINT32 / BOOLEAN — they're all 32-bit on the wire.
        if (argType.toByte() != DBusBindings.DBUS_TYPE_INT32 &&
            argType.toByte() != DBusBindings.DBUS_TYPE_UINT32 &&
            argType.toByte() != DBusBindings.DBUS_TYPE_BOOLEAN) return null
        val out = call.allocate(ValueLayout.JAVA_INT)
        bindings.handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
        return out.get(ValueLayout.JAVA_INT, 0)
    }

    /** Walk an `ai` array iterator and collect ints. Iterator must be at the array start. */
    private fun readIntArray(call: Arena, iter: MemorySegment): List<Int> {
        val argType = bindings.handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int
        if (argType.toByte() != DBusBindings.DBUS_TYPE_ARRAY) return emptyList()
        val sub = call.allocate(bindings.messageIterLayout)
        bindings.handle("dbus_message_iter_recurse").invokeExact(iter, sub) as Unit
        val result = mutableListOf<Int>()
        while (true) {
            val elementType = bindings.handle("dbus_message_iter_get_arg_type").invokeExact(sub) as Int
            if (elementType.toByte() == DBusBindings.DBUS_TYPE_INVALID) break
            val v = readInt(call, sub) ?: break
            result += v
            bindings.handle("dbus_message_iter_next").invokeExact(sub) as Int
        }
        return result
    }

    private fun appendBasicString(call: Arena, iter: MemorySegment, type: Byte, value: String) {
        val strSeg = call.allocateUtf8(value)
        val ptrBuf = call.allocate(ValueLayout.ADDRESS)
        ptrBuf.set(ValueLayout.ADDRESS, 0, strSeg)
        bindings.handle("dbus_message_iter_append_basic")
            .invokeExact(iter, type.toInt(), ptrBuf) as Int
    }

    private fun openContainer(parent: MemorySegment, type: Byte, signature: MemorySegment, sub: MemorySegment) {
        bindings.handle("dbus_message_iter_open_container")
            .invokeExact(parent, type.toInt(), signature, sub) as Int
    }

    private fun closeContainer(parent: MemorySegment, sub: MemorySegment) {
        bindings.handle("dbus_message_iter_close_container").invokeExact(parent, sub) as Int
    }

    private fun replyEmpty(msg: MemorySegment) {
        val reply = bindings.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
        if (reply.address() == 0L) return
        sendAndUnref(reply)
    }

    /**
     * Queue an outgoing message for the [senderThread] to send + flush +
     * unref. Returns immediately; the actual blocking native work happens
     * off the caller thread. See [outgoing]'s KDoc for the EDT-freeze
     * incident that drove this off-thread refactor.
     *
     * Null/zero-address segments are dropped silently — the prior
     * synchronous version would have crashed in `dbus_connection_send`;
     * we keep that contract by short-circuiting earlier.
     */
    private fun sendAndUnref(reply: MemorySegment) {
        if (reply.address() == 0L) return
        if (!open.get()) {
            // Already closing: don't enqueue (sender may have exited).
            // Drop the ref directly so libdbus's internal refcount goes
            // to zero and the message memory is reclaimed.
            runCatching { bindings.handle("dbus_message_unref").invokeExact(reply) as Unit }
            return
        }
        // LinkedBlockingQueue#offer with no capacity bound always succeeds.
        // Defensive `else` would be dead code; rely on the contract.
        outgoing.put(reply)
    }

    /**
     * Drains [outgoing] in a loop, calling `dbus_connection_send` + flush
     * + unref for each queued message. This is the thread that takes the
     * D-Bus flush latency hit so the EDT doesn't have to.
     *
     * Lives until [open] flips to false. On shutdown, drains any
     * still-queued messages without sending them (their refcount is
     * dropped so native memory isn't leaked) — the host has already been
     * told via the previous Status/Closing dance that we're going away,
     * so racing one last NewTitle out the door has no value.
     */
    private fun senderLoop() {
        val send  = bindings.handle("dbus_connection_send")
        val flush = bindings.handle("dbus_connection_flush")
        val unref = bindings.handle("dbus_message_unref")
        while (open.get()) {
            val msg = try {
                outgoing.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                continue
            }
            try {
                Arena.ofConfined().use { call ->
                    val serial = call.allocate(ValueLayout.JAVA_INT)
                    send.invokeExact(connection, msg, serial) as Int
                    flush.invokeExact(connection) as Unit
                }
            } catch (t: Throwable) {
                log.warn("sender: send/flush failed, dropping message: {}", t.message)
            } finally {
                runCatching { unref.invokeExact(msg) as Unit }
            }
        }
        // Final drain: anything queued between the last poll() and the
        // open=false transition. We unref without sending so libdbus's
        // outgoing queue doesn't hold our refs past connection_unref.
        while (true) {
            val msg = outgoing.poll() ?: break
            runCatching { unref.invokeExact(msg) as Unit }
        }
    }

    private data class Pixmap(val width: Int, val height: Int, val argbNetworkOrder: ByteArray)

    /**
     * Decode a PNG (or any ImageIO-supported format) into the ARGB32
     * network-byte-order layout that StatusNotifierItem's `IconPixmap`
     * property expects. SNI accepts an array of multi-resolution
     * pixmaps; we ship just one matching the input image's size.
     */
    private fun pngToPixmaps(bytes: ByteArray): List<Pixmap> {
        return runCatching {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return emptyList()
            val w = img.width
            val h = img.height
            val argb = IntArray(w * h)
            img.getRGB(0, 0, w, h, argb, 0, w)
            val out = ByteArray(w * h * 4)
            for (i in argb.indices) {
                val px = argb[i]
                // Network byte order: A R G B.
                out[i * 4]     = ((px ushr 24) and 0xFF).toByte()
                out[i * 4 + 1] = ((px ushr 16) and 0xFF).toByte()
                out[i * 4 + 2] = ((px ushr 8)  and 0xFF).toByte()
                out[i * 4 + 3] = (px and 0xFF).toByte()
            }
            listOf(Pixmap(w, h, out))
        }.getOrElse {
            log.warn("Icon decode failed: {}", it.message)
            emptyList()
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libtray.SniTray")
        private val itemCounter = AtomicInteger(0)

        /**
         * Derive an SNI `Id` from the human-readable title. Spec calls for
         * "a name that should be unique for this application and consistent
         * between sessions" — so lowercase, drop everything that's not
         * `[a-z0-9]`, fall back to "tray" if the result is empty (titles
         * made of pure CJK / cyrillic / emoji would otherwise produce an
         * empty Id, which some hosts reject).
         */
        internal fun slugifyForSni(title: String): String {
            val slug = buildString(title.length) {
                for (ch in title.lowercase()) {
                    if (ch in 'a'..'z' || ch in '0'..'9') append(ch)
                }
            }
            return slug.ifEmpty { "tray" }
        }

        // Properties exposed on the StatusNotifierItem object — used by
        // GetAll to enumerate, and by Get to dispatch.
        private val PROPERTY_NAMES = listOf(
            "Category", "Id", "Title", "Status", "WindowId",
            "IconName", "IconPixmap",
            "OverlayIconName", "AttentionIconName",
            "ToolTip", "Menu", "ItemIsMenu",
        )

        // Minimal introspection — hosts that probe before subscribing
        // need it; without it some implementations refuse to render the
        // icon. Lists the methods + signals we actually implement on
        // each path — /StatusNotifierItem and /MenuBar are dispatched
        // separately by [handleIntrospect].
        private val INTROSPECTION_XML_ITEM = """
            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
              "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            <node>
              <interface name="org.kde.StatusNotifierItem">
                <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
                <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
                <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
                <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
                <signal name="NewIcon"/>
                <signal name="NewToolTip"/>
                <signal name="NewStatus"><arg type="s"/></signal>
              </interface>
              <interface name="org.freedesktop.DBus.Properties">
                <method name="Get"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
                <method name="GetAll"><arg type="s" direction="in"/><arg type="a{sv}" direction="out"/></method>
              </interface>
              <interface name="org.freedesktop.DBus.Introspectable">
                <method name="Introspect"><arg type="s" direction="out"/></method>
              </interface>
            </node>
        """.trimIndent()

        private val INTROSPECTION_XML_MENU = """
            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
              "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            <node>
              <interface name="com.canonical.dbusmenu">
                <method name="GetLayout">
                  <arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="as" direction="in"/>
                  <arg type="u" direction="out"/><arg type="(ia{sv}av)" direction="out"/>
                </method>
                <method name="GetGroupProperties">
                  <arg type="ai" direction="in"/><arg type="as" direction="in"/>
                  <arg type="a(ia{sv})" direction="out"/>
                </method>
                <method name="GetProperty">
                  <arg type="i" direction="in"/><arg type="s" direction="in"/>
                  <arg type="v" direction="out"/>
                </method>
                <method name="Event">
                  <arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="in"/><arg type="u" direction="in"/>
                </method>
                <method name="EventGroup">
                  <arg type="a(isvu)" direction="in"/>
                  <arg type="ai" direction="out"/>
                </method>
                <method name="AboutToShow">
                  <arg type="i" direction="in"/>
                  <arg type="b" direction="out"/>
                </method>
                <method name="AboutToShowGroup">
                  <arg type="ai" direction="in"/>
                  <arg type="ai" direction="out"/><arg type="ai" direction="out"/>
                </method>
                <signal name="LayoutUpdated">
                  <arg type="u"/><arg type="i"/>
                </signal>
                <signal name="ItemsPropertiesUpdated">
                  <arg type="a(ia{sv})"/><arg type="a(ias)"/>
                </signal>
              </interface>
              <interface name="org.freedesktop.DBus.Introspectable">
                <method name="Introspect"><arg type="s" direction="out"/></method>
              </interface>
            </node>
        """.trimIndent()

        fun create(builder: TrayBuilder): Tray? {
            val bindings = DBusBindings.load() ?: run {
                log.info("libdbus not loadable — SNI tray unavailable")
                return null
            }
            return Arena.ofConfined().use { setup ->
                val error = setup.allocate(bindings.errorLayout)
                bindings.handle("dbus_error_init").invokeExact(error) as Unit

                val conn = bindings.handle("dbus_bus_get").invokeExact(
                    DBusBindings.DBUS_BUS_SESSION, error,
                ) as MemorySegment
                if (conn.address() == 0L) {
                    log.info("dbus_bus_get returned NULL — no session bus, SNI unavailable")
                    return@use null
                }

                val itemId = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-${itemCounter.incrementAndGet()}"
                val nameSeg = setup.allocateUtf8(itemId)
                val flags = DBusBindings.DBUS_NAME_FLAG_REPLACE_EXISTING or DBusBindings.DBUS_NAME_FLAG_DO_NOT_QUEUE
                val nameResult = bindings.handle("dbus_bus_request_name").invokeExact(
                    conn, nameSeg, flags, error,
                ) as Int
                if (nameResult != DBusBindings.DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER) {
                    log.warn("dbus_bus_request_name returned {} for {} — SNI registration failed",
                        nameResult, itemId)
                    bindings.handle("dbus_connection_unref").invokeExact(conn) as Unit
                    return@use null
                }

                // Tell the desktop's tray host we're here. Failure isn't
                // fatal — the host might come up later and discover us
                // via well-known name listing — but log so a "no icon
                // shows up" investigation has a starting point.
                if (!registerWithWatcher(bindings, conn, itemId, setup)) {
                    log.info("StatusNotifierWatcher registration failed for {}; icon may not appear " +
                        "until a tray host comes online", itemId)
                }

                SniTrayImpl(bindings, conn, itemId, builder)
            }
        }

        private fun registerWithWatcher(
            bindings: DBusBindings,
            conn: MemorySegment,
            itemId: String,
            setup: Arena,
        ): Boolean {
            return runCatching {
                val destination = setup.allocateUtf8("org.kde.StatusNotifierWatcher")
                val path        = setup.allocateUtf8("/StatusNotifierWatcher")
                val iface       = setup.allocateUtf8("org.kde.StatusNotifierWatcher")
                val member      = setup.allocateUtf8("RegisterStatusNotifierItem")
                val msg = bindings.handle("dbus_message_new_method_call").invokeExact(
                    destination, path, iface, member,
                ) as MemorySegment
                if (msg.address() == 0L) return@runCatching false

                val iter = setup.allocate(bindings.messageIterLayout)
                bindings.handle("dbus_message_iter_init_append").invokeExact(msg, iter) as Unit
                val nameSeg = setup.allocateUtf8(itemId)
                val ptrBuf = setup.allocate(ValueLayout.ADDRESS)
                ptrBuf.set(ValueLayout.ADDRESS, 0, nameSeg)
                bindings.handle("dbus_message_iter_append_basic")
                    .invokeExact(iter, DBusBindings.DBUS_TYPE_STRING.toInt(), ptrBuf) as Int

                val error = setup.allocate(bindings.errorLayout)
                bindings.handle("dbus_error_init").invokeExact(error) as Unit
                val reply = bindings.handle("dbus_connection_send_with_reply_and_block").invokeExact(
                    conn, msg, 5_000, error,
                ) as MemorySegment
                bindings.handle("dbus_message_unref").invokeExact(msg) as Unit
                if (reply.address() != 0L) {
                    bindings.handle("dbus_message_unref").invokeExact(reply) as Unit
                }
                val errorSet = bindings.handle("dbus_error_is_set").invokeExact(error) as Int
                if (errorSet != 0) {
                    bindings.handle("dbus_error_free").invokeExact(error) as Unit
                    false
                } else true
            }.getOrDefault(false)
        }
    }
}

/**
 * Allocate a UTF-8 null-terminated string in this arena. libdbus expects
 * `const char *` style strings in every text field.
 */
private fun Arena.allocateUtf8(s: String): MemorySegment {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val segment = allocate((bytes.size + 1).toLong())
    if (bytes.isNotEmpty()) {
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
    }
    segment.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
    return segment
}

