package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Wolt Market — one static page per day listing that day's files:
 *
 *     https://wm-gateway.wolt.com/isr-prices/public/v1/YYYY-MM-DD.html
 *     <li><a href="...">PriceFull7290058249350-...gz</a></li>
 *
 * Every Wolt Market store is a delivery store, so the chain is marked allStoresOnline.
 */
class WoltSource(private val http: HttpClient) : PriceSource {
    override val chain = Chain.WOLT_MARKET

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> {
        val found = mutableListOf<PortalFile>()
        for (daysBack in 0..3) {
            val response: HttpResponse = runCatching { http.get("$BASE${israelDate(daysBack)}.html") }.getOrNull()
                ?: continue
            if (!response.status.isSuccess()) continue
            found += parseListing(response.bodyAsText())
            if (found.any { it.name.startsWith(kind.prefix, true) }) break
        }
        return found
    }

    companion object {
        const val BASE = "https://wm-gateway.wolt.com/isr-prices/public/v1/"

        private val linkPattern =
            Regex("""<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun parseListing(html: String): List<PortalFile> =
            linkPattern.findAll(html).mapNotNull { match ->
                val url = resolveUrl(BASE, match.groupValues[1].decodeHtmlAttribute())
                val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                portalFileFromUrl(url, Chain.WOLT_MARKET) ?: portalFile(text, url, Chain.WOLT_MARKET)
            }.distinctBy { it.name }.toList()
    }
}
