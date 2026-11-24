package com.hencohen.grocery.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** Great-circle distance in km. */
fun LatLng.distanceKm(other: LatLng): Double {
    val r = 6371.0
    val dLat = (other.lat - lat).toRadians()
    val dLng = (other.lng - lng).toRadians()
    val a = sin(dLat / 2).let { it * it } +
        cos(lat.toRadians()) * cos(other.lat.toRadians()) * sin(dLng / 2).let { it * it }
    return 2 * r * asin(sqrt(a))
}

private fun Double.toRadians() = this * kotlin.math.PI / 180.0

/**
 * Picks the store closest to [here]. [locate] turns a store into coordinates (platform geocoder);
 * stores it can't place are skipped. Returns null if none could be placed.
 */
suspend fun nearestStore(
    stores: List<StoreInfo>,
    here: LatLng,
    locate: suspend (StoreInfo) -> LatLng?,
): Pair<StoreInfo, Double>? =
    stores.mapNotNull { store -> locate(store)?.let { store to here.distanceKm(it) } }
        .minByOrNull { it.second }
