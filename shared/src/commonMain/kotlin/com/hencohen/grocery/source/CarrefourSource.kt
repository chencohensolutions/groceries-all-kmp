package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * prices.carrefour.co.il — reportedly the page embeds a download base `path` and a `files` list
 * in inline script, and it only answers Israeli IPs (fine for a phone in Israel).
 *
 * UNVERIFIED: the portal couldn't be reached while writing this. [parseListing] is deliberately
 * tolerant (it finds any transparency file name on the page), but check it against the real HTML
 * on the first live run and tighten it — see `ListingAndBasketTest`.
 *
 * Inside Carrefour files the StoreId can be stale, so the store always comes from the file name.
 */
class CarrefourSource(private val http: HttpClient) : PriceSource {
    override val chain = Chain.CARREFOUR

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> =
        parseListing(http.get("$BASE/").bodyAsText())

    companion object {
        const val BASE = "https://prices.carrefour.co.il"

        private val pathPattern = Regex("""\bpath\s*[=:]\s*["']([^"']+)["']""")
        private val fileNamePattern = Regex("""(?:PriceFull|PromoFull|Stores|Price|Promo)7290055700007-[\d-]+\.(?:gz|xml|zip)""")

        fun parseListing(html: String): List<PortalFile> {
            val path = pathPattern.find(html)?.groupValues?.get(1)?.decodeHtmlAttribute()
            val base = when {
                path == null -> "$BASE/"
                path.startsWith("http") -> path.trimEnd('/') + "/"
                else -> "$BASE/" + path.trim('/') + "/"
            }
            return fileNamePattern.findAll(html)
                .map { it.value }
                .distinct()
                .mapNotNull { portalFileFromUrl(base + it, Chain.CARREFOUR) }
                .toList()
        }
    }
}
