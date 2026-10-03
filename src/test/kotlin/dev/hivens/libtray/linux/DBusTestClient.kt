package dev.hivens.libtray.linux

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The other end of the bus for [SniHostTest]: a private libdbus connection
 * that calls into a tray item the way a tray host does, built on the same
 * [DBusBindings] the backend uses. Only the shapes the tests need are
 * covered.
 */
internal class DBusTestClient : AutoCloseable {

    val bindings: DBusBindings = DBusBindings.load() ?: error("libdbus not loadable")
    val connection: MemorySegment

    init {
        Arena.ofConfined().use { arena ->
            val error = newError(arena)
            connection = bindings.handle("dbus_bus_get_private")
                .invokeExact(DBusBindings.DBUS_BUS_SESSION, error) as MemorySegment
            check(connection.address() != 0L) { "no session bus: ${errorText(error)}" }
            bindings.handle("dbus_connection_set_exit_on_disconnect").invokeExact(connection, 0) as Unit
        }
    }

    /** One argument of a method call. */
    sealed interface Arg {
        data class Str(val value: String) : Arg
        data class Int32(val value: Int) : Arg
        data class UInt32(val value: Int) : Arg
        data class VariantInt32(val value: Int) : Arg
        data object EmptyStringArray : Arg
    }

    /**
     * Call [member] and hand the reply to [read] before it is freed. Throws
     * with the D-Bus error name when the call fails.
     */
    fun <T> call(
        destination: String,
        path: String,
        iface: String,
        member: String,
        args: List<Arg> = emptyList(),
        read: (reply: MemorySegment, arena: Arena) -> T,
    ): T = Arena.ofConfined().use { arena ->
        val msg = bindings.handle("dbus_message_new_method_call").invokeExact(
            arena.allocateUtf8(destination), arena.allocateUtf8(path),
            arena.allocateUtf8(iface), arena.allocateUtf8(member),
        ) as MemorySegment
        check(msg.address() != 0L) { "could not build $iface.$member" }
        val iter = arena.allocate(bindings.messageIterLayout)
        bindings.handle("dbus_message_iter_init_append").invokeExact(msg, iter) as Unit
        for (arg in args) append(arena, iter, arg)

        val error = newError(arena)
        val reply = bindings.handle("dbus_connection_send_with_reply_and_block")
            .invokeExact(connection, msg, CALL_TIMEOUT_MS, error) as MemorySegment
        bindings.handle("dbus_message_unref").invokeExact(msg) as Unit
        if (reply.address() == 0L) error("$iface.$member failed: ${errorText(error)}")
        try {
            read(reply, arena)
        } finally {
            bindings.handle("dbus_message_unref").invokeExact(reply) as Unit
        }
    }

    fun callNoResult(destination: String, path: String, iface: String, member: String, args: List<Arg> = emptyList()) =
        call(destination, path, iface, member, args) { _, _ -> }

    /** `Properties.Get` of a string or object path property. */
    fun getStringProperty(destination: String, name: String): String =
        call(destination, ITEM_PATH, "org.freedesktop.DBus.Properties", "Get",
            listOf(Arg.Str(ITEM_IFACE), Arg.Str(name))) { reply, arena ->
            val iter = firstArg(reply, arena)
            val variant = recurse(iter, arena)
            readString(variant, arena)
        }

    /** Width and height of the first pixmap in `IconPixmap`, or null when the array is empty. */
    fun firstIconPixmapSize(destination: String): Pair<Int, Int>? =
        call(destination, ITEM_PATH, "org.freedesktop.DBus.Properties", "Get",
            listOf(Arg.Str(ITEM_IFACE), Arg.Str("IconPixmap"))) { reply, arena ->
            val array = recurse(recurse(firstArg(reply, arena), arena), arena)
            if (argType(array) == DBusBindings.DBUS_TYPE_INVALID) return@call null
            val struct = recurse(array, arena)
            val width = readInt(struct, arena)
            bindings.handle("dbus_message_iter_next").invokeExact(struct) as Int
            width to readInt(struct, arena)
        }

