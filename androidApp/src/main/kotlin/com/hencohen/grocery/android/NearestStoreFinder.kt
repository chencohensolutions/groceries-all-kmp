package com.hencohen.grocery.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hencohen.grocery.model.LatLng
import com.hencohen.grocery.model.StoreInfo
import com.hencohen.grocery.model.distanceKm
import com.hencohen.grocery.model.nearestStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Finds the store closest to the phone. Store files have no coordinates, only city/address text,
 * so stores are placed with Android's Geocoder (results cached on disk). To keep it fast with
 * dozens of stores: first place each *city*, then geocode exact addresses only for stores in
 * cities near the closest one.
 */
class NearestStoreFinder(private val context: Context) {

    private val cache = context.getSharedPreferences("geocode_cache", Context.MODE_PRIVATE)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns the nearest store and its distance in km, or null if location or geocoding isn't available. */
    suspend fun nearest(stores: List<StoreInfo>): Pair<StoreInfo, Double>? {
        if (stores.isEmpty() || !Geocoder.isPresent()) return null
        val here = currentLocation() ?: return null

        // Phase 1: place each city once (or the store name when there's no usable city).
        val byArea = stores.groupBy { areaQuery(it) }
        val areaDistance = byArea.keys.associateWith { q -> geocode(q)?.let(here::distanceKm) }
        val best = areaDistance.values.filterNotNull().minOrNull()
            ?: return nearestStore(stores, here) { geocode(storeQuery(it)) } // no city resolved: try each store

        // Phase 2: exact addresses for stores in nearby cities only.
        val candidates = byArea.filterKeys { (areaDistance[it] ?: Double.MAX_VALUE) <= best + 15.0 }.values.flatten()
        return nearestStore(candidates, here) { store ->
            geocode(storeQuery(store)) ?: geocode(areaQuery(store))
        }
    }

    private fun areaQuery(store: StoreInfo): String {
        val city = store.city?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
        return if (city != null) "$city, ישראל" else "${store.name}, ישראל"
    }

    private fun storeQuery(store: StoreInfo): String {
        val city = store.city?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
        val place = store.address?.trim()?.takeIf { it.isNotEmpty() } ?: store.name
        return listOfNotNull(place, city, "ישראל").joinToString(", ")
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): LatLng? = withContext(Dispatchers.IO) {
        cache.getString(query, null)?.let { cached ->
            if (cached == NONE) return@withContext null
            val (lat, lng) = cached.split(',').map(String::toDouble)
            return@withContext LatLng(lat, lng)
        }
        val result = runCatching {
            Geocoder(context, Locale("he", "IL")).getFromLocationName(query, 1)?.firstOrNull()
        }.getOrNull()
        val latLng = result?.let { LatLng(it.latitude, it.longitude) }
        cache.edit().putString(query, latLng?.let { "${it.lat},${it.lng}" } ?: NONE).apply()
        latLng
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LatLng? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val recent = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 30 * 60_000L) return recent.toLatLng()

        for (provider in providers) {
            val fresh = withTimeoutOrNull(15_000) { requestOnce(lm, provider) }
            if (fresh != null) return fresh.toLatLng()
        }
        return recent?.toLatLng()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun requestOnce(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }
                }
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }

    private fun Location.toLatLng() = LatLng(latitude, longitude)

    private companion object {
        const val NONE = "none"
    }
}
