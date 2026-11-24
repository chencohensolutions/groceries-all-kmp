package com.hencohen.grocery.parse

/**
 * Parses names like:
 *   PriceFull7290027600007-001-413-20261124-030000   (Shufersal: subchain, store, date, time)
 *   PriceFull7290055700007-2960-202611240300         (store, datetime)
 *   Stores7290027600007-000-202611240201
 *
 * The first dash-separated segment with 8+ digits starts the timestamp; the segment right
 * before it is the store number.
 */
object TransparencyFileName {
    private val pattern = Regex("""^([A-Za-z]+?)(\d{13})-([\d-]+)$""")

    data class Parts(val kind: String, val chainId: String, val storeId: String?, val timestamp: String)

    fun parse(fileName: String): Parts? {
        val base = fileName.substringAfterLast('/').substringBefore('?').substringBefore('.')
        val match = pattern.matchEntire(base) ?: return null
        val (kind, chainId, rest) = match.destructured
        val segments = rest.split('-').filter { it.isNotEmpty() }
        val tsIndex = segments.indexOfFirst { it.length >= 8 }
        if (tsIndex < 0) return null
        val store = segments.subList(0, tsIndex).lastOrNull()?.let(::normalizeStoreId)
        val timestamp = segments.subList(tsIndex, segments.size).joinToString("")
        return Parts(kind, chainId, store, timestamp)
    }
}
