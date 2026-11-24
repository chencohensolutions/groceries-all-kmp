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
 * One chain's price-transparency portal. Implementations only know how to *list* files (and, for
 * portals with an indirection step, resolve the real download URL). Downloading, decompressing
 * and parsing are shared (see [FileDownloader]).
 *
 * Download URLs can be short-lived (Shufersal's are signed Azure links) or session-bound
 * (Cerberus), so always list right before downloading; never cache URLs.
 */
interface PriceSource {
    val chain: Chain

    /** Files of [kind] currently on the portal, optionally narrowed to one store. */
    suspend fun listFiles(kind: FileKind, storeId: String? = null): List<PortalFile>

    /** The URL to actually fetch. Most portals link directly; Bina returns the real path from an API call. */
    suspend fun downloadUrl(file: PortalFile): String = file.url
}

/** Newest file of [kind] for [storeId] (or any store, for Stores files). */
suspend fun PriceSource.latest(kind: FileKind, storeId: String? = null): PortalFile? {
    val wanted = storeId?.let(::normalizeStoreId)
    return listFiles(kind, storeId)
        .filter { it.name.startsWith(kind.prefix, ignoreCase = true) }
        .filter { wanted == null || it.storeId == wanted }
        .maxByOrNull { it.timestamp }
}

/** Builds a [PortalFile] from a download URL whose last path segment is the file name. */
internal fun portalFileFromUrl(url: String, chain: Chain): PortalFile? {
    val fileName = url.substringBefore('?').substringAfterLast('/')
    return portalFile(fileName, url, chain)
}

/** Builds a [PortalFile] from an explicit file name, or null if it isn't one of [chain]'s transparency files. */
internal fun portalFile(fileName: String, url: String, chain: Chain): PortalFile? {
    val parts = TransparencyFileName.parse(fileName) ?: return null
    if (parts.chainId !in chain.allChainIds) return null
    return PortalFile(
        name = fileName.substringAfterLast('/').substringBefore('.'),
        url = url,
        storeId = parts.storeId,
        timestamp = parts.timestamp,
    )
}

internal fun String.decodeHtmlAttribute(): String =
    replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"")

/** Resolves [href] (absolute, root-relative or relative) against [base], which should end with '/'. */
internal fun resolveUrl(base: String, href: String): String = when {
    href.startsWith("http://") || href.startsWith("https://") -> href
    href.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + href
    else -> base.substringBeforeLast('/') + "/" + href.removePrefix("./")
}