    /** Number of top-level children in the root of a dbusmenu `GetLayout` reply. */
    fun menuRootChildCount(destination: String): Int =
        call(destination, MENU_PATH, "com.canonical.dbusmenu", "GetLayout",
            listOf(Arg.Int32(0), Arg.Int32(-1), Arg.EmptyStringArray)) { reply, arena ->
            val iter = firstArg(reply, arena)
            bindings.handle("dbus_message_iter_next").invokeExact(iter) as Int  // skip revision
            val root = recurse(iter, arena)
            bindings.handle("dbus_message_iter_next").invokeExact(root) as Int  // skip id
            bindings.handle("dbus_message_iter_next").invokeExact(root) as Int  // skip properties
            val children = recurse(root, arena)
            var count = 0
            while (argType(children) != DBusBindings.DBUS_TYPE_INVALID) {
                count++
                bindings.handle("dbus_message_iter_next").invokeExact(children) as Int
            }
            count
        }

    /** Own [name] on this connection. False when someone else already holds it. */
    fun requestName(name: String): Boolean = Arena.ofConfined().use { arena ->
        val error = newError(arena)
        val result = bindings.handle("dbus_bus_request_name").invokeExact(
            connection, arena.allocateUtf8(name), DBusBindings.DBUS_NAME_FLAG_DO_NOT_QUEUE, error,
        ) as Int
        bindings.handle("dbus_error_free").invokeExact(error) as Unit
        result == DBusBindings.DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER
    }

