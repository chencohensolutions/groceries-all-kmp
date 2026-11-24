package com.hencohen.grocery.parse

/**
 * Search normalisation for Hebrew product names, so "עגבניות", "עגבנייה" and "עגבניה" all match.
 *
 * - drops niqqud, geresh/gershayim and punctuation; lower-cases Latin
 * - final letters → regular (ם→מ, ן→נ, ץ→צ, ף→פ, ך→כ)
 * - collapses full spelling (יי→י, וו→ו)
 * - light stemming per word: plural ות/ים, then a trailing ה
 *
 * Stored per product as `search_name` (" w1 w2 … ", padded with spaces so a query word can be
 * matched at a word start with LIKE '% word%').
 */
object HebrewSearch {
    private val finals = mapOf('ם' to 'מ', 'ן' to 'נ', 'ץ' to 'צ', 'ף' to 'פ', 'ך' to 'כ')

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            when {
                c in '֑'..'ׇ' -> Unit // niqqud and cantillation
                c == '׳' || c == '״' || c == '\'' || c == '"' || c == '`' -> Unit // geresh, gershayim, quotes
                c.isLetterOrDigit() -> sb.append(finals[c] ?: c)
                c == '.' || c == '%' -> sb.append(c) // keep "1.5" and "3%"
                else -> sb.append(' ')
            }
        }
        return sb.toString()
            .replace("יי", "י")
            .replace("וו", "ו")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun stem(word: String): String {
        if (word.none { it in 'א'..'ת' }) return word
        var w = word
        if (w.length >= 5 && (w.endsWith("ות") || w.endsWith("ימ"))) w = w.dropLast(2)
        if (w.length >= 4 && w.endsWith("ה")) w = w.dropLast(1)
        return w
    }

    /** Stemmed words of [text]. */
    fun terms(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }.map(::stem)

    /** The value stored in `product.search_name`. */
    fun searchName(name: String): String = " " + terms(name).joinToString(" ") + " "

    /** True if every query term matches the start of some word in [searchName]. */
    fun matchesAll(searchName: String, queryTerms: List<String>): Boolean =
        queryTerms.all { " $it" in searchName }

    /**
     * Key for comparing one product across chains. Real barcodes are the key. Items with only an
     * internal chain code (produce, deli: barcode "chain:code") are matched across chains by
     * normalised name instead, e.g. "עגבניות" at one chain and "עגבנייה" at another.
     */
    fun matchKey(barcode: String, name: String): String =
        if (':' in barcode) "n:" + terms(name).joinToString(" ") else barcode
}
