package dev.hivens.libtray

import org.slf4j.Logger
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shrinks oversized icons before a backend hands them to the OS.
 *
 * A tray icon is drawn at roughly the height of a panel — 16 to 32 logical
 * pixels, 64 on a HiDPI panel at 2x. Application icons, by contrast, are
 * routinely 512 or 1024 square, and passing one straight through is
 * expensive in ways that are invisible until something is slow:
 *
 *  * Linux marshals the raw ARGB into the `IconPixmap` property on every
 *    query the host makes. A 1024x1024 icon is 4 MiB per query.
 *  * Windows rejects anything past 256 outright — `CreateIcon` is
 *    documented for cursor/icon dimensions, and libtray logged a warning
 *    and left the tray icon-less.
 *  * macOS decodes the full-size PNG into an NSImage on every `setIcon`.
 *
 * So the default is to fit the icon inside [DEFAULT_MAX_SIZE] and say so
 * once, loudly enough to be actionable. Callers who genuinely want the
 * original bytes on the wire set [TrayBuilder.maxIconSize] to null.
 */
internal object IconScaling {

    /**
     * Longest edge an icon is allowed before it gets scaled down. 256 is
     * the Win32 ceiling, and above it no tray host on any of the three
     * platforms shows more detail — it just moves more bytes.
     */
    const val DEFAULT_MAX_SIZE: Int = 256

    /**
     * Return [bytes] unchanged, or a PNG re-encoding scaled to fit a
     * [maxSize]-square box with the aspect ratio preserved.
     *
     * Never throws and never returns empty: an undecodable image, a
     * missing PNG writer, or any other failure yields the original bytes,
     * because a backend that can still try to render something is better
     * than one that has lost the icon. `null` [maxSize] disables scaling
     * entirely.
     */
    fun fit(bytes: ByteArray, maxSize: Int?, log: Logger): ByteArray {
        if (maxSize == null) return bytes
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return bytes
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return bytes
        if (width <= maxSize && height <= maxSize) return bytes

        val scale = maxSize.toDouble() / max(width, height)
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())

        return runCatching {
            val source = IntArray(width * height)
            image.getRGB(0, 0, width, height, source, 0, width)
            val scaled = areaAverage(source, width, height, targetWidth, targetHeight)
            val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
            out.setRGB(0, 0, targetWidth, targetHeight, scaled, 0, targetWidth)
            val encoded = ByteArrayOutputStream()
            if (!ImageIO.write(out, "PNG", encoded)) return bytes
            val result = encoded.toByteArray()
            log.warn(
                "Tray icon is {}x{}; scaled to {}x{} ({} -> {} bytes). Tray hosts draw at panel " +
                    "height, so the detail is not rendered either way. Pass a smaller icon, or set " +
                    "TrayBuilder.maxIconSize = null to send the original.",
                width, height, targetWidth, targetHeight, bytes.size, result.size,
            )
            result
        }.getOrElse {
            log.warn("Tray icon downscale failed ({}); using the original {}x{} image", it.message, width, height)
            bytes
        }
    }

    /**
     * Shrink [source], `sourceWidth` x `sourceHeight` ARGB pixels, to
     * `targetWidth` x `targetHeight` by area averaging: each target pixel is
     * the mean of the source area it covers, with partly covered source
     * pixels weighted by how much of them it covers. Reductions here are
     * typically 4x or more, where a bilinear draw samples too sparsely and
     * aliases the glyph into noise.
     *
     * Colour is averaged premultiplied by alpha, so the colour a fully
     * transparent pixel happens to carry cannot bleed into the icon's edge
     * as a halo.
     *
     * It is the same reduction as `Image.getScaledInstance(SCALE_SMOOTH)`,
     * done in plain array arithmetic because that call goes through the AWT
     * Toolkit image pipeline and Java2D. Those start the platform toolkit
     * (X11 on Linux) and need a large, per-platform set of GraalVM
     * native-image metadata. Against its output, alpha matches exactly and
     * colour within one level.
     */
    internal fun areaAverage(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        val out = IntArray(targetWidth * targetHeight)
        val stepX = sourceWidth.toDouble() / targetWidth
        val stepY = sourceHeight.toDouble() / targetHeight
        for (ty in 0 until targetHeight) {
            val top = ty * stepY
            val bottom = (ty + 1) * stepY
            for (tx in 0 until targetWidth) {
                val left = tx * stepX
                val right = (tx + 1) * stepX
                var area = 0.0
                var alpha = 0.0
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var y = top.toInt()
                while (y < bottom && y < sourceHeight) {
                    val coverY = min(y + 1.0, bottom) - max(y.toDouble(), top)
                    var x = left.toInt()
                    while (x < right && x < sourceWidth) {
                        val cover = (min(x + 1.0, right) - max(x.toDouble(), left)) * coverY
                        val pixel = source[y * sourceWidth + x]
                        val a = (pixel ushr 24 and 0xFF).toDouble()
                        val weight = cover * a
                        area += cover
                        alpha += weight
                        red += weight * (pixel ushr 16 and 0xFF)
                        green += weight * (pixel ushr 8 and 0xFF)
                        blue += weight * (pixel and 0xFF)
                        x++
                    }
                    y++
                }
                out[ty * targetWidth + tx] = if (alpha <= 0.0) {
                    0
                } else {
                    (channel(alpha / area) shl 24) or (channel(red / alpha) shl 16) or
                        (channel(green / alpha) shl 8) or channel(blue / alpha)
                }
            }
        }
        return out
    }

    private fun channel(value: Double): Int = (value + 0.5).toInt().coerceIn(0, 255)
}
