package dev.hivens.libtray

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles

/** The descriptor of a native function returning [ret], or void when [ret] is null. */
internal fun functionDescriptor(ret: MemoryLayout?, args: List<MemoryLayout>): FunctionDescriptor =
    if (ret == null) FunctionDescriptor.ofVoid(*args.toTypedArray()) else FunctionDescriptor.of(ret, *args.toTypedArray())

/**
 * A static method native code calls back into. Backends build every upcall
 * stub through this, and each declares its targets, so the GraalVM metadata
 * generated from those declarations cannot miss one and the method's signature
 * always matches its [descriptor].
 */
internal class UpcallTarget(val owner: Class<*>, val method: String, val descriptor: FunctionDescriptor) {
    fun stub(arena: Arena): MemorySegment {
        val handle = MethodHandles.lookup().findStatic(owner, method, descriptor.toMethodType())
        return Linker.nativeLinker().upcallStub(handle, descriptor, arena)
    }
}
