package dev.hivens.libtray

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.util.concurrent.CopyOnWriteArrayList

/** The descriptor of a native function returning [ret], or void when [ret] is null. */
internal fun functionDescriptor(ret: MemoryLayout?, args: List<MemoryLayout>): FunctionDescriptor =
    if (ret == null) FunctionDescriptor.ofVoid(*args.toTypedArray()) else FunctionDescriptor.of(ret, *args.toTypedArray())

/**
 * A static method native code calls back into. Backends build every upcall
 * stub through this, and declare each target as a value in their companion.
 * A target registers itself when it is created, which happens when its backend
 * class is initialised, so the GraalVM metadata generated from the registry
 * cannot miss one that a backend declares, and the method's signature always
 * matches its [descriptor].
 */
internal class UpcallTarget(val owner: Class<*>, val method: String, val descriptor: FunctionDescriptor) {
    init {
        declared += this
    }

    fun stub(arena: Arena): MemorySegment {
        val handle = MethodHandles.lookup().findStatic(owner, method, descriptor.toMethodType())
        return Linker.nativeLinker().upcallStub(handle, descriptor, arena)
    }

    internal companion object {
        private val declared = CopyOnWriteArrayList<UpcallTarget>()

        /** Every target created so far, which covers a backend once its class is initialised. */
        val all: List<UpcallTarget> get() = declared.toList()
    }
}
