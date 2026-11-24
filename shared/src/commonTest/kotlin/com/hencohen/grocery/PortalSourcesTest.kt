package com.hencohen.grocery

import com.hencohen.grocery.data.StoreDirectory
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.RawItem
import com.hencohen.grocery.model.StoreInfo
import com.hencohen.grocery.parse.XmlPullParser
import com.hencohen.grocery.parse.decodedChars
import com.hencohen.grocery.parse.readRecords
import com.hencohen.grocery.parse.toRawItem
import com.hencohen.grocery.parse.toStoreInfo
import com.hencohen.grocery.source.BinaSource
import com.hencohen.grocery.source.CerberusSource
import com.hencohen.grocery.source.HaziHinamSource
import com.hencohen.grocery.source.LaibcatalogSource
import com.hencohen.grocery.source.WoltSource
import com.hencohen.grocery.source.civilFromDays
import com.hencohen.grocery.source.israelDate
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Listing shapes follow the maintained il-supermarket-scraper; verify on a live run from Israel. */
class PortalSourcesTest {

    @Test
    fun cerberusCsrfAndListing() {
        assertEquals("abc123", CerberusSource.csrfToken("""<meta name="csrftoken" content="abc123"/>"""))
        assertEquals("xyz", CerberusSource.csrfToken("""<input type="hidden" name="csrftoken" value="xyz">"""))
        assertNull(CerberusSource.csrfToken("<html></html>"))

        val json = """{"sEcho":1,"aaData":[
            {"fname":"PriceFull7290058140886-039-202611240300.gz","ftype":"gz","size":5000000},
            {"fname":"Stores7290058140886-202611240505.xml","ftype":"xml"},
            {"fname":"PriceFull7290000000000-001-202611240300.gz"}
        ]}"""
        val files = CerberusSource.parseListing(json, Chain.RAMI_LEVY)
        assertEquals(2, files.size, "other chains' files are ignored")
        assertEquals("https://url.publishedprices.co.il/file/d/PriceFull7290058140886-039-202611240300.gz", files[0].url)
        assertEquals("39", files[0].storeId)
        assertNull(files[1].storeId, "chain-wide Stores file has no store segment")
    }

    @Test
    fun laibcatalogListingAcceptsBothChainIds() {
        val json = """[
            {"fileName":"PriceFull7290696200003-090-202611240300.xml.gz","fileType":"pricefull","fileSize":"5.2 MB"},
            {"fileName":"PriceFull7290058103393-012-202611240300.xml.gz","fileType":"pricefull"}
        ]"""
        val files = LaibcatalogSource.parseListing(json, Chain.VICTORY)
        assertEquals(2, files.size)
        assertEquals(
            "https://laibcatalog.co.il/webapi/7290696200003/PriceFull7290696200003-090-202611240300.xml.gz",
            files[0].url,
        )
        assertEquals("90", files[0].storeId)
    }

    @Test
    fun binaListingPointsAtDownloadResolver() {
        val json = """[{"FileNm":"PriceFull7290058108879-334-202611240300.gz","DateFile":"03:00 24/11/2026"}]"""
        val file = BinaSource.parseListing(json, Chain.KING_STORE, "http://kingstore.binaprojects.com").single()
        assertEquals("http://kingstore.binaprojects.com/Download.aspx?FileNm=PriceFull7290058108879-334-202611240300.gz", file.url)
        assertEquals("334", file.storeId)
    }

    @Test
    fun haziHinamTableRows() {
        val html = """
            <table><thead><tr><th>תאריך</th></tr></thead><tbody>
            <tr><td>24-11-2026 03:00</td><td>מחירים</td><td>PriceFull7290700100008-000-207-20261124-030000</td><td>חצי חינם אונליין</td><td>4,210</td>
                <td><a href="https://shop.hazi-hinam.co.il/Prices/Download?f=PriceFull7290700100008-000-207-20261124-030000.xml.gz&amp;x=1">הורדה</a></td></tr>
            </tbody></table>
        """.trimIndent()
        val file = HaziHinamSource.parseListing(html).single()
        assertEquals("207", file.storeId)
        assertEquals("https://shop.hazi-hinam.co.il/Prices/Download?f=PriceFull7290700100008-000-207-20261124-030000.xml.gz&x=1", file.url)
    }

    @Test
    fun woltDailyIndex() {
        val html = """<ul>
            <li><a href="2026-11-24/PriceFull7290058249350-012-202611240400.gz">PriceFull7290058249350-012-202611240400.gz</a></li>
            <li><a href="https://cdn.example/x/abc">Stores7290058249350-000-202611240100.xml</a></li>
        </ul>"""
        val files = WoltSource.parseListing(html)
        assertEquals(2, files.size)
        assertEquals("https://wm-gateway.wolt.com/isr-prices/public/v1/2026-11-24/PriceFull7290058249350-012-202611240400.gz", files[0].url)
        assertEquals("Stores7290058249350-000-202611240100", files[1].name, "name from link text when the URL hides it")
    }

    @Test
    fun parsesNibitProductRecordsAndBranches() {
        val prices = """<Prices><Products>
            <Product><ItemCode>7290000000017</ItemCode><ItemName>שמן קנולה</ItemName><ManufactureName>שמן</ManufactureName>
                     <UnitMeasure>ליטר</UnitMeasure><ItemPrice>9.90</ItemPrice></Product>
        </Products></Prices>"""
        val items = mutableListOf<RawItem>()
        XmlPullParser(Buffer().writeUtf8(prices).decodedChars())
            .readRecords("Item", "Product") { r -> r.toRawItem(Chain.VICTORY)?.let(items::add) }
        val oil = items.single()
        assertEquals(990L, oil.price)
        assertEquals("שמן", oil.manufacturer)
        assertEquals("ליטר", oil.unitOfMeasure)

        val stores = """<Branches><Branch><BranchId>90</BranchId><BranchName>ויקטורי אונליין</BranchName></Branch>
            <Branch><BranchId>1</BranchId><BranchName>ויקטורי באר שבע</BranchName></Branch></Branches>"""
        val parsed = mutableListOf<StoreInfo>()
        XmlPullParser(Buffer().writeUtf8(stores).decodedChars())
            .readRecords("Store", "Branch") { r -> r.toStoreInfo(Chain.VICTORY)?.let(parsed::add) }
        assertEquals("90", StoreDirectory.pickOnlineStore(parsed)?.storeId)
    }

    @Test
    fun picksWoltStoreByKeyword() {
        val stores = listOf(
            StoreInfo(Chain.KING_STORE, "5", "קינג סטור ירושלים", null),
            StoreInfo(Chain.KING_STORE, "334", "קינג סטור Wolt ONLINE באר שבע", null),
        )
        assertEquals("334", StoreDirectory.pickOnlineStore(stores)?.storeId)
    }

    @Test
    fun civilDates() {
        assertEquals("1970-01-01", civilFromDays(0))
        assertEquals("2026-11-24", civilFromDays(20_781))
        assertEquals("2024-02-29", civilFromDays(19_782))
        // 2026-11-24 23:30 UTC is already the 25th in Israel.
        assertEquals("2026-11-25", israelDate(0, nowMillis = 20_781L * 86_400_000L + 23L * 3_600_000L + 1_800_000L))
        assertEquals("2026-11-24", israelDate(1, nowMillis = 20_781L * 86_400_000L + 23L * 3_600_000L + 1_800_000L))
    }
}
