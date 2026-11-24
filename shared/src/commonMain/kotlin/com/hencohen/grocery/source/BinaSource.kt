package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * Bina portals (<prefix>.binaprojects.com) — King Store, Zol Vebegadol, Maayan 2000 and others.
 *
 *     GET /MainIO_Hok.aspx?_=<chainId>&wReshet=הכל&WFileType=<n>&WDate=&WStore=<store>
 *         → [{"FileNm": "PriceFull...gz", "DateFile": "...", ...}]
 *     GET /Download.aspx?FileNm=<name>  → [{"SPath": "<real url>"}]
 *
 * Plain HTTP only, so the Android app allows cleartext for binaprojects.com
 * (res/xml/network_security_config.xml).
 */
class BinaSource(
    private val http: HttpClient,
    override val chain: Chain,
    prefix: String,
) : PriceSource {
    private val baseUrl = "http://$prefix.binaprojects.com"

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> {
        val json = http.get("$baseUrl/MainIO_Hok.aspx") {
            parameter("_", chain.chainId)
            parameter("wReshet", "הכל")
            parameter("WFileType", fileTypeId(kind))
            parameter("WDate", "")
            parameter("WStore", storeId.orEmpty())
        }.bodyAsText()
        return parseListing(json, chain, baseUrl)
    }

    override suspend fun downloadUrl(file: PortalFile): String {
        val json = http.get(file.url).bodyAsText()
        return spathPattern.find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: error("${chain.displayName}: no download path for ${file.name}")
    }

    companion object {
        private val fileNamePattern = Regex(""""FileNm"\s*:\s*"([^"]+)"""")
        private val spathPattern = Regex(""""SPath"\s*:\s*"([^"]+)"""")

        fun fileTypeId(kind: FileKind): Int = when (kind) {
            FileKind.STORES -> 1
            FileKind.PRICE_FULL -> 4
            FileKind.PROMO_FULL -> 5
        }

        fun parseListing(json: String, chain: Chain, baseUrl: String): List<PortalFile> =
            fileNamePattern.findAll(json)
                .map { it.groupValues[1] }
                .distinct()
                .mapNotNull { name -> portalFile(name, "$baseUrl/Download.aspx?FileNm=$name", chain) }
                .toList()
    }
}
