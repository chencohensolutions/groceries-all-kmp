package com.hencohen.grocery

import com.hencohen.grocery.domain.ChainFees
import com.hencohen.grocery.domain.compareBasket
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.OrderLine
import com.hencohen.grocery.source.CarrefourSource
import com.hencohen.grocery.source.ShufersalSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingAndBasketTest {

    @Test
    fun shufersalListingPicksSignedLinks() {
        val html = """
            <table><tr>
              <td><a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-413-20261123-030000.gz?sv=2014-02-14&amp;sr=b&amp;sig=abc&amp;se=2026-11-23T21%3A35%3A00Z&amp;sp=r">לחץ להורדה</a></td>
            </tr><tr>
              <td><a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-413-20261124-030000.gz?sv=2014-02-14&amp;sr=b&amp;sig=def&amp;sp=r">לחץ להורדה</a></td>
            </tr><tr>
              <td><a href="/FileObject/UpdateCategory?catID=2&amp;page=2">2</a></td>
            </tr></table>
        """.trimIndent()
        val files = ShufersalSource.parseListing(html)
        assertEquals(2, files.size)
        val latest = files.maxBy { it.timestamp }
        assertEquals("413", latest.storeId)
        assertTrue("sig=def&sp=r" in latest.url, "HTML entities decoded")
    }

    /** UNVERIFIED shape — replace with a real page snippet on the first live run. */
    @Test
    fun carrefourListingResolvesAgainstPath() {
        val html = """
            <script>
              const path = "/2026/11/24";
              const files = [{"name":"PriceFull7290055700007-0090-202611240300.gz"},{"name":"Stores7290055700007-000-202611240200.xml"}];
            </script>
        """.trimIndent()
        val files = CarrefourSource.parseListing(html)
        assertEquals(2, files.size)
        assertEquals("https://prices.carrefour.co.il/2026/11/24/PriceFull7290055700007-0090-202611240300.gz", files[0].url)
        assertEquals("90", files[0].storeId)
    }

    @Test
    fun basketTotalsPerChain() {
        val lines = listOf(
            OrderLine("111", "חלב", 2.0),
            OrderLine("222", "לחם", 1.0),
            OrderLine("333", "עגבניות", 1.5),
        )
        val prices = mapOf(
            Chain.SHUFERSAL to mapOf("111" to 710L, "222" to 1290L, "333" to 890L),
            Chain.CARREFOUR to mapOf("111" to 690L, "222" to 1190L),
        )
        val fees = mapOf(
            Chain.SHUFERSAL to ChainFees(deliveryFee = 2990, minOrder = 15000),
            Chain.CARREFOUR to ChainFees(deliveryFee = 1990),
        )
        val result = compareBasket(lines, prices, fees, listOf(Chain.SHUFERSAL, Chain.CARREFOUR))

        val shufersal = result.totals.first { it.chain == Chain.SHUFERSAL }
        assertEquals(1420L + 1290L + 1335L, shufersal.subtotal)
        assertEquals(shufersal.subtotal + 2990L, shufersal.total)
        assertTrue(shufersal.belowMinimum)
        assertTrue(shufersal.missing.isEmpty())

        val carrefour = result.totals.first { it.chain == Chain.CARREFOUR }
        assertEquals(1380L + 1190L, carrefour.subtotal)
        assertEquals(listOf("333"), carrefour.missing.map { it.barcode })

        assertEquals(Chain.SHUFERSAL, result.cheapestComplete, "only chain with every item")
        assertEquals(null, result.savings, "one complete basket: nothing to compare")
    }

    @Test
    fun savingsBetweenCompleteBaskets() {
        val lines = listOf(OrderLine("111", "חלב", 1.0))
        val prices = mapOf(
            Chain.SHUFERSAL to mapOf("111" to 710L),
            Chain.CARREFOUR to mapOf("111" to 650L),
        )
        val result = compareBasket(lines, prices, emptyMap(), listOf(Chain.SHUFERSAL, Chain.CARREFOUR))
        assertEquals(Chain.CARREFOUR, result.cheapestComplete)
        assertEquals(60L, result.savings)
    }
}
