package dev.hivens.libtray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class IconScalingTest {

    private val log = LoggerFactory.getLogger("libtray.test")

    private fun png(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color(0xBB, 0x86, 0xFC, 0xFF)
            g.fillOval(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "PNG", it) }.toByteArray()
    }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> =
        ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }

    @Test
    fun `an icon already within the limit is passed through untouched`() {
        val bytes = png(32, 32)
        // Identity, not just equality: no decode / re-encode round trip,
        // so the caller's exact bytes reach the OS.
        IconScaling.fit(bytes, 256, log) shouldBe bytes
    }

    @Test
    fun `an icon exactly at the limit is not scaled`() {
        val bytes = png(256, 256)
        IconScaling.fit(bytes, 256, log) shouldBe bytes
    }

    @Test
    fun `an oversized icon is scaled to the limit`() {
        val bytes = png(1024, 1024)
        val fitted = IconScaling.fit(bytes, 256, log)

        fitted shouldNotBe bytes
        sizeOf(fitted) shouldBe (256 to 256)
        // The whole point is fewer bytes on the wire / through FFM.
        (fitted.size < bytes.size) shouldBe true
    }

    @Test
    fun `the scaled icon still carries the picture`() {
        // Dimensions alone would also pass for a fully blank result, which
        // is the failure mode of drawing an asynchronously-produced Image:
        // getScaledInstance does not block, so a null-observer drawImage can
        // legitimately paint nothing. Assert on pixels instead.
        val img = ImageIO.read(ByteArrayInputStream(IconScaling.fit(png(1024, 1024), 64, log)))

        val centre = img.getRGB(32, 32)
        (centre ushr 24 and 0xFF) shouldBe 0xFF          // opaque inside the circle
        (centre and 0xFFFFFF) shouldBe 0xBB86FC          // and the colour we drew
        (img.getRGB(0, 0) ushr 24 and 0xFF) shouldBe 0   // transparent outside it
    }

    @Test
    fun `a non-square icon keeps its aspect ratio`() {
        // The long edge sets the bound; the short one follows, and neither
        // may exceed the limit or the Win32 backend would still reject it.
        val fitted = IconScaling.fit(png(1000, 500), 100, log)
        sizeOf(fitted) shouldBe (100 to 50)
    }

    @Test
    fun `a very lopsided icon never collapses an edge to zero`() {
        // 4000x3 at a 256 bound rounds the short edge to 0.192 px; a zero
        // dimension would throw out of BufferedImage rather than degrade.
        val fitted = IconScaling.fit(png(4000, 3), 256, log)
        val (w, h) = sizeOf(fitted)
        w shouldBe 256
        h shouldBe 1
    }

    @Test
    fun `area averaging takes the mean of each covered block`() {
        // 4x2 -> 2x1: each target pixel averages a 2x2 block.
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val source = intArrayOf(
            red, blue, black, white,
            red, blue, white, black,
        )
        IconScaling.areaAverage(source, 4, 2, 2, 1).toList() shouldBe listOf(
            0xFF800080.toInt(),  // half red, half blue
            0xFF808080.toInt(),  // half black, half white
        )
    }

    @Test
    fun `area averaging weights partly covered pixels by their coverage`() {
        // 3x1 -> 2x1: the middle pixel is split between both targets.
        val source = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt() or 0xC0, 0xFF0000FF.toInt())
        // Left: 1.0 x 0 + 0.5 x 0xC0 over 1.5, right: 0.5 x 0xC0 + 1.0 x 0xFF over 1.5.
        IconScaling.areaAverage(source, 3, 1, 2, 1).toList() shouldBe listOf(0xFF000040.toInt(), 0xFF0000EA.toInt())
    }

    @Test
    fun `transparent pixels do not tint the edge`() {
        // An opaque red pixel next to a fully transparent white one averages
        // to half-transparent red. Averaging colour without the alpha weight
        // would give pink, the halo premultiplied averaging avoids.
        val source = intArrayOf(0xFFFF0000.toInt(), 0x00FFFFFF)
        IconScaling.areaAverage(source, 2, 1, 1, 1).single() shouldBe 0x80FF0000.toInt()
    }

    @Test
    fun `a fully transparent area stays fully transparent`() {
        IconScaling.areaAverage(IntArray(4) { 0x00FFFFFF }, 2, 2, 1, 1).single() shouldBe 0
    }

    @Test
    fun `a null limit disables scaling`() {
        val bytes = png(1024, 1024)
        IconScaling.fit(bytes, null, log) shouldBe bytes
    }

    @Test
    fun `undecodable bytes are handed back rather than dropped`() {
        // Losing the icon outright would be worse than letting the backend
        // try: some platforms accept formats ImageIO does not read.
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        IconScaling.fit(garbage, 256, log) shouldBe garbage
    }

    @Test
    fun `the builder scales by default and honours an explicit opt-out`() {
        TrayBuilder(title = "T", iconBytes = png(8, 8)).maxIconSize shouldBe IconScaling.DEFAULT_MAX_SIZE
        TrayBuilder(title = "T", iconBytes = png(8, 8), maxIconSize = null).maxIconSize shouldBe null
    }
}