    override fun close() {
        bindings.handle("dbus_connection_close").invokeExact(connection) as Unit
        bindings.handle("dbus_connection_unref").invokeExact(connection) as Unit
        bindings.arena.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun append(arena: Arena, iter: MemorySegment, arg: Arg) {
        val appendBasic = bindings.handle("dbus_message_iter_append_basic")
        when (arg) {
            is Arg.Str -> {
                val ptr = arena.allocate(ValueLayout.ADDRESS)
                ptr.set(ValueLayout.ADDRESS, 0, arena.allocateUtf8(arg.value))
                appendBasic.invokeExact(iter, DBusBindings.DBUS_TYPE_STRING.toInt(), ptr) as Int
            }
            is Arg.Int32 -> appendBasic.invokeExact(iter, DBusBindings.DBUS_TYPE_INT32.toInt(), int(arena, arg.value)) as Int
            is Arg.UInt32 -> appendBasic.invokeExact(iter, DBusBindings.DBUS_TYPE_UINT32.toInt(), int(arena, arg.value)) as Int
            is Arg.VariantInt32 -> {
                val variant = arena.allocate(bindings.messageIterLayout)
                bindings.handle("dbus_message_iter_open_container").invokeExact(
                    iter, DBusBindings.DBUS_TYPE_VARIANT.toInt(), arena.allocateUtf8("i"), variant,
                ) as Int
                appendBasic.invokeExact(variant, DBusBindings.DBUS_TYPE_INT32.toInt(), int(arena, arg.value)) as Int
                bindings.handle("dbus_message_iter_close_container").invokeExact(iter, variant) as Int
            }
            Arg.EmptyStringArray -> {
                val array = arena.allocate(bindings.messageIterLayout)
                bindings.handle("dbus_message_iter_open_container").invokeExact(
                    iter, DBusBindings.DBUS_TYPE_ARRAY.toInt(), arena.allocateUtf8("s"), array,
                ) as Int
                bindings.handle("dbus_message_iter_close_container").invokeExact(iter, array) as Int
            }
        }
    }

    private fun int(arena: Arena, value: Int): MemorySegment =
        arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0, value) }

    private fun firstArg(msg: MemorySegment, arena: Arena): MemorySegment {
        val iter = arena.allocate(bindings.messageIterLayout)
        check((bindings.handle("dbus_message_iter_init").invokeExact(msg, iter) as Int) != 0) { "empty reply" }
        return iter
    }

    private fun recurse(iter: MemorySegment, arena: Arena): MemorySegment {
        val sub = arena.allocate(bindings.messageIterLayout)
        bindings.handle("dbus_message_iter_recurse").invokeExact(iter, sub) as Unit
        return sub
    }

    private fun argType(iter: MemorySegment): Byte =
        (bindings.handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int).toByte()

    private fun readString(iter: MemorySegment, arena: Arena): String {
        val out = arena.allocate(ValueLayout.ADDRESS)
        bindings.handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
        return out.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
    }

    private fun readInt(iter: MemorySegment, arena: Arena): Int {
        val out = arena.allocate(ValueLayout.JAVA_INT)
        bindings.handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
        return out.get(ValueLayout.JAVA_INT, 0)
    }

    private fun newError(arena: Arena): MemorySegment =
        arena.allocate(bindings.errorLayout).also { bindings.handle("dbus_error_init").invokeExact(it) as Unit }

    private fun errorText(error: MemorySegment): String {
        if ((bindings.handle("dbus_error_is_set").invokeExact(error) as Int) == 0) return "no error set"
        fun field(offset: Long): String {
            val ptr = error.get(ValueLayout.ADDRESS, offset)
            return if (ptr.address() == 0L) "" else ptr.reinterpret(Long.MAX_VALUE).getString(0)
        }
        val text = "${field(0)}: ${field(8)}"
        bindings.handle("dbus_error_free").invokeExact(error) as Unit
        return text
    }

    /**
     * A minimal `org.kde.StatusNotifierWatcher`: owns the name and records
     * every `RegisterStatusNotifierItem` argument in [registrations]. Close
     * it to drop the name, which a tray item sees as the watcher going away.
     */
    class Watcher : AutoCloseable {
        val registrations = LinkedBlockingQueue<String>()
        private val client = DBusTestClient()
        private val running = AtomicBoolean(true)
        private val loop: Thread

        init {
            // A watcher closed just before may not have lost the name yet:
            // the daemon handles its disconnect on its own schedule.
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!client.requestName(WATCHER_NAME)) {
                check(System.nanoTime() < deadline) { "$WATCHER_NAME is already owned on this bus" }
                Thread.sleep(20)
            }
            loop = thread(name = "test-sni-watcher", isDaemon = true) { serve() }
        }

        private fun serve() {
            val b = client.bindings
            while (running.get()) {
                b.handle("dbus_connection_read_write").invokeExact(client.connection, 50) as Int
                while (true) {
                    val msg = b.handle("dbus_connection_pop_message").invokeExact(client.connection) as MemorySegment
                    if (msg.address() == 0L) break
                    try {
                        handle(msg)
                    } finally {
                        b.handle("dbus_message_unref").invokeExact(msg) as Unit
                    }
                }
            }
        }

        private fun handle(msg: MemorySegment) {
            val b = client.bindings
            val type = b.handle("dbus_message_get_type").invokeExact(msg) as Int
            if (type != DBusBindings.DBUS_MESSAGE_TYPE_METHOD_CALL) return
            val memberPtr = b.handle("dbus_message_get_member").invokeExact(msg) as MemorySegment
            val member = if (memberPtr.address() == 0L) "" else memberPtr.reinterpret(Long.MAX_VALUE).getString(0)
            Arena.ofConfined().use { arena ->
                if (member == "RegisterStatusNotifierItem") {
                    registrations.add(client.readString(client.firstArg(msg, arena), arena))
                }
                val reply = b.handle("dbus_message_new_method_return").invokeExact(msg) as MemorySegment
                b.handle("dbus_connection_send").invokeExact(
                    client.connection, reply, arena.allocate(ValueLayout.JAVA_INT),
                ) as Int
                b.handle("dbus_connection_flush").invokeExact(client.connection) as Unit
                b.handle("dbus_message_unref").invokeExact(reply) as Unit
            }
        }

        override fun close() {
            running.set(false)
            loop.join(2_000)
            client.close()
        }
    }

    companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val ITEM_IFACE = "org.kde.StatusNotifierItem"
        const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
        private const val CALL_TIMEOUT_MS = 2_000
    }
}
