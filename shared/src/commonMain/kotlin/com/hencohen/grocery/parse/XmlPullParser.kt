package com.hencohen.grocery.parse

/**
 * Minimal streaming XML pull parser, written in common Kotlin so it runs unchanged on Android and iOS.
 *
 * It handles what the price-transparency files actually contain: elements, text, the five
 * predefined entities plus numeric references, CDATA, comments, processing instructions and a
 * DOCTYPE. Attributes are skipped — the files carry their data in child elements.
 * Namespace prefixes are stripped from element names.
 */
class XmlPullParser(private val input: CharInput) {

    enum class Event { START, END, TEXT, EOF }

    /** Element name for START / END events. */
    var name: String = ""
        private set

    /** Content for TEXT events (entities already decoded, not trimmed). */
    var text: String = ""
        private set

    private var pushedBack = NONE
    private var pendingEnd: String? = null

    fun next(): Event {
        pendingEnd?.let {
            name = it
            pendingEnd = null
            return Event.END
        }
        while (true) {
            val c = readChar()
            if (c == -1) return Event.EOF
            if (c != LT) {
                unread(c)
                text = readText()
                if (text.isBlank()) continue
                return Event.TEXT
            }
            when (val c2 = readChar()) {
                -1 -> return Event.EOF
                '?'.code -> skipUntil("?>")
                '!'.code -> {
                    when (readChar()) {
                        '-'.code -> { readChar(); skipUntil("-->") }
                        '['.code -> {
                            skipUntil("[") // the rest of "[CDATA["
                            text = readUntil("]]>")
                            if (text.isNotEmpty()) return Event.TEXT
                        }
                        else -> skipUntil(">") // DOCTYPE etc.
                    }
                }
                '/'.code -> {
                    name = readName()
                    skipUntil(">")
                    return Event.END
                }
                else -> {
                    unread(c2)
                    name = readName()
                    if (skipAttributes()) pendingEnd = name
                    return Event.START
                }
            }
        }
    }

    private fun readChar(): Int {
        if (pushedBack != NONE) {
            val c = pushedBack
            pushedBack = NONE
            return c
        }
        return input.read()
    }

    private fun unread(c: Int) {
        pushedBack = c
    }

    private fun readText(): String {
        val sb = StringBuilder()
        while (true) {
            val c = readChar()
            if (c == -1) break
            if (c == LT) {
                unread(c)
                break
            }
            if (c == AMP) sb.append(readEntity()) else sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readEntity(): String {
        val raw = StringBuilder()
        while (raw.length < 10) {
            val c = readChar()
            if (c == -1) return "&$raw"
            if (c == ';'.code) {
                val ref = raw.toString()
                return when {
                    ref == "amp" -> "&"
                    ref == "lt" -> "<"
                    ref == "gt" -> ">"
                    ref == "quot" -> "\""
                    ref == "apos" -> "'"
                    ref.startsWith("#x") || ref.startsWith("#X") ->
                        ref.substring(2).toIntOrNull(16)?.let(::codePointToString) ?: "&$ref;"
                    ref.startsWith("#") ->
                        ref.substring(1).toIntOrNull()?.let(::codePointToString) ?: "&$ref;"
                    else -> "&$ref;"
                }
            }
            if (c == LT) {
                unread(c)
                return "&$raw"
            }
            raw.append(c.toChar())
        }
        return "&$raw"
    }

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = readChar()
            if (c == -1) break
            if (c == GT || c == '/'.code || c.isXmlSpace()) {
                unread(c)
                break
            }
            sb.append(c.toChar())
        }
        val full = sb.toString()
        return full.substringAfter(':')
    }

    /** Skips to the end of a start tag. Returns true if the tag was self-closing (`<a/>`). */
    private fun skipAttributes(): Boolean {
        while (true) {
            when (val c = readChar()) {
                -1, GT -> return false
                '/'.code -> {
                    val c2 = readChar()
                    if (c2 == GT) return true
                    unread(c2)
                }
                '"'.code, '\''.code -> {
                    while (true) {
                        val q = readChar()
                        if (q == -1 || q == c) break
                    }
                }
            }
        }
    }

    private fun skipUntil(terminator: String) {
        readUntil(terminator)
    }

    private fun readUntil(terminator: String): String {
        val sb = StringBuilder()
        while (true) {
            val c = readChar()
            if (c == -1) return sb.toString()
            sb.append(c.toChar())
            if (sb.length >= terminator.length && sb.endsWith(terminator)) {
                sb.setLength(sb.length - terminator.length)
                return sb.toString()
            }
        }
    }

    private fun Int.isXmlSpace() = this == ' '.code || this == '\n'.code || this == '\r'.code || this == '\t'.code

    private companion object {
        const val NONE = -2
        const val LT = 60 // '<'
        const val GT = 62 // '>'
        const val AMP = 38 // '&'

        fun codePointToString(cp: Int): String =
            if (cp < 0x10000) {
                cp.toChar().toString()
            } else {
                val v = cp - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
    }
}
