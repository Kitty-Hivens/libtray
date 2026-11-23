package dev.hivens.libtray.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Reads an HICON's colour pixels back the way Windows holds them, through
 * `GetIconInfo` and `GetDIBits`. The library only creates icons, so these
 * calls are bound here for the tests and never reach the jar or its
 * native-image metadata.
 */
internal object IconReadback {
    private val linker = Linker.nativeLinker()
    private val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
    private val gdi32 = SymbolLookup.libraryLookup("gdi32", Arena.global())

    private fun bind(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)

    // BOOL GetIconInfo(HICON, PICONINFO)
    private val getIconInfo = bind(user32, "GetIconInfo",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    // HDC GetDC(HWND)
    private val getDC = bind(user32, "GetDC", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    // int ReleaseDC(HWND, HDC)
    private val releaseDC = bind(user32, "ReleaseDC",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    // int GetDIBits(HDC, HBITMAP, UINT start, UINT lines, LPVOID bits, LPBITMAPINFO, UINT usage)
    private val getDIBits = bind(gdi32, "GetDIBits", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
    ))
    // BOOL DeleteObject(HGDIOBJ)
    private val deleteObject = bind(gdi32, "DeleteObject", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

    /**
     * The ARGB pixels of the [size] x [size] [icon], row by row from the top.
     *
     * ICONINFO is BOOL fIcon, two DWORD hotspots, then HBITMAP hbmMask at 16 and
     * hbmColor at 24, which GetIconInfo creates and the caller deletes. The
     * BITMAPINFOHEADER asks for 32-bit BI_RGB with a negative height, which
     * GetDIBits reads as top-down, so row 0 of the result is the top row.
     */
    fun pixels(icon: MemorySegment, size: Int): IntArray = Arena.ofConfined().use { arena ->
        val info = arena.allocate(32, 8)
        check((getIconInfo.invokeExact(icon, info) as Int) != 0) { "GetIconInfo failed" }
        val mask = info.get(ValueLayout.ADDRESS, 16)
        val colour = info.get(ValueLayout.ADDRESS, 24)
        try {
            check(colour.address() != 0L) { "the icon has no colour bitmap" }
            val header = arena.allocate(44, 4)
            header.set(ValueLayout.JAVA_INT, 0, 40)          // biSize
            header.set(ValueLayout.JAVA_INT, 4, size)        // biWidth
            header.set(ValueLayout.JAVA_INT, 8, -size)       // biHeight, top-down
            header.set(ValueLayout.JAVA_SHORT, 12, 1)        // biPlanes
            header.set(ValueLayout.JAVA_SHORT, 14, 32)       // biBitCount, the rest BI_RGB and zero
            val bits = arena.allocate(size * size * 4L, 4)
            val dc = getDC.invokeExact(MemorySegment.NULL) as MemorySegment
            try {
                val lines = getDIBits.invokeExact(dc, colour, 0, size, bits, header, DIB_RGB_COLORS) as Int
                check(lines == size) { "GetDIBits copied $lines of $size rows" }
            } finally {
                releaseDC.invokeExact(MemorySegment.NULL, dc) as Int
            }
            // Each pixel is B, G, R, A in memory, which reads as 0xAARRGGBB in little-endian order.
            IntArray(size * size) { i -> bits.get(ValueLayout.JAVA_INT, i * 4L) }
        } finally {
            if (mask.address() != 0L) deleteObject.invokeExact(mask) as Int
            if (colour.address() != 0L) deleteObject.invokeExact(colour) as Int
        }
    }

    private const val DIB_RGB_COLORS = 0
}
