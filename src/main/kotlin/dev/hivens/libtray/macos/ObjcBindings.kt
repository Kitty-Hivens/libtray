package dev.hivens.libtray.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets

/**
 * Panama bindings to the Objective-C runtime + AppKit. Loaded once per
 * process via [ObjcBindings.load]; the resulting object holds:
 *
 *   * Method handles for `objc_*` runtime intrinsics (`msgSend` variants,
 *     class/selector lookup, runtime class registration for upcall targets).
 *   * AppKit class & selector caches — fast id lookup for the symbols
 *     [AppKitTrayImpl] hits on every operation.
 *
 * `objc_msgSend` is C-variadic by spec but Panama's [Linker] needs an
 * exact `FunctionDescriptor` per call shape. We declare one downcall
 * handle per parameter signature combination the tray backend uses.
 * Add a new variant here, not at the call site, when introducing a new
 * shape — keeps ABI surface auditable.
 *
 * **Architecture note.** ARM64 (Apple Silicon) unified `objc_msgSend` for
 * all return types; x86_64 (Intel) splits into `objc_msgSend_stret` for
 * struct returns and `objc_msgSend_fpret` for float/double returns. Our
 * call sites avoid struct-returning Cocoa methods (NSRect/NSPoint
 * accessors), so plain `objc_msgSend` covers both archs. If a Cocoa
 * method we add later returns a struct (e.g. `frame`), introduce a
 * `_stret` variant with a `#if __x86_64__`-style branch.
 */
