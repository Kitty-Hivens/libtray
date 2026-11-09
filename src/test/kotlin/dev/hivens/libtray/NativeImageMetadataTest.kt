package dev.hivens.libtray

import dev.hivens.libtray.linux.DBusBindings
import dev.hivens.libtray.linux.SniTrayImpl
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
        // A Windows checkout may turn the file's line endings into CRLF.
        file.readText().replace("\r\n", "\n") shouldBe expected
    }

    @Test
    fun `every upcall target is a static method matching its descriptor`() {
        for (target in UPCALL_TARGETS) {
            val type = target.descriptor.toMethodType()
            val method = target.owner.getDeclaredMethod(target.method, *type.parameterArray())
            Modifier.isStatic(method.modifiers) shouldBe true
            method.returnType shouldBe type.returnType()
        }
    }

    @Test
    fun `upcall stubs are only built through UpcallTarget`() {
        // A stub built anywhere else would be missing from the generated file.
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" && it.name != "Foreign.kt" && ".upcallStub(" in it.readText() }
            .map { it.path }
            .toList()
        offenders shouldBe emptyList()
    }

    @Test
    fun `downcall handles are only bound from the descriptor lists`() {
        // The metadata comes from the bindings' DOWNCALL_DESCRIPTORS. A handle
        // bound anywhere else must use a shape that is already in them.
        val binders = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" && ".downcallHandle(" in it.readText() }
            .map { it.name }
            .toSet()
        binders shouldBe setOf("DBusBindings.kt", "Win32Bindings.kt", "ObjcBindings.kt", "Win32TrayImpl.kt")
        // Win32TrayImpl binds the pre-instance DefWindowProcW fallback with the WndProc shape.
        Win32Bindings.DOWNCALL_DESCRIPTORS.contains(Win32Bindings.WNDPROC_DESCRIPTOR) shouldBe true
    }

    @Test
    fun `every UpcallTarget in the sources is in the registry`() {
        // The registry only sees targets created while a backend class
        // initialises. One behind `by lazy`, inside a function or in a class
        // missing from BACKENDS would drop out of the metadata without this.
        val declarations = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .sumOf { file -> file.readLines().count { "UpcallTarget(" in it && "class UpcallTarget(" !in it } }
        UPCALL_TARGETS.size shouldBe declarations
    }

    private fun render(): String {
        val downcalls = (DBusBindings.DOWNCALL_DESCRIPTORS + Win32Bindings.DOWNCALL_DESCRIPTORS + ObjcBindings.DOWNCALL_DESCRIPTORS)
            .map(::signature).distinct().sorted()
        val upcalls = UPCALL_TARGETS.map { signature(it.descriptor) }.distinct().sorted()

        val (jdkReflection, jdkResources) = jdkEntries()
        val upcallReflection = UPCALL_TARGETS.groupBy { it.owner }.entries.sortedBy { it.key.name }.map { (owner, targets) ->
            val methodLines = targets.sortedBy { it.method }.joinToString(",\n") { target ->
                val types = target.descriptor.toMethodType().parameterList().joinToString(", ") { "\"${it.name}\"" }
                """        { "name": "${target.method}", "parameterTypes": [$types] }"""
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

        /** Every backend class. Initialising one creates its upcall targets, which register themselves. */
        private val BACKENDS = listOf(AppKitTrayImpl::class.java, SniTrayImpl::class.java, Win32TrayImpl::class.java)

        /**
         * The static methods the backends turn into upcall stubs through
         * `MethodHandles.lookup().findStatic`, which a native image resolves
         * only for methods registered for reflection.
         */
        val UPCALL_TARGETS: List<UpcallTarget> by lazy {
            for (backend in BACKENDS) Class.forName(backend.name, true, backend.classLoader)
            // Only the backends': a target some other test creates must not end up in the shipped file.
            UpcallTarget.all.filter { it.owner in BACKENDS }
        }
    }
}
