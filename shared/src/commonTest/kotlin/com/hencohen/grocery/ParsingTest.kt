package com.hencohen.grocery

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.RawItem
import com.hencohen.grocery.model.StoreInfo
import com.hencohen.grocery.parse.TransparencyFileName
import com.hencohen.grocery.parse.XmlPullParser
import com.hencohen.grocery.parse.decodedChars
import com.hencohen.grocery.parse.parseAgorot
import com.hencohen.grocery.parse.readRecords
import com.hencohen.grocery.parse.toRawItem
import com.hencohen.grocery.parse.toStoreInfo
import com.hencohen.grocery.data.StoreDirectory
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {

    private val priceXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- sample -->
        <root>
          <ChainId>7290027600007</ChainId>
          <Items Count="3">
            <Item>
              <PriceUpdateDate>2026-11-24 03:00</PriceUpdateDate>
              <ItemCode>7290000066318</ItemCode>
              <ItemType>1</ItemType>
              <ItemName>חלב 3% קרטון 1 ל' &amp; מבצע</ItemName>
              <ManufacturerName>תנובה</ManufacturerName>
              <UnitQty>ליטר</UnitQty>
              <Quantity>1.00</Quantity>
              <bIsWeighted>0</bIsWeighted>
              <UnitOfMeasure>ליטר</UnitOfMeasure>
              <ItemPrice>7.10</ItemPrice>
              <UnitOfMeasurePrice>7.1</UnitOfMeasurePrice>
              <AllowDiscount/>
            </Item>
            <Item>
              <ItemCode>0007290011194246</ItemCode>
              <ItemNm><![CDATA[קוטג' 5%]]></ItemNm>
              <ItemPrice>5.9</ItemPrice>
            </Item>
            <Item>
              <ItemCode>123</ItemCode>
              <ItemType>0</ItemType>
              <ItemName>עגבניות</ItemName>
              <bIsWeighted>1</bIsWeighted>
              <ItemPrice>8.905</ItemPrice>
            </Item>
          </Items>
        </root>
    """.trimIndent()

    private fun parseItems(source: Buffer, chain: Chain = Chain.SHUFERSAL): List<RawItem> {
        val items = mutableListOf<RawItem>()
        XmlPullParser(source.decodedChars()).readRecords("Item") { r -> r.toRawItem(chain)?.let(items::add) }
        return items
    }

    @Test
    fun parsesUtf8PriceFile() {
        val items = parseItems(Buffer().writeUtf8(priceXml))
        assertEquals(3, items.size)

        val milk = items[0]
        assertEquals("7290000066318", milk.barcode)
        assertEquals("חלב 3% קרטון 1 ל' & מבצע", milk.name)
        assertEquals("תנובה", milk.manufacturer)
        assertEquals(710L, milk.price)
        assertEquals(710L, milk.unitPrice)

        val cottage = items[1]
        assertEquals("7290011194246", cottage.barcode, "leading zeros dropped")
        assertEquals("קוטג' 5%", cottage.name, "CDATA + ItemNm alias")

        val tomatoes = items[2]
        assertEquals("shufersal:123", tomatoes.barcode, "internal codes are chain-scoped")
        assertTrue(tomatoes.isWeighted)
        assertEquals(891L, tomatoes.price, "rounded half up")
    }

    @Test
    fun parsesUtf16LittleEndianWithBom() {
        val buffer = Buffer().writeByte(0xFF).writeByte(0xFE)
        priceXml.forEach { buffer.writeShortLe(it.code) }
        val items = parseItems(buffer)
        assertEquals(3, items.size)
        assertEquals("חלב 3% קרטון 1 ל' & מבצע", items[0].name)
    }

    @Test
    fun parsesUtf16WithoutBom() {
        val buffer = Buffer()
        "<Items><Item><ItemCode>1</ItemCode><ItemPrice>2</ItemPrice></Item></Items>".forEach { buffer.writeShortLe(it.code) }
        assertEquals(200L, parseItems(buffer).single().price)
    }

    @Test
    fun parsesStoresFileWithUppercaseTags() {
        val xml = """
            <asx:abap xmlns:asx="http://www.sap.com/abapxml"><asx:values><STORES>
              <STORE><STOREID>1</STOREID><STORENAME>שלי ת"א- בן יהודה</STORENAME><CITY>תל אביב</CITY></STORE>
              <STORE><STOREID>413</STOREID><STORENAME>שופרסל ONLINE</STORENAME></STORE>
            </STORES></asx:values></asx:abap>
        """.trimIndent()
        val stores = mutableListOf<StoreInfo>()
        XmlPullParser(Buffer().writeUtf8(xml).decodedChars())
            .readRecords("Store") { r -> r.toStoreInfo(Chain.SHUFERSAL)?.let(stores::add) }
        assertEquals(listOf("1", "413"), stores.map { it.storeId })
        assertEquals("413", StoreDirectory.pickOnlineStore(stores)?.storeId)
    }

    @Test
    fun picksOnlineStoreByHebrewName() {
        val stores = listOf(
            StoreInfo(Chain.CARREFOUR, "12", "קרפור היפר חיפה", "חיפה"),
            StoreInfo(Chain.CARREFOUR, "90", "קרפור אונליין", null),
        )
        assertEquals("90", StoreDirectory.pickOnlineStore(stores)?.storeId)
    }

    @Test
    fun parsesPrices() {
        assertEquals(1290L, parseAgorot("12.90"))
        assertEquals(500L, parseAgorot("5"))
        assertEquals(350L, parseAgorot("3.5"))
        assertEquals(426L, parseAgorot("4.255"))
        assertEquals(50L, parseAgorot(".5"))
        assertNull(parseAgorot(""))
        assertNull(parseAgorot("abc"))
    }

    @Test
    fun parsesFileNames() {
        val shufersal = TransparencyFileName.parse("PriceFull7290027600007-001-413-20261124-030000.gz")!!
        assertEquals("7290027600007", shufersal.chainId)
        assertEquals("413", shufersal.storeId)
        assertEquals("20261124030000", shufersal.timestamp)

        val carrefour = TransparencyFileName.parse("PriceFull7290055700007-0090-202611240300.gz")!!
        assertEquals("90", carrefour.storeId)
        assertEquals("202611240300", carrefour.timestamp)

        val stores = TransparencyFileName.parse("Stores7290027600007-000-202611240201")!!
        assertEquals("Stores", stores.kind)
        assertEquals("0", stores.storeId)

        assertNull(TransparencyFileName.parse("index.html"))
    }
}
