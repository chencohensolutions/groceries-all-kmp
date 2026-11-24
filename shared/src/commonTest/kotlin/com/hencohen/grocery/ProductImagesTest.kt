package com.hencohen.grocery

import com.hencohen.grocery.domain.ProductImages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductImagesTest {
    @Test
    fun onlyRealBarcodesAreLookedUp() {
        assertTrue(ProductImages.isRealBarcode("7290000066318"))
        assertFalse(ProductImages.isRealBarcode("shufersal:123"))
        assertFalse(ProductImages.isRealBarcode("1234"))
    }

    @Test
    fun picksShufersalProductPhoto() {
        val html = """
            <img src="https://res.cloudinary.com/shufersal/image/upload/f_auto,q_auto/v1/prod/site/logo.png">
            <img class="pic" src="https://res.cloudinary.com/shufersal/image/upload/f_auto,q_auto/v1551800922/prod/product_images/products_medium/RXB43_M_P_7290000066318_1.png">
            <img class="pic" src="https://res.cloudinary.com/shufersal/image/upload/f_auto,q_auto/v1551800922/prod/product_images/products_large/RXB43_L_P_7290000066318_1.png">
        """
        assertEquals(
            "https://res.cloudinary.com/shufersal/image/upload/f_auto,q_auto/v1551800922/prod/product_images/products_large/RXB43_L_P_7290000066318_1.png",
            ProductImages.shufersalImageFromPage(html, "7290000066318"),
        )
        assertNull(ProductImages.shufersalImageFromPage(html, "7290000000017"))
    }
}
