package dev.hivens.libtray

import org.slf4j.Logger
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
     * [maxSize]-square box with the aspect ratio preserved, or null when the
     * icon is past [MAX_DECODED_PIXELS] and [subsampleOversized] is off.
     *
     * Null is the only refusal. An undecodable image, a missing PNG writer,
     * or any other failure yields the original bytes, because a backend that
     * can still try to render something is better than one that has lost the
     * icon. An oversized icon is the exception, since handing it on would
     * only move the huge decode into the backend or the OS. `null` [maxSize]
     * disables scaling and this check entirely, and the bytes go out
     * untouched.
     */
    fun fit(bytes: ByteArray, maxSize: Int?, log: Logger, subsampleOversized: Boolean = false): ByteArray? {
        if (maxSize == null) return bytes
        // The header says whether there is anything to do, so an icon that
        // already fits is never decoded here.
        val size = runCatching { headerSize(bytes) }.getOrNull()
        if (size != null) {
            val (headerWidth, headerHeight) = size
            if (headerWidth.toLong() * headerHeight > MAX_DECODED_PIXELS && !subsampleOversized) {
                refuse(headerWidth, headerHeight, log)
                return null
            }
            if (headerWidth in 1..maxSize && headerHeight in 1..maxSize) return bytes
        }
        val image = try {
            decode(bytes, subsampleOversized, log)
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
     * Decode an icon, or null when no ImageIO reader takes the bytes or the
     * icon is past [MAX_DECODED_PIXELS] and [subsampleOversized] is off.
     *
     * The size comes from the header first, before the reader allocates a
     * raster for it: a PNG of a few dozen bytes can declare 8000 x 8000 and
     * cost a quarter of a gigabyte. With [subsampleOversized] such an icon
     * is read at every n-th pixel and row instead, small enough to fit.
     *
     * Reads through a memory cache: `ImageIO.read(InputStream)` backs its
     * stream with a temporary file, one per call by default, which an
     * animated icon would create on every frame and a read-only temp
     * directory would fail on.
     */
    internal fun decode(bytes: ByteArray, subsampleOversized: Boolean = false, log: Logger? = null): BufferedImage? =
        withReader(bytes) { reader ->
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            val param = reader.defaultReadParam
            val pixels = width.toLong() * height
            if (pixels > MAX_DECODED_PIXELS) {
                if (!subsampleOversized) {
                    refuse(width, height, log)
                    return@withReader null
                }
                val (stepX, stepY) = subsamplingSteps(width, height)
                param.setSourceSubsampling(stepX, stepY, 0, 0)
                log?.warn(
                    "Tray icon is {}x{}, past the {} pixel budget. Decoding every {}th column and {}th row, as " +
                        "TrayBuilder.subsampleOversizedIcons asks, so detail is lost. Pass a smaller icon.",
                    width, height, MAX_DECODED_PIXELS, stepX, stepY,
                )
            }
            reader.read(0, param)
        }

    /** Width and height from the image header, without decoding it. Null when no reader takes the bytes. */
    private fun headerSize(bytes: ByteArray): Pair<Int, Int>? =
        withReader(bytes) { reader -> reader.getWidth(0) to reader.getHeight(0) }

    /** Run [block] with the first ImageIO reader that takes [bytes], or return null when none does. */
    private inline fun <T> withReader(bytes: ByteArray, block: (ImageReader) -> T?): T? =
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, true, true)
                block(reader)
            } finally {
                reader.dispose()
            }
        }

    private fun refuse(width: Int, height: Int, log: Logger?) {
        log?.warn(
            "Tray icon is {}x{}, past the {} pixel budget, so it is not used. Pass a smaller icon, " +
                "or set TrayBuilder.subsampleOversizedIcons to decode it at lower quality.",
            width, height, MAX_DECODED_PIXELS,
        )
    }

    /** Most pixels an icon may decode to: 4096 x 4096, 64 MiB as ARGB. */
    const val MAX_DECODED_PIXELS: Long = 4096L * 4096

    /**
     * What subsampling aims under: a 4096-pixel row below [MAX_DECODED_PIXELS],
     * so the result also fits a single D-Bus array on Linux, where the
     * `IconPixmap` entry adds a few bytes to the pixels.
     */
    private const val MAX_SUBSAMPLED_PIXELS: Long = MAX_DECODED_PIXELS - 4096

    /**
     * The column and row steps that bring a [width] x [height] image under
     * [MAX_SUBSAMPLED_PIXELS]. One step for both axes, which keeps the aspect
     * ratio, grows until the result fits. Only when a side is down to one
     * pixel and the result still does not fit, as for a thin image, does the
     * other axis take further steps on its own.
     */
    internal fun subsamplingSteps(width: Int, height: Int): Pair<Int, Int> {
        fun shown(size: Int, step: Int): Long = (size.toLong() + step - 1) / step
        fun fits(stepX: Int, stepY: Int) = shown(width, stepX) * shown(height, stepY) <= MAX_SUBSAMPLED_PIXELS
        var step = max(1, sqrt(width.toDouble() * height / MAX_SUBSAMPLED_PIXELS).toInt())
        while (!fits(step, step) && shown(width, step) > 1 && shown(height, step) > 1) step++
        var stepX = step
        var stepY = step
        while (!fits(stepX, stepY)) {
            if (shown(width, stepX) > shown(height, stepY)) stepX++ else stepY++
        }
        return stepX to stepY
    }

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
