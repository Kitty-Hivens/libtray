package dev.hivens.libtray

import java.awt.image.BufferedImage

/**
 * An icon file under src/test/resources/icons with what it is and what it
 * decodes to: [kind] as [fileKind] reads it from the bytes, [corner] and
 * [opposite] as the ARGB the JVM decodes at the two [samplePoints], and
 * [shownSize] as the edge it is shown at once libtray has scaled it. Together
 * they cover the colour models, bit depths and ImageIO readers icon decoding
 * goes through, and a native image only decodes the ones its metadata covers,
 * so the native checks set every one of them and compare both points.
 *
 * Every icon has a top-left quadrant that differs from the rest. A single
 * colour only shows that the decoder produced it somewhere, while two points
 * in opposite quadrants also catch an image that comes out flipped, mirrored,
 * with its channels swapped or with the wrong row stride.
 */
internal class IconFixture(
    val name: String,
    val kind: String,
    val corner: Int,
    val opposite: Int,
    val shownSize: Int = 32,
) {
    fun bytes(): ByteArray =
        IconFixture::class.java.getResourceAsStream("/icons/$name")?.use { it.readAllBytes() }
            ?: error("missing test icon $name")
}

private const val TEAL = 0xFF03DAC6.toInt()
private const val PURPLE = 0xFFBB86FC.toInt()
private const val DARK_GRAY = 0xFF898989.toInt()
private const val LIGHT_GRAY = 0xFFDFDFDF.toInt()

internal val ICON_FIXTURES = listOf(
    IconFixture("rgba8-32.png", "png depth=8 colourType=6", TEAL, PURPLE),
    IconFixture("rgb8-32.png", "png depth=8 colourType=2", TEAL, PURPLE),
    IconFixture("gray8-32.png", "png depth=8 colourType=0", DARK_GRAY, LIGHT_GRAY),
    IconFixture("graya8-32.png", "png depth=8 colourType=4", DARK_GRAY, LIGHT_GRAY),
    IconFixture("pal8-32.png", "png depth=8 colourType=3", TEAL, PURPLE),
    IconFixture("pal4-32.png", "png depth=4 colourType=3", TEAL, PURPLE),
    IconFixture("pal1-32.png", "png depth=1 colourType=3", TEAL, PURPLE),
    IconFixture("gray1-32.png", "png depth=1 colourType=0", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
    IconFixture("gray16-32.png", "png depth=16 colourType=0", DARK_GRAY, LIGHT_GRAY),
    IconFixture("rgb16-32.png", "png depth=16 colourType=2", TEAL, PURPLE),
    IconFixture("rgba16-32.png", "png depth=16 colourType=6", TEAL, PURPLE),
    // A PNG with an iCCP chunk, as image editors write them. A gradient, so
    // its quadrants differ without a drawn corner.
    IconFixture("rgba16-iccp-32.png", "png depth=16 colourType=6", 0x20DE0021, 0xD72100DE.toInt()),
    // A palette PNG past maxIconSize, so it also goes through scaling.
    IconFixture("pal8-300.png", "png depth=8 colourType=3", TEAL, PURPLE, IconScaling.DEFAULT_MAX_SIZE),
    IconFixture("plain-32.gif", "gif", TEAL, PURPLE),
    // JPEG is lossy, so the corner comes back one level off the colour drawn.
    IconFixture("plain-32.jpg", "jpeg", 0xFF02DAC5.toInt(), PURPLE),
    // A JPEG with an embedded ICC profile, as Photoshop or an iPhone save
    // them. Without the profile its points would come out as ffdd001f and
    // ff2000df.
    IconFixture("icc-32.jpg", "jpeg", 0xFFEF0062.toInt(), 0xFF6300F0.toInt()),
    IconFixture("plain-32.bmp", "bmp", TEAL, PURPLE),
)

/**
 * The two points compared on a [width] x [height] image: one inside the top-left
 * quadrant and one inside the bottom-right, an eighth in from each edge so a
 * lossy format's blur at the quadrant border does not reach them.
 */
internal fun samplePoints(width: Int, height: Int): List<Pair<Int, Int>> =
    listOf(width / 8 to height / 8, width - 1 - width / 8 to height - 1 - height / 8)

/** The format of [bytes] from their signature, for PNGs with the bit depth and colour type from IHDR. */
internal fun fileKind(bytes: ByteArray): String = when {
    bytes.size > 25 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() ->
        "png depth=${bytes[24].toInt() and 0xFF} colourType=${bytes[25].toInt() and 0xFF}"
    bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
    (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 -> "jpeg"
    bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
    else -> "unknown"
}

/** The ARGB at the two [samplePoints] of [image]. */
internal fun samples(image: BufferedImage): List<Int> =
    samplePoints(image.width, image.height).map { (x, y) -> image.getRGB(x, y) }

/** The ARGB at the two [samplePoints] of [bytes] as [IconScaling.decode] reads them, or null if they do not decode. */
internal fun decodedSamples(bytes: ByteArray): List<Int>? =
    runCatching { IconScaling.decode(bytes) }.getOrNull()?.let(::samples)

/** ARGB values as hex, for failure messages. */
internal fun hex(values: List<Int>?): String = values?.joinToString(", ") { it.toUInt().toString(16) } ?: "nothing"

/**
 * What is wrong with [icon] in this process, or null if nothing. Checks the
 * pixels the decoder produces at both sample points, since a native image
 * missing a resource can decode without an error and still get the colours
 * wrong, and that [setIcon] accepts the icon.
 */
internal fun iconProblem(icon: IconFixture, setIcon: (ByteArray) -> Boolean): String? {
    val bytes = icon.bytes()
    val decoded = decodedSamples(bytes)
    val expected = listOf(icon.corner, icon.opposite)
    if (decoded != expected) {
        return "${icon.name} decodes to ${hex(decoded)}, expected ${hex(expected)}"
    }
    if (!setIcon(bytes)) return "${icon.name} was refused by setIcon"
    return null
}
