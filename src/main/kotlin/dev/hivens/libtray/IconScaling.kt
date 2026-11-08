package dev.hivens.libtray

import org.slf4j.Logger
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
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

    private val decoderFailureLogged = AtomicBoolean(false)

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
        val image = try {
            decode(bytes)
        } catch (t: Throwable) {
            // Bytes ImageIO cannot read are expected, the OS may still take
            // them. An Error is not: a native image missing its metadata ends
            // up here, and this is the only trace of it on macOS. It repeats on
            // every setIcon, so the stack is logged once per process.
            if (t is Error && decoderFailureLogged.compareAndSet(false, true)) {
                log.warn("The image decoder failed on a tray icon, sending it unscaled. Later failures are logged at debug.", t)
            } else {
                log.debug("The image decoder failed on a tray icon", t)
            }
            return bytes
        } ?: return bytes
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return bytes
        if (width <= maxSize && height <= maxSize) return bytes

        val scale = maxSize.toDouble() / max(width, height)
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())

        return runCatching {
            val scaled = areaAverageRows(width, height, targetWidth, targetHeight) { y, row ->
                image.getRGB(0, y, width, 1, row, 0, width)
            }
            val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
            out.setRGB(0, 0, targetWidth, targetHeight, scaled, 0, targetWidth)
            val encoded = ByteArrayOutputStream()
            val written = MemoryCacheImageOutputStream(encoded).use { ImageIO.write(out, "PNG", it) }
            if (!written) return bytes
            val result = encoded.toByteArray()
            log.warn(
                "Tray icon is {}x{}; scaled to {}x{} ({} -> {} bytes). Tray hosts draw at panel " +
                    "height, so the detail is not rendered either way. Pass a smaller icon, or set " +
                    "TrayBuilder.maxIconSize = null to send the original.",
                width, height, targetWidth, targetHeight, bytes.size, result.size,
            )
            result
        }.getOrElse {
            log.warn("Tray icon downscale failed, using the original {}x{} image", width, height, it)
            bytes
        }
    }

    /**
     * Decode an icon, or null when no ImageIO reader takes the bytes. Reads
     * through a memory cache: `ImageIO.read(InputStream)` backs its stream with
     * a temporary file, one per call by default, which an animated icon would
     * create on every frame and a read-only temp directory would fail on.
     */
    internal fun decode(bytes: ByteArray): BufferedImage? =
        ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))

    internal fun areaAverage(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray = areaAverageRows(sourceWidth, sourceHeight, targetWidth, targetHeight) { y, row ->
        System.arraycopy(source, y * sourceWidth, row, 0, sourceWidth)
    }

    /**
     * Shrink a `sourceWidth` x `sourceHeight` ARGB image, read one row at a
     * time through [readRow] so a large icon is not copied whole on top of its
     * decoded raster, to
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
    internal fun areaAverageRows(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        readRow: (y: Int, row: IntArray) -> Unit,
    ): IntArray {
        val out = IntArray(targetWidth * targetHeight)
        val stepX = sourceWidth.toDouble() / targetWidth
        val stepY = sourceHeight.toDouble() / targetHeight
        val row = IntArray(sourceWidth)
        // A source row on the boundary between two target rows is needed by
        // both, and target rows are built in order, so remember the last one.
        var loadedRow = -1
        // Running totals for the target row being built, one slot per target pixel.
        val area = DoubleArray(targetWidth)
        val alpha = DoubleArray(targetWidth)
        val red = DoubleArray(targetWidth)
        val green = DoubleArray(targetWidth)
        val blue = DoubleArray(targetWidth)
        for (ty in 0 until targetHeight) {
            val top = ty * stepY
            val bottom = (ty + 1) * stepY
            for (totals in arrayOf(area, alpha, red, green, blue)) totals.fill(0.0)
            var y = top.toInt()
            while (y < bottom && y < sourceHeight) {
                if (y != loadedRow) {
                    readRow(y, row)
                    loadedRow = y
                }
                val coverY = min(y + 1.0, bottom) - max(y.toDouble(), top)
                for (tx in 0 until targetWidth) {
                    val left = tx * stepX
                    val right = (tx + 1) * stepX
                    var x = left.toInt()
                    while (x < right && x < sourceWidth) {
                        val cover = (min(x + 1.0, right) - max(x.toDouble(), left)) * coverY
                        val pixel = row[x]
                        val a = (pixel ushr 24 and 0xFF).toDouble()
                        val weight = cover * a
                        area[tx] += cover
                        alpha[tx] += weight
                        red[tx] += weight * (pixel ushr 16 and 0xFF)
                        green[tx] += weight * (pixel ushr 8 and 0xFF)
                        blue[tx] += weight * (pixel and 0xFF)
                        x++
                    }
                }
                y++
            }
            for (tx in 0 until targetWidth) {
                out[ty * targetWidth + tx] = if (alpha[tx] <= 0.0) {
                    0
                } else {
                    (channel(alpha[tx] / area[tx]) shl 24) or (channel(red[tx] / alpha[tx]) shl 16) or
                        (channel(green[tx] / alpha[tx]) shl 8) or channel(blue[tx] / alpha[tx])
                }
            }
        }
        return out
    }

    private fun channel(value: Double): Int = (value + 0.5).toInt().coerceIn(0, 255)
}