internal class ObjcBindings private constructor(
    val arena: Arena,
    val handles: Map<String, MethodHandle>,
    private val classCache: MutableMap<String, MemorySegment>,
    private val selCache:   MutableMap<String, MemorySegment>,
) {

    fun handle(name: String): MethodHandle =
        handles[name] ?: error("ObjC handle not loaded: $name. Add to LOAD_SET in ObjcBindings.load.")

    /**
     * Resolve an Objective-C class by name (e.g. "NSStatusBar"). Cached
     * after the first lookup — class objects are stable for the JVM's
     * lifetime. Throws if the class isn't registered (typically a typo
     * or framework not loaded).
     */
    fun cls(name: String): MemorySegment = classCache.getOrPut(name) {
        Arena.ofConfined().use { tmp ->
            val nameSeg = tmp.allocateFrom(name)
            val result = handle("objc_getClass").invokeExact(nameSeg) as MemorySegment
            require(result.address() != 0L) { "objc_getClass returned NULL for '$name'" }
            result
        }
    }

    /**
     * Resolve an Objective-C selector by name (e.g. "alloc",
     * "initWithData:", "setImage:setToolTip:"). Cached — selectors are
     * stable for the JVM's lifetime, and `sel_registerName` allocates
     * them once globally inside the runtime.
     */
    fun sel(name: String): MemorySegment = selCache.getOrPut(name) {
        Arena.ofConfined().use { tmp ->
            val nameSeg = tmp.allocateFrom(name)
            handle("sel_registerName").invokeExact(nameSeg) as MemorySegment
        }
    }

    // ── NSString / NSData helpers ────────────────────────────────────────

    /**
     * Build an `NSString*` from a JVM string. Allocated as autoreleased,
     * so call sites that need long-lived references should `objc_retain`
     * the result.
     */
    fun nsString(text: String): MemorySegment {
        val cls = cls("NSString")
        val sel = sel("stringWithUTF8String:")
        return Arena.ofConfined().use { tmp ->
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val seg = tmp.allocate(bytes.size + 1L)
            seg.asByteBuffer().put(bytes)
            seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
            handle("objc_msgSend_id_id").invokeExact(cls, sel, seg) as MemorySegment
        }
    }

    /**
     * Build an `NSData*` from a JVM byte array. AppKit's `[NSImage
     * initWithData:]` accepts this directly to decode PNGs without
     * libtray needing its own decoder.
     */
    fun nsData(bytes: ByteArray): MemorySegment {
        val cls = cls("NSData")
        val sel = sel("dataWithBytes:length:")
        return Arena.ofConfined().use { tmp ->
            val seg = tmp.allocate(bytes.size.toLong())
            seg.asByteBuffer().put(bytes)
            handle("objc_msgSend_id_ptr_long").invokeExact(cls, sel, seg, bytes.size.toLong()) as MemorySegment
        }
    }

    companion object {

        /**
         * Library names — the Objective-C runtime + AppKit framework.
         * `dlopen` against the framework path resolves the AppKit-side
         * classes (NSStatusBar, NSImage, NSMenu, …) for `objc_getClass`.
         */
        private val LIBS = listOf(
            "libobjc.A.dylib",
            "/System/Library/Frameworks/AppKit.framework/AppKit",
            "/System/Library/Frameworks/Foundation.framework/Foundation",
        )

        /**
         * Symbols loaded — runtime intrinsics + the small set of `msgSend`
         * shape variants the tray backend needs.
         *
         * Naming: `objc_msgSend_<ret>_<arg>...` where types are abbreviated:
         *   * `id`   — Objective-C object pointer (`ADDRESS`)
         *   * `sel`  — selector pointer (`ADDRESS`)
         *   * `ptr`  — raw pointer (`ADDRESS`)
         *   * `long` — 64-bit integer (`JAVA_LONG`)
         *   * `double` — 64-bit float (`JAVA_DOUBLE`)
         *   * `void` — no return
         *
         * The receiver + selector pair (id, sel) is implicit in every
         * `msgSend` call — the named args follow.
         */
        private val LOAD_SET: List<Triple<String, String, FunctionDescriptor>> = listOf(
            // Runtime intrinsics
            Triple("objc_getClass",       "objc_getClass",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("sel_registerName",    "sel_registerName",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_retain",         "objc_retain",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            Triple("objc_release",        "objc_release",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
            Triple("objc_autoreleasePoolPush", "objc_autoreleasePoolPush",
                FunctionDescriptor.of(ValueLayout.ADDRESS)),
            Triple("objc_autoreleasePoolPop",  "objc_autoreleasePoolPop",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),

            // Class registration (for upcall target — see AppKitTrayImpl)
            Triple("objc_allocateClassPair", "objc_allocateClassPair",
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,    // Class returned
                    ValueLayout.ADDRESS,    // Class superclass
                    ValueLayout.ADDRESS,    // const char *name
                    ValueLayout.JAVA_LONG,  // size_t extraBytes
                )),
            Triple("class_addMethod", "class_addMethod",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_BOOLEAN,
                    ValueLayout.ADDRESS,    // Class
                    ValueLayout.ADDRESS,    // SEL
                    ValueLayout.ADDRESS,    // IMP (function pointer)
                    ValueLayout.ADDRESS,    // const char *types (signature string)
                )),
            Triple("objc_registerClassPair", "objc_registerClassPair",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
            Triple("class_createInstance", "class_createInstance",
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,    // id
                    ValueLayout.ADDRESS,    // Class
                    ValueLayout.JAVA_LONG,  // size_t extraBytes
                )),

            // ── objc_msgSend variants — one per argument shape ───────────

            // id msgSend(id, SEL) — zero-arg method calls (alloc, init, button, ...)
            Triple("objc_msgSend_id",        "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS)),

            // id msgSend(id, SEL, id) — single id-arg setters/builders
            Triple("objc_msgSend_id_id",     "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),

            // id msgSend(id, SEL, double) — statusItemWithLength:
            Triple("objc_msgSend_id_double", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)),

            // id msgSend(id, SEL, ptr, long) — dataWithBytes:length:
            Triple("objc_msgSend_id_ptr_long", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),

            // id msgSend(id, SEL, id, sel, id) — initWithTitle:action:keyEquivalent:
            Triple("objc_msgSend_id_id_sel_id", "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),

            // void msgSend(id, SEL, long) — setTag: (NSInteger = long)
            Triple("objc_msgSend_void_long", "objc_msgSend",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),

            // void msgSend(id, SEL, id) — addItem:, setTarget:, setMenu:, removeStatusItem:
            Triple("objc_msgSend_void_id",   "objc_msgSend",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),

            // void msgSend(id, SEL, sel) — setAction:
            Triple("objc_msgSend_void_sel",  "objc_msgSend",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),

            // long msgSend(id, SEL) — tag getter (NSInteger = long)
            Triple("objc_msgSend_long",      "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
        )

        /**
         * NSStatusItem length sentinel — `NSVariableStatusItemLength`
         * from `NSStatusBar.h`. Tells AppKit "size to fit the icon",
         * which is what every well-behaved menu-bar app picks.
         */
        const val NS_VARIABLE_STATUS_ITEM_LENGTH: Double = -1.0

        /**
         * Load runtime + AppKit + Foundation into a fresh shared arena
         * and bind every symbol in [LOAD_SET]. Returns null when not on
         * macOS (`dlopen` fails) or any required symbol is missing —
         * caller treats null as "no tray", same null-as-degrade path the
         * Linux SNI / Win32 backends use.
         */
        fun load(): ObjcBindings? {
            val arena = Arena.ofShared()
            val lookups = LIBS.mapNotNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            }
            // libobjc + AppKit are mandatory; Foundation is technically
            // bundled into AppKit but listed for completeness.
            if (lookups.size < 2) {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((alias, symbol, descriptor) in LOAD_SET) {
                val sym = lookups.firstNotNullOfOrNull { it.find(symbol).orElse(null) }
                    ?: run {
                        arena.close()
                        return null
                    }
                handles[alias] = linker.downcallHandle(sym, descriptor)
            }
            return ObjcBindings(arena, handles, HashMap(), HashMap())
        }
    }
}
