package com.hencohen.grocery

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.LatLng
import com.hencohen.grocery.model.StoreInfo
import com.hencohen.grocery.model.distanceKm
import com.hencohen.grocery.model.nearestStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val haifa = LatLng(32.794, 34.989)
    private val telAviv = LatLng(32.085, 34.781)
    private val kiryatMotzkin = LatLng(32.837, 35.077)

    @Test
    fun distance() {
        val d = haifa.distanceKm(telAviv)
        assertTrue(d in 78.0..85.0, "Haifa–Tel Aviv ≈ 81 km, got $d")
    }

    @Test
    fun picksClosestPlacedStore() = runTest {
        val stores = listOf(
            StoreInfo(Chain.YOHANANOF, "1", "יוחננוף תל אביב", "תל אביב"),
            StoreInfo(Chain.YOHANANOF, "2", "יוחננוף חיפה", "חיפה"),
            StoreInfo(Chain.YOHANANOF, "3", "יוחננוף ???", null),
        )
        val coords = mapOf("1" to telAviv, "2" to haifa)
        val (store, km) = nearestStore(stores, kiryatMotzkin) { coords[it.storeId] }!!
        assertEquals("2", store.storeId)
        assertTrue(km < 15)
    }
}
