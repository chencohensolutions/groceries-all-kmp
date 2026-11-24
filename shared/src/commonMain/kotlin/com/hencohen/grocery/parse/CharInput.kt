package com.hencohen.grocery.parse

import okio.BufferedSource

/**
 * Pull-based character stream. [read] returns the next UTF-16 code unit, or -1 at end of input.
 * Keeps memory flat: PriceFull files decompress to tens of MB, so nothing is loaded whole.
 */
fun interface CharInput {
    fun read(): Int
}

/**
 * Detects the encoding (BOM, or UTF-16 without BOM via the "<\0" pattern) and returns a decoder.
 * Chains publish UTF-8 and UTF-16LE files; UTF-16BE is handled for completeness.
 */
fun BufferedSource.decodedChars(): CharInput {
    val head = peek()
    val b = IntArray(3) { if (head.request(1)) head.readByte().toInt() and 0xFF else -1 }
    return when {
        b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF -> { skip(3); Utf8Input(this) }
        b[0] == 0xFF && b[1] == 0xFE -> { skip(2); Utf16Input(this, littleEndian = true) }
        b[0] == 0xFE && b[1] == 0xFF -> { skip(2); Utf16Input(this, littleEndian = false) }
        b[0] != 0 && b[1] == 0 -> Utf16Input(this, littleEndian = true)
        b[0] == 0 && b[1] != 0 -> Utf16Input(this, littleEndian = false)
        else -> Utf8Input(this)
    }
}

private class Utf8Input(private val source: BufferedSource) : CharInput {
    private var pendingLowSurrogate = -1

    override fun read(): Int {
        if (pendingLowSurrogate >= 0) {
            val low = pendingLowSurrogate
            pendingLowSurrogate = -1
            return low
        }
        if (source.exhausted()) return -1
        val codePoint = source.readUtf8CodePoint()
        if (codePoint < 0x10000) return codePoint
        val v = codePoint - 0x10000
        pendingLowSurrogate = 0xDC00 + (v and 0x3FF)
        return 0xD800 + (v shr 10)
    }
}

private class Utf16Input(
    private val source: BufferedSource,
    private val littleEndian: Boolean,
) : CharInput {
    override fun read(): Int {
        if (!source.request(2)) return -1
        val unit = if (littleEndian) source.readShortLe() else source.readShort()
        return unit.toInt() and 0xFFFF
    }
}
