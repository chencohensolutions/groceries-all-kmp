package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * "PublishPrice" portals (prices.<site>.co.il) — Carrefour, and formerly Mega / Quik.
 * The page hard-codes today's folder and file list in an inline script:
 *
 *     const path = '20261124';
 *     const files = [{"name":"PriceFull7290055700007-...gz", "modified":"...", ...}];
 *
 * Files are served from `<base>/<path>/<name>`. Only answers Israeli IPs.
 */
class PublishPriceSource(
    private val http: HttpClient,
    override val chain: Chain,
    site: String,
) : PriceSource {
    private val baseUrl = "https://prices.$site.co.il/"

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> =
        parseListing(http.get(baseUrl).bodyAsText(), chain, baseUrl)

    companion object {
        private val pathPattern = Regex("""\bpath\s*[=:]\s*["']([^"']*)["']""")
        private val fileNamePattern =
            Regex("""(?:PriceFull|PromoFull|Stores|Price|Promo)\d{13}-[\d-]+\.(?:gz|xml|zip)""")

        fun parseListing(html: String, chain: Chain, baseUrl: String): List<PortalFile> {
            val root = baseUrl.trimEnd('/') + "/"
            val path = pathPattern.find(html)?.groupValues?.get(1)?.decodeHtmlAttribute()
                ?.removePrefix("./")?.trim('/').orEmpty()
            val folder = when {
                path.startsWith("http") -> "$path/"
                path.isEmpty() -> root
                else -> "$root$path/"
            }
            return fileNamePattern.findAll(html)
                .map { it.value }
                .distinct()
                .mapNotNull { portalFileFromUrl(folder + it, chain) }
                .toList()
        }
    }
}
