package com.hencohen.grocery.parse

/**
 * Walks the document and calls [onRecord] for every element named one of [recordTags] (case-insensitive),
 * passing its direct leaf children as a map of lower-cased tag name → trimmed text.
 *
 * Works for `<Item>` in PriceFull files and `<Store>` / `<STORE>` in Stores files, whose
 * tag casing differs between chains.
 */
fun XmlPullParser.readRecords(vararg recordTags: String, onRecord: (Map<String, String>) -> Unit) {
    fun isRecord(tag: String) = recordTags.any { it.equals(tag, ignoreCase = true) }

    var fields: HashMap<String, String>? = null
    var currentField: String? = null
    val text = StringBuilder()

    while (true) {
        when (next()) {
            XmlPullParser.Event.EOF -> return
            XmlPullParser.Event.START -> {
                if (isRecord(name)) {
                    fields = HashMap()
                    currentField = null
                } else if (fields != null) {
                    currentField = name.lowercase()
                    text.clear()
                }
            }
            XmlPullParser.Event.TEXT -> {
                if (fields != null && currentField != null) text.append(this.text)
            }
            XmlPullParser.Event.END -> {
                val record = fields ?: continue
                if (isRecord(name)) {
                    onRecord(record)
                    fields = null
                    currentField = null
                } else if (currentField != null && name.lowercase() == currentField) {
                    record[currentField] = text.toString().trim()
                    currentField = null
                }
            }
        }
    }
}
