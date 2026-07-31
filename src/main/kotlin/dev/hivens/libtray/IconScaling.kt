package dev.hivens.libtray

import org.slf4j.Logger
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
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
            // SCALE_SMOOTH (area averaging) rather than a bilinear draw:
            // reductions here are typically 4x or more, where bilinear
            // samples too sparsely and aliases the glyph into noise.
            val scaled = image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH)
            val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                g.drawImage(scaled, 0, 0, null)
            } finally {
                g.dispose()
            }
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
}
