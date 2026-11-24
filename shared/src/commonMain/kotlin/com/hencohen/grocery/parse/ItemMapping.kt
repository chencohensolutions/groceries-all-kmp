package com.hencohen.grocery.parse

import com.hencohen.grocery.model.Agorot
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.RawItem
import com.hencohen.grocery.model.StoreInfo

private fun Map<String, String>.firstOf(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }

/** Parses "12.90", "5", "3.5" or "4.255" into agorot, rounding half up on the third decimal. */
fun parseAgorot(value: String?): Agorot? {
    val t = value?.trim().orEmpty()
    if (t.isEmpty()) return null
    val negative = t.startsWith("-")
    val body = t.removePrefix("-")
    val whole = body.substringBefore('.').ifEmpty { "0" }
    val frac = if ('.' in body) body.substringAfter('.') else ""
    if (!whole.all { it.isDigit() } || !frac.all { it.isDigit() }) return null
    val wholeValue = whole.toLongOrNull() ?: return null
    val cents = (frac + "00").take(2).toLong()
    val roundUp = (frac.getOrNull(2)?.digitToInt() ?: 0) >= 5
    val result = wholeValue * 100 + cents + if (roundUp) 1 else 0
    return if (negative) -result else result
}

/**
 * The key used to match the same product across chains.
 * Real barcodes are compared without leading zeros. Internal chain codes (ItemType 0, e.g. loose
 * produce) are prefixed with the chain so they never match another chain's unrelated code.
 */
fun productKey(chain: Chain, itemCode: String, itemType: String?): String {
    val code = itemCode.trim().trimStart('0').ifEmpty { "0" }
    return if (itemType?.trim() == "0") "${chain.key}:$code" else code
}

fun Map<String, String>.toRawItem(chain: Chain): RawItem? {
    val code = firstOf("itemcode") ?: return null
    val price = parseAgorot(firstOf("itemprice")) ?: return null
    return RawItem(
        barcode = productKey(chain, code, firstOf("itemtype")),
        name = firstOf("itemname", "itemnm") ?: code,
        manufacturer = firstOf("manufacturername", "manufacturename", "manufacturer"),
        price = price,
        unitQty = firstOf("unitqty"),
        quantity = firstOf("quantity"),
        unitOfMeasure = firstOf("unitofmeasure", "unitmeasure"),
        unitPrice = parseAgorot(firstOf("unitofmeasureprice")),
        isWeighted = firstOf("bisweighted").let { it == "1" || it.equals("true", ignoreCase = true) },
    )
}

fun Map<String, String>.toStoreInfo(chain: Chain): StoreInfo? {
    val id = firstOf("storeid", "branchid", "storenumber") ?: return null
    return StoreInfo(
        chain = chain,
        storeId = normalizeStoreId(id),
        name = firstOf("storename", "branchname") ?: id,
        city = firstOf("city", "cityname"),
        address = firstOf("address", "storeaddress"),
    )
}

fun normalizeStoreId(id: String): String = id.trim().trimStart('0').ifEmpty { "0" }
