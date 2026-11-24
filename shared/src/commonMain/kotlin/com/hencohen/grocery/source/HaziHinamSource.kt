package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import com.hencohen.grocery.parse.normalizeStoreId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * shop.hazi-hinam.co.il/Prices — a paged HTML table per day.
 *
 *     /Prices?d=YYYY-MM-DD&t=<1 prices | 3 stores>&f=null&p=<page>
 *
 * Row cells: [1] date, [3] file name (no extension), [6] download link. The portal can't filter by
 * store, so pages are read until the wanted store shows up (or pages run out).
 */
class HaziHinamSource(private val http: HttpClient) : PriceSource {
    override val chain = Chain.HAZI_HINAM

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> {
        val type = if (kind == FileKind.STORES) "3" else if (kind == FileKind.PROMO_FULL) "2" else "1"
        val found = mutableListOf<PortalFile>()
        for (daysBack in 0..1) {
            val date = israelDate(daysBack)
            for (page in 1..MAX_PAGES) {
                val html = http.get(BASE) {
                    parameter("d", date)
                    parameter("t", type)
                    parameter("f", "null")
                    parameter("p", page)
                }.bodyAsText()
                val rows = parseListing(html)
                if (rows.isEmpty() || rows.all { row -> found.any { it.name == row.name } }) break
                found += rows
                val hit = rows.any { it.name.startsWith(kind.prefix, true) && (storeId == null || it.storeId == normalizeStoreId(storeId)) }
                if (hit) return found
            }
        }
        return found
    }

    companion object {
        const val BASE = "https://shop.hazi-hinam.co.il/Prices"
        private const val MAX_PAGES = 20

        private val rowPattern = Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val cellPattern = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val hrefPattern = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val tagPattern = Regex("<[^>]+>")

        fun parseListing(html: String): List<PortalFile> =
            rowPattern.findAll(html).mapNotNull { row ->
                val cells = cellPattern.findAll(row.groupValues[1]).map { it.groupValues[1] }.toList()
                if (cells.size < 6) return@mapNotNull null
                val name = cells[2].replace(tagPattern, "").trim()
                val href = hrefPattern.find(cells[5])?.groupValues?.get(1)?.decodeHtmlAttribute()
                    ?: return@mapNotNull null
                portalFile(name, resolveUrl("https://shop.hazi-hinam.co.il/", href), Chain.HAZI_HINAM)
            }.toList()
    }
}
