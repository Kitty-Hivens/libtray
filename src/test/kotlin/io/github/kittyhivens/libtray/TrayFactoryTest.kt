package io.github.kittyhivens.libtray

import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

/**
 * Factory-level tests. Every backend is currently stubbed (returns null
 * from its `create` companion), so [Tray.create] returns null on every
 * platform. As individual backends land, this suite gains positive
 * assertions for the ones that can be exercised in CI (Linux first via
 * dbus-launch in a container).
 */
class TrayFactoryTest {

    private val sampleIcon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun `create returns null while every backend is stubbed`() {
        // Reachable on every CI runner OS — the factory dispatches by
        // os.name, all three known branches return null today.
        val tray = Tray.create(TrayBuilder(title = "Test", iconBytes = sampleIcon))
        tray.shouldBeNull()
    }

    @Test
    fun `create returns null on unknown os without throwing`() {
        // Defensive: a host with an os.name we don't recognise (Plan9,
        // some hypothetical future BSD variant) must degrade gracefully
        // rather than blow up the whole launcher startup. Mutate the
        // property for the call, restore after.
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Plan9")
            val tray = Tray.create(TrayBuilder(title = "Test", iconBytes = sampleIcon))
            tray.shouldBeNull()
        } finally {
            if (original == null) System.clearProperty("os.name")
            else System.setProperty("os.name", original)
        }
    }
}
