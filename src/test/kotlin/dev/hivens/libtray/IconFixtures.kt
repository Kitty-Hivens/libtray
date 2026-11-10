package dev.hivens.libtray

/**
 * An icon file under src/test/resources/icons with what it is and what it
 * decodes to: [kind] as [fileKind] reads it from the bytes, [centre] as the
 * ARGB of the centre pixel the JVM decodes, and [shownSize] as the edge it is
 * shown at once libtray has scaled it. Together they cover the colour models,
 * bit depths and ImageIO readers icon decoding goes through, and a native
 * image only decodes the ones its metadata covers, so the native checks set
 * every one of them and compare the centre pixel.
 */
internal class IconFixture(val name: String, val kind: String, val centre: Int, val shownSize: Int = 32) {
    fun bytes(): ByteArray =
        IconFixture::class.java.getResourceAsStream("/icons/$name")?.use { it.readAllBytes() }
            ?: error("missing test icon $name")
}

internal val ICON_FIXTURES = listOf(
    IconFixture("rgba8-32.png", "png depth=8 colourType=6", 0xFFBB86FC.toInt()),
    IconFixture("rgb8-32.png", "png depth=8 colourType=2", 0xFFBB86FC.toInt()),
    IconFixture("gray8-32.png", "png depth=8 colourType=0", 0xFFBCBCBC.toInt()),
    IconFixture("graya8-32.png", "png depth=8 colourType=4", 0xFFBCBCBC.toInt()),
    IconFixture("pal8-32.png", "png depth=8 colourType=3", 0xFFBB86FC.toInt()),
    IconFixture("pal4-32.png", "png depth=4 colourType=3", 0xFFBB86FC.toInt()),
    IconFixture("pal1-32.png", "png depth=1 colourType=3", 0xFFBB86FC.toInt()),
    IconFixture("gray1-32.png", "png depth=1 colourType=0", 0xFFFFFFFF.toInt()),
    IconFixture("gray16-32.png", "png depth=16 colourType=0", 0xFFBCBCBC.toInt()),
    IconFixture("rgb16-32.png", "png depth=16 colourType=2", 0xFFBA85FB.toInt()),
    IconFixture("rgba16-32.png", "png depth=16 colourType=6", 0xFFBA85FB.toInt()),
    // A PNG with an iCCP chunk, as image editors write them.
    IconFixture("rgba16-iccp-32.png", "png depth=16 colourType=6", 0x807B0084.toInt()),
    // A palette PNG past maxIconSize, so it also goes through scaling.
    IconFixture("pal8-300.png", "png depth=8 colourType=3", 0xFFBB86FC.toInt(), IconScaling.DEFAULT_MAX_SIZE),
    IconFixture("plain-32.gif", "gif", 0xFF000000.toInt()),
    IconFixture("plain-32.jpg", "jpeg", 0xFF000000.toInt()),
    // A JPEG with an embedded ICC profile, as Photoshop or an iPhone save
    // them. Without the profile its centre would come out as ff7a0086.
    IconFixture("icc-32.jpg", "jpeg", 0xFFB800C0.toInt()),
    IconFixture("plain-32.bmp", "bmp", 0xFF000000.toInt()),
)

/** The format of [bytes] from their signature, for PNGs with the bit depth and colour type from IHDR. */
internal fun fileKind(bytes: ByteArray): String = when {
    bytes.size > 25 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() ->
        "png depth=${bytes[24].toInt() and 0xFF} colourType=${bytes[25].toInt() and 0xFF}"
    bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
    (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 -> "jpeg"
    bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
    else -> "unknown"
}

/** The ARGB of the centre pixel of [bytes] as [IconScaling.decode] reads them, or null if they do not decode. */
internal fun decodedCentre(bytes: ByteArray): Int? =
    runCatching { IconScaling.decode(bytes) }.getOrNull()?.let { it.getRGB(it.width / 2, it.height / 2) }

/**
 * What is wrong with [icon] in this process, or null if nothing. Checks the
 * centre pixel the decoder produces, since a native image missing a resource
 * can decode without an error and still get the colours wrong, and that
 * [setIcon] accepts the icon.
 */
internal fun iconProblem(icon: IconFixture, setIcon: (ByteArray) -> Boolean): String? {
    val bytes = icon.bytes()
    val centre = decodedCentre(bytes)
    if (centre != icon.centre) {
        return "${icon.name} decodes to centre ${centre?.toUInt()?.toString(16)}, expected ${icon.centre.toUInt().toString(16)}"
    }
    if (!setIcon(bytes)) return "${icon.name} was refused by setIcon"
    return null
}
