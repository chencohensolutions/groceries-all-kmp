package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * laibcatalog.co.il JSON API — Victory, Mahsanei Hashuk, H. Cohen.
 *
 *     GET /webapi/api/getfiles?edi=<chainId>   → [{"fileName": "...", "fileType": "pricefull", ...}]
 *     GET /webapi/<primaryChainId>/<fileName>  → the file
 *
 * getfiles returns the whole chain's catalog (branchNumber doesn't narrow it), so store filtering
 * happens on the parsed file names.
 */
class LaibcatalogSource(
    private val http: HttpClient,
    override val chain: Chain,
) : PriceSource {

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> =
        chain.allChainIds.flatMap { edi ->
            val json = runCatching {
                http.get("$BASE/webapi/api/getfiles") { parameter("edi", edi) }.bodyAsText()
            }.getOrNull() ?: return@flatMap emptyList()
            parseListing(json, chain)
        }

    companion object {
        const val BASE = "https://laibcatalog.co.il"

        private val fileNamePattern = Regex(""""fileName"\s*:\s*"([^"]+)"""")

        fun parseListing(json: String, chain: Chain): List<PortalFile> =
            fileNamePattern.findAll(json)
                .map { it.groupValues[1] }
                .distinct()
                .mapNotNull { name -> portalFile(name, "$BASE/webapi/${chain.chainId}/$name", chain) }
                .toList()
    }
}
