package dev.hivens.libtray

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A solid-colour RGBA PNG built without AWT. The native-image checks use it
 * so that the only AWT and ImageIO work in them is libtray's own, which is
 * what the shipped metadata has to cover.
 */
internal fun solidPng(size: Int, argb: Int = 0xFFBB86FC.toInt()): ByteArray {
    val row = ByteArray(1 + size * 4)  // filter byte 0, then RGBA per pixel
    for (x in 0 until size) {
        row[1 + x * 4] = (argb ushr 16).toByte()
        row[2 + x * 4] = (argb ushr 8).toByte()
        row[3 + x * 4] = argb.toByte()
        row[4 + x * 4] = (argb ushr 24).toByte()
    }
    val raw = ByteArrayOutputStream().apply { repeat(size) { write(row) } }.toByteArray()
    val deflater = Deflater().apply { setInput(raw); finish() }
    val compressed = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))

    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
    val header = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeInt(size); writeInt(size)
            writeByte(8)   // bit depth
            writeByte(6)   // colour type RGBA
            writeByte(0); writeByte(0); writeByte(0)  // compression, filter, interlace
        }
    }.toByteArray()
    chunk(out, "IHDR", header)
    chunk(out, "IDAT", compressed.toByteArray())
    chunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
}

private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(typeBytes); update(data) }
    DataOutputStream(out).apply {
        writeInt(data.size)
        write(typeBytes)
        write(data)
        writeInt(crc.value.toInt())
    }
}
