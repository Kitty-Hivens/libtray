package dev.hivens.libtray.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Panama bindings to `libdbus-1` — the reference D-Bus client library
 * shipped on every desktop Linux. Loaded once per process via
 * [DBusBindings.load]; the resulting object holds method handles for the
 * subset of the libdbus API that the SNI backend uses.
 *
 * Wire-level approach: a polling dispatch loop (`dbus_connection_read_write`
 * + `dbus_connection_pop_message`) so message handling runs on a thread
 * we control, no Panama upcall stubs needed. Trades the slightly more
 * idiomatic `dbus_connection_register_object_path` (with a C vtable
 * pointing at our handler) for simpler memory management — we never
 * generate a callable C function pointer from Kotlin.
 *
 * Reference for the constants and shapes: `dbus/dbus.h` upstream
 * (https://gitlab.freedesktop.org/dbus/dbus). Where this file says
 * "DBUS_TYPE_FOO" the value matches the C macro of the same name.
 */
internal class DBusBindings internal constructor(
    val arena: Arena,
    val handles: Map<String, MethodHandle>,
) {
    /**
     * Look up a previously-resolved handle. Throws if the symbol wasn't
     * in the load-time set — programmer error, not a runtime fallback.
     */
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("DBus handle not loaded: $name. Add to LOAD_SET in DBusBindings.load.")

    companion object {
        /** D-Bus bus types from dbus/dbus-shared.h. */
        const val DBUS_BUS_SESSION: Int = 0
        const val DBUS_BUS_SYSTEM:  Int = 1
        const val DBUS_BUS_STARTER: Int = 2

        /** Message type from dbus/dbus-protocol.h -- only method calls owe a reply. */
        const val DBUS_MESSAGE_TYPE_METHOD_CALL: Int = 1

        /** Type signatures from dbus/dbus-protocol.h. Single-byte ASCII. */
        const val DBUS_TYPE_INVALID:     Byte = 0
        const val DBUS_TYPE_BYTE:        Byte = 'y'.code.toByte()
        const val DBUS_TYPE_BOOLEAN:     Byte = 'b'.code.toByte()
        const val DBUS_TYPE_INT16:       Byte = 'n'.code.toByte()
        const val DBUS_TYPE_UINT16:      Byte = 'q'.code.toByte()
        const val DBUS_TYPE_INT32:       Byte = 'i'.code.toByte()
        const val DBUS_TYPE_UINT32:      Byte = 'u'.code.toByte()
        const val DBUS_TYPE_INT64:       Byte = 'x'.code.toByte()
        const val DBUS_TYPE_UINT64:      Byte = 't'.code.toByte()
        const val DBUS_TYPE_DOUBLE:      Byte = 'd'.code.toByte()
        const val DBUS_TYPE_STRING:      Byte = 's'.code.toByte()
        const val DBUS_TYPE_OBJECT_PATH: Byte = 'o'.code.toByte()
        const val DBUS_TYPE_SIGNATURE:   Byte = 'g'.code.toByte()
        const val DBUS_TYPE_ARRAY:       Byte = 'a'.code.toByte()
        const val DBUS_TYPE_VARIANT:     Byte = 'v'.code.toByte()
        const val DBUS_TYPE_STRUCT:      Byte = 'r'.code.toByte()  // also '(' ')'
        const val DBUS_TYPE_DICT_ENTRY:  Byte = 'e'.code.toByte()  // also '{' '}'

        /** Name-request flags from dbus_bus_request_name. */
        const val DBUS_NAME_FLAG_REPLACE_EXISTING: Int = 0x2
        const val DBUS_NAME_FLAG_DO_NOT_QUEUE:     Int = 0x4

        /** Reply codes from dbus_bus_request_name. */
        const val DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER: Int = 1

        /** Library names — JDK's libraryLookup tries these in order. */
        private val LIB_CANDIDATES = listOf("dbus-1", "dbus-1.so.3", "libdbus-1.so.3")

        /**
         * Symbols this binding loads. Each entry: name → (return layout,
         * arg layouts...). The bindings are for the "client API" subset
         * the SNI backend uses; expand the set when adding new D-Bus
         * features.
         */
        private val LOAD_SET: List<Triple<String, java.lang.foreign.MemoryLayout?, List<MemoryLayout>>> = listOf(
            // Connection lifecycle
            //
            // We open a PRIVATE connection (dbus_bus_get_private), not the
            // process-shared dbus_bus_get one: this backend drains the bus
            // with its own dbus_connection_pop_message loop, and a shared
            // connection has a single incoming queue that any other libdbus
            // user in the process (a sibling notification library, say) would
            // pop from too -- stealing the tray host's property queries before
            // our pump ever sees them. A private connection is ours alone.
            Triple("dbus_bus_get_private",
                ValueLayout.ADDRESS,                         // DBusConnection*
                listOf(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),  // type, error*
            ),
            Triple("dbus_bus_request_name",
                ValueLayout.JAVA_INT,                         // int
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            ),
            // A private connection must be explicitly closed before the final
            // unref (a shared one must NOT be) -- close() detaches it from the
            // bus and releases the socket.
            Triple("dbus_connection_close",
                null,                                          // void
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_unref",
                null,                                          // void
                listOf(ValueLayout.ADDRESS),
            ),
            // libdbus defaults exit_on_disconnect ON, which _exit()s the whole
            // process if the session bus drops. A vanishing tray icon must not
            // take the host application down -- turn it off and let the pump
            // idle on a dead connection.
            Triple("dbus_connection_set_exit_on_disconnect",
                null,                                          // void
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),  // conn, dbus_bool_t
            ),
            Triple("dbus_connection_read_write",
                ValueLayout.JAVA_INT,                          // dbus_bool_t
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ),
            Triple("dbus_connection_pop_message",
                ValueLayout.ADDRESS,                           // DBusMessage* | NULL
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_send",
                ValueLayout.JAVA_INT,                          // dbus_bool_t
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_send_with_reply_and_block",
                ValueLayout.ADDRESS,                           // DBusMessage*
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            ),
            Triple("dbus_connection_flush",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
            // Match rules -- used to observe NameOwnerChanged for the
            // StatusNotifierWatcher so the item re-registers when the tray
            // host restarts (issue #10).
            Triple("dbus_bus_add_match",
                null,                                          // void
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), // conn, rule, error*
            ),

            // Message construction
            Triple("dbus_message_new_method_call",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_new_method_return",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_new_signal",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_new_error",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_unref",
                null,
                listOf(ValueLayout.ADDRESS),
            ),

            // Message inspection
            Triple("dbus_message_get_type",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_member",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_interface",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_path",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_destination",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_sender",
                ValueLayout.ADDRESS,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_serial",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_get_no_reply",
                ValueLayout.JAVA_INT,                          // dbus_bool_t
                listOf(ValueLayout.ADDRESS),
            ),

            // Iterator API
            Triple("dbus_message_iter_init",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_init_append",
                null,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_append_basic",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_open_container",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_close_container",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_recurse",
                null,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_next",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_get_arg_type",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_message_iter_get_basic",
                null,
                listOf(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),

            // Error API
            Triple("dbus_error_init",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_error_is_set",
                ValueLayout.JAVA_INT,
                listOf(ValueLayout.ADDRESS),
            ),
            Triple("dbus_error_free",
                null,
                listOf(ValueLayout.ADDRESS),
            ),
        )

        /**
         * Load libdbus into a fresh shared arena and bind every symbol in
         * [LOAD_SET]. Returns null if the library can't be found OR any
         * required symbol is missing — in either case the SNI backend
         * degrades to "no tray", same as if the user has no D-Bus daemon.
         */
        fun load(): DBusBindings? {
            val arena = Arena.ofShared()
            val lookup = LIB_CANDIDATES.firstNotNullOfOrNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            } ?: run {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((name, ret, args) in LOAD_SET) {
                val descriptor = if (ret == null) {
                    FunctionDescriptor.ofVoid(*args.toTypedArray())
                } else {
                    FunctionDescriptor.of(ret, *args.toTypedArray())
                }
                val symbol = lookup.find(name).orElse(null) ?: run {
                    arena.close()
                    return null
                }
                handles[name] = linker.downcallHandle(symbol, descriptor)
            }
            return DBusBindings(arena, handles)
        }
    }

    /**
     * `DBusError` struct layout — opaque to the bindings caller, but must
     * be allocated with the right size for `dbus_error_init` to populate.
     * libdbus reserves these fields:
     *
     * ```c
     * typedef struct DBusError {
     *     const char *name;       // 8
     *     const char *message;    // 8
     *     unsigned dummy1 : 1;
     *     unsigned dummy2 : 1;
     *     unsigned dummy3 : 1;
     *     unsigned dummy4 : 1;
     *     unsigned dummy5 : 1;
     *     void *padding1;         // 8
     * } DBusError;
     * ```
     *
     * Total: 32 bytes on x86_64 / aarch64. We don't actually read the
     * fields — error checking is done via dbus_error_is_set + the
     * error message printed at warn level.
     */
    val errorLayout: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("name"),
        ValueLayout.ADDRESS.withName("message"),
        ValueLayout.JAVA_INT.withName("flags"),       // packed bitfields
        MemoryLayout.paddingLayout(4),                 // alignment
        ValueLayout.ADDRESS.withName("padding1"),
    )

    /**
     * `DBusMessageIter` is a stack-allocated cursor -- libdbus says it's
     * "small" but exposes no struct definition in the public ABI. The real
     * struct on x86_64 / aarch64 is 72 bytes: two pointers (16), nine 32-bit
     * dummies + an int pad (40) = 56, then two trailing pointers `pad2`/`pad3`
     * at offsets 56 and 64, ending at 72. A 64-byte buffer let libdbus write
     * `pad3` (offset 64..71) past the allocation -- silent arena corruption on
     * every `dbus_message_iter_*` call. Reserve 80 for headroom; over-allocating
     * an opaque cursor is harmless.
     */
    val messageIterLayout: MemoryLayout = MemoryLayout.sequenceLayout(80, ValueLayout.JAVA_BYTE)
}

/**
 * Allocate a UTF-8 NUL-terminated string in this arena. libdbus expects
 * `const char *` style strings in every text field. Lives here (rather than
 * as a private helper in a single backend file) so any libtray code in this
 * package can reach it.
 */
internal fun java.lang.foreign.Arena.allocateUtf8(s: String): MemorySegment {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val segment = allocate((bytes.size + 1).toLong())
    if (bytes.isNotEmpty()) {
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
    }
    segment.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
    return segment
}
