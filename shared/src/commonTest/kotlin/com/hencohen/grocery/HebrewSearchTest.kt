package com.hencohen.grocery

import com.hencohen.grocery.parse.HebrewSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HebrewSearchTest {
    @Test
    fun tomatoVariantsShareAStem() {
        val stems = listOf("עגבניות", "עגבנייה", "עגבניה", "עַגְבָנִיָּה").map { HebrewSearch.terms(it).single() }
        assertEquals(setOf("עגבני"), stems.toSet())
    }

    @Test
    fun pluralsAndFinalLetters() {
        assertEquals(HebrewSearch.terms("מלפפון"), HebrewSearch.terms("מלפפונים"))
        assertEquals(HebrewSearch.terms("ביצה"), HebrewSearch.terms("ביצים"))
        assertEquals(listOf("חלב"), HebrewSearch.terms("חלב"), "short words are left alone")
    }

    @Test
    fun matchesEveryWordAtWordStart() {
        val name = HebrewSearch.searchName("עגבניות שרי מארז 500 גר'")
        assertTrue(HebrewSearch.matchesAll(name, HebrewSearch.terms("עגבנייה שרי")))
        assertTrue(HebrewSearch.matchesAll(name, HebrewSearch.terms("שרי")))
        assertFalse(HebrewSearch.matchesAll(name, HebrewSearch.terms("רי")), "no mid-word matches")
        assertFalse(HebrewSearch.matchesAll(name, HebrewSearch.terms("עגבניה תמר")))
    }

    @Test
    fun keepsNumbersAndPercent() {
        assertEquals(" חלב 3% 1.5 ליטר ", HebrewSearch.searchName("חלב 3%, 1.5 ליטר"))
    }

    @Test
    fun internalCodesMatchAcrossChainsByName() {
        assertEquals(
            HebrewSearch.matchKey("shufersal:123", "עגבניות"),
            HebrewSearch.matchKey("rami_levy:987", "עגבנייה"),
        )
        assertEquals("7290000066318", HebrewSearch.matchKey("7290000066318", "חלב"))
    }
}
