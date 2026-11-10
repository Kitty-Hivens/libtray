package dev.hivens.libtray

/**
 * An icon file under src/test/resources/icons and the edge it should be shown
 * at once libtray has scaled it. Each one decodes through a different colour
 * model or ImageIO reader, and a native image only decodes the ones its
 * metadata covers, so the native checks set every one of them.
 */
internal class IconFixture(val name: String, val shownSize: Int) {
    fun bytes(): ByteArray =
        IconFixture::class.java.getResourceAsStream("/icons/$name")?.use { it.readAllBytes() }
            ?: error("missing test icon $name")
}

internal val ICON_FIXTURES = listOf(
    IconFixture("rgba8-32.png", 32),
    IconFixture("rgb8-32.png", 32),
    IconFixture("gray8-32.png", 32),
    IconFixture("graya8-32.png", 32),
    IconFixture("pal8-32.png", 32),
    IconFixture("pal4-32.png", 32),
    IconFixture("pal1-32.png", 32),
    IconFixture("gray1-32.png", 32),
    IconFixture("gray16-32.png", 32),
    IconFixture("rgb16-32.png", 32),
    IconFixture("rgba16-32.png", 32),
    // A palette PNG past maxIconSize, so it also goes through scaling.
    IconFixture("pal8-300.png", IconScaling.DEFAULT_MAX_SIZE),
    IconFixture("plain-32.gif", 32),
    IconFixture("plain-32.jpg", 32),
    IconFixture("plain-32.bmp", 32),
)
