package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * prices.shufersal.co.il — an HTML table filtered by category and store. Each row links to a
 * signed, expiring Azure blob URL, so the listing must be fetched fresh before each download.
 *
 * Online store: 413 ("שופרסל ONLINE").
 */
class ShufersalSource(private val http: HttpClient) : PriceSource {
    override val chain = Chain.SHUFERSAL

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> {
        val url = "$BASE/FileObject/UpdateCategory?catID=${catId(kind)}&storeId=${storeId ?: "0"}&sort=Time&sortdir=DESC"
        return parseListing(http.get(url).bodyAsText())
    }

    companion object {
        const val BASE = "https://prices.shufersal.co.il"
        const val ONLINE_STORE_ID = "413"

        private val hrefPattern = Regex("""href\s*=\s*"(https?://[^"]+)"""", RegexOption.IGNORE_CASE)

        fun catId(kind: FileKind): Int = when (kind) {
            FileKind.PRICE_FULL -> 2
            FileKind.PROMO_FULL -> 4
            FileKind.STORES -> 5
        }

        /** Extracts transparency-file links from the portal's HTML. Pure, so it's unit-tested. */
        fun parseListing(html: String): List<PortalFile> =
            hrefPattern.findAll(html)
                .map { it.groupValues[1].decodeHtmlAttribute() }
                .mapNotNull { portalFileFromUrl(it, Chain.SHUFERSAL) }
                .distinctBy { it.name }
                .toList()
    }
}
