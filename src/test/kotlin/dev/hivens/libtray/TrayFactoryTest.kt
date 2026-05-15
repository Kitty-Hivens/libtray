package dev.hivens.libtray

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Factory-level tests. Real backends are platform-conditional and depend
 * on infrastructure the test runner may or may not have (D-Bus session
 * bus on Linux; tray watcher on the desktop), so the assertions are
 * shaped around "doesn't throw, returns sensible value" rather than
 * "always returns a live Tray".
 */
class TrayFactoryTest {

    // 1x1 transparent PNG — minimal but ImageIO-decodable so the SNI
    // backend's pngToPixmaps call doesn't choke during construction.
    private val sampleIcon: ByteArray = byteArrayOf(
        // PNG header + IHDR + IDAT + IEND chunks for a 1x1 transparent image.
        // Generated via `convert -size 1x1 xc:transparent png:- | xxd -i`.
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x62, 0x00, 0x01, 0x00, 0x00, 0x05,
        0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @Test
    fun `create on unknown os returns null without throwing`() {
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

    @Test
    fun `create on host platform either returns an open Tray or degrades to null`() {
        // We can't control whether the test runner has a usable session
        // bus + tray watcher, so the assertion is "the contract holds":
        // either we got a live tray (its isOpen is true and close is
        // safe to call) or we got null (no native backend reachable).
        // CI containers without D-Bus go down the null path; the
        // maintainer's desktop goes down the live-tray path. Both pass.
        val tray = Tray.create(TrayBuilder(title = "libtray-factory-test", iconBytes = sampleIcon))
        if (tray != null) {
            tray.isOpen shouldBe true
            tray.close()  // critical — leaves the daemon thread alive otherwise
        }
    }
}
