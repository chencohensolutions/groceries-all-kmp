package com.hencohen.grocery

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.RawItem
import com.hencohen.grocery.parse.readRecords
import com.hencohen.grocery.parse.toRawItem
import com.hencohen.grocery.source.FileDownloader
import io.ktor.client.HttpClient
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rami Levy (Cerberus) serves a ZIP even when the file name says .gz. */
class ZipFileTest {

    @Test
    fun parsesPriceFileInsideZip() {
        val fs = FileSystem.SYSTEM
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "grocery-zip-test.download"
        fs.write(path) { write(ZIP_BASE64.decodeBase64()!!) }
        try {
            val downloader = FileDownloader(HttpClient())
            val items = mutableListOf<RawItem>()
            downloader.parse(path) { parser ->
                parser.readRecords("Item") { r -> r.toRawItem(Chain.RAMI_LEVY)?.let(items::add) }
            }
            assertEquals(690L, items.single().price)
            assertEquals("חלב", items.single().name)
        } finally {
            fs.delete(path, mustExist = false)
        }
    }

    private companion object {
        // ZIP (deflate) holding PriceFull7290058140886-039-202611240517.xml with one <Item>.
        const val ZIP_BASE64 =
            "UEsDBBQAAAAIAI6leF04AsCrdQAAAKcAAAArAAAAUHJpY2VGdWxsNzI5MDA1ODE0MDg4Ni0wMzktMjAyNjExMjQwNTE3LnhtbLOxr8jNUShLLSrOzM+zVTLUM1BSSM1Lzk/JzEu3VSotSdO1ULK3swnKzy+xs/EsSc0thlAQ0jk/JdXO3MjSAATMzIwNLWz04eJgFX6Jual216dfn3N9IkQKLACWCijKTE61M9OzNIDIQPgQNpQqtrPRB1sNAFBLAQIUAxQAAAAIAI6leF04AsCrdQAAAKcAAAArAAAAAAAAAAAAAACAAQAAAABQcmljZUZ1bGw3MjkwMDU4MTQwODg2LTAzOS0yMDI2MTEyNDA1MTcueG1sUEsFBgAAAAABAAEAWQAAAL4AAAAAAA=="
    }
}
