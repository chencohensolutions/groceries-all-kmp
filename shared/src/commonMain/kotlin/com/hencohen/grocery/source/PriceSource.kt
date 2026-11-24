package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import com.hencohen.grocery.parse.TransparencyFileName
import com.hencohen.grocery.parse.normalizeStoreId

enum class FileKind(val prefix: String) {
    PRICE_FULL("PriceFull"),
    PROMO_FULL("PromoFull"),
    STORES("Stores"),
}

/**
 * One chain's price-transparency portal. Implementations only know how to *list* files —
 * downloading, decompressing and parsing are shared (see [FileDownloader]).
 *
 * Download URLs can be short-lived (Shufersal's are signed Azure links), so always list right
 * before downloading; never cache URLs.
 */
interface PriceSource {
    val chain: Chain

    /** Files of [kind] currently on the portal, optionally narrowed to one store. */
    suspend fun listFiles(kind: FileKind, storeId: String? = null): List<PortalFile>
}

/** Newest file of [kind] for [storeId] (or any store, for Stores files). */
suspend fun PriceSource.latest(kind: FileKind, storeId: String? = null): PortalFile? {
    val wanted = storeId?.let(::normalizeStoreId)
    return listFiles(kind, storeId)
        .filter { it.name.startsWith(kind.prefix, ignoreCase = true) }
        .filter { wanted == null || it.storeId == wanted }
        .maxByOrNull { it.timestamp }
}

/** Builds a [PortalFile] from a download URL, or null if the name isn't a transparency file. */
internal fun portalFileFromUrl(url: String, chain: Chain): PortalFile? {
    val fileName = url.substringBefore('?').substringAfterLast('/')
    val parts = TransparencyFileName.parse(fileName) ?: return null
    if (parts.chainId != chain.chainId) return null
    return PortalFile(
        name = fileName.substringBefore('.'),
        url = url,
        storeId = parts.storeId,
        timestamp = parts.timestamp,
    )
}

internal fun String.decodeHtmlAttribute(): String =
    replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"")
