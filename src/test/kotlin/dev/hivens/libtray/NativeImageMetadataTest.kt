package dev.hivens.libtray

import dev.hivens.libtray.linux.DBusBindings
import dev.hivens.libtray.macos.AppKitTrayImpl
import dev.hivens.libtray.macos.ObjcBindings
import dev.hivens.libtray.windows.Win32Bindings
import dev.hivens.libtray.windows.Win32TrayImpl
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.foreign.AddressLayout
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.reflect.Modifier

/**
 * Keeps the GraalVM native-image metadata shipped in the jar in step with the
 * code. A native image only supports the foreign calls and the reflective
 * lookups registered at build time, so a downcall shape or an upcall target
 * added without updating the file would fail only in a consumer's native
 * build. This rebuilds the expected file from the descriptor lists the
 * backends bind from, plus the JDK entries for libtray's AWT and ImageIO use
 * kept in src/test/resources/native-image/awt-imageio-entries.txt, and fails
 * when the committed file differs.
 *
 * To regenerate: `LIBTRAY_WRITE_NATIVE_METADATA=1 ./gradlew test --tests '*NativeImageMetadataTest'`.
 */
class NativeImageMetadataTest {

    @Test
    fun `shipped native-image metadata matches the code`() {
        val expected = render()
        val file = File(METADATA_PATH)
        if (System.getenv("LIBTRAY_WRITE_NATIVE_METADATA") == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
        }
        check(file.exists()) { "$METADATA_PATH is missing, regenerate it (see the class KDoc)" }
        file.readText() shouldBe expected
    }

    @Test
    fun `every upcall target exists as a static method`() {
        for ((owner, methods) in UPCALL_TARGETS) {
            for ((name, parameters) in methods) {
                val method = owner.getDeclaredMethod(name, *parameters.toTypedArray())
                Modifier.isStatic(method.modifiers) shouldBe true
            }
        }
    }

    private fun render(): String {
        val downcalls = (DBusBindings.DOWNCALL_DESCRIPTORS + Win32Bindings.DOWNCALL_DESCRIPTORS + ObjcBindings.DOWNCALL_DESCRIPTORS)
            .map(::signature).distinct().sorted()
        val upcalls = listOf(
            Win32Bindings.WNDPROC_DESCRIPTOR,
            AppKitTrayImpl.TRAMPOLINE_DESCRIPTOR,
            AppKitTrayImpl.OBJC_METHOD_DESCRIPTOR,
        ).map(::signature).distinct().sorted()

        val (jdkReflection, jdkResources) = jdkEntries()
        val upcallReflection = UPCALL_TARGETS.entries.sortedBy { it.key.name }.map { (owner, methods) ->
            val methodLines = methods.sortedBy { it.first }.joinToString(",\n") { (name, parameters) ->
                val types = parameters.joinToString(", ") { "\"${it.name}\"" }
                """        { "name": "$name", "parameterTypes": [$types] }"""
            }
            "    {\n      \"type\": \"${owner.name}\",\n      \"methods\": [\n$methodLines\n      ]\n    }"
        }
        val reflection = (upcallReflection + jdkReflection.map { "    $it" }).joinToString(",\n")
        val resources = jdkResources.joinToString(",\n") { "    $it" }
        return buildString {
            append("{\n")
            append("  \"reflection\": [\n").append(reflection).append("\n  ],\n")
            append("  \"resources\": [\n").append(resources).append("\n  ],\n")
            append("  \"foreign\": {\n")
            append("    \"downcalls\": [\n").append(downcalls.joinToString(",\n") { "      $it" }).append("\n    ],\n")
            append("    \"upcalls\": [\n").append(upcalls.joinToString(",\n") { "      $it" }).append("\n    ]\n")
            append("  }\n")
            append("}\n")
        }
    }

    /** The REFLECTION and RESOURCES sections of awt-imageio-entries.txt, one JSON entry per line. */
    private fun jdkEntries(): Pair<List<String>, List<String>> {
        val text = javaClass.getResource("/native-image/awt-imageio-entries.txt")?.readText()
            ?: error("awt-imageio-entries.txt is missing from the test resources")
        val sections = mutableMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in text.lines()) {
            when {
                line.isBlank() || line.startsWith("#") -> Unit
                line == "REFLECTION" || line == "RESOURCES" -> current = sections.getOrPut(line) { mutableListOf() }
                else -> current?.add(line) ?: error("entry before any section: $line")
            }
        }
        return sections["REFLECTION"].orEmpty() to sections["RESOURCES"].orEmpty()
    }

    private fun signature(descriptor: FunctionDescriptor): String {
        val returnType = descriptor.returnLayout().map(::typeName).orElse("void")
        val parameters = descriptor.argumentLayouts().joinToString(", ") { "\"${typeName(it)}\"" }
        return """{ "returnType": "$returnType", "parameterTypes": [$parameters] }"""
    }

    private fun typeName(layout: MemoryLayout): String = when {
        layout is AddressLayout -> "void*"
        layout is ValueLayout -> when (layout.carrier()) {
            Int::class.javaPrimitiveType -> "jint"
            Long::class.javaPrimitiveType -> "jlong"
            Double::class.javaPrimitiveType -> "jdouble"
            Float::class.javaPrimitiveType -> "jfloat"
            Boolean::class.javaPrimitiveType -> "jboolean"
            Byte::class.javaPrimitiveType -> "jbyte"
            Short::class.javaPrimitiveType -> "jshort"
            Char::class.javaPrimitiveType -> "jchar"
            else -> error("no native-image name for $layout")
        }
        else -> error("struct or sequence layouts in a call need their own metadata entry: $layout")
    }

    private companion object {
        const val METADATA_PATH = "src/main/resources/META-INF/native-image/dev.hivens/libtray/reachability-metadata.json"

        private val MS = MemorySegment::class.java
        private val INT = Int::class.javaPrimitiveType!!
        private val LONG = Long::class.javaPrimitiveType!!

        /**
         * The static methods the backends turn into upcall stubs through
         * `MethodHandles.lookup().findStatic`, which a native image resolves
         * only for methods registered for reflection.
         */
        val UPCALL_TARGETS: Map<Class<*>, List<Pair<String, List<Class<*>>>>> = mapOf(
            Win32TrayImpl::class.java to listOf(
                "wndProcEntry" to listOf(MS, INT, LONG, LONG),
            ),
            AppKitTrayImpl::class.java to listOf(
                "dispatchTrampoline" to listOf(MS),
                "onMenuItemEntry" to listOf(MS, MS, MS),
                "onStatusItemClickEntry" to listOf(MS, MS, MS),
                "onStatusViewMouseEntry" to listOf(MS, MS, MS),
            ),
        )
    }
}
