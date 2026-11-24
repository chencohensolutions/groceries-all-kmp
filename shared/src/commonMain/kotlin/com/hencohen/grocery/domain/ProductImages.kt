package com.hencohen.grocery.domain

import com.hencohen.grocery.db.GroceryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Finds a photo for a barcode. The price files carry no images, so this looks the barcode up on
 * the chains' own shopping sites (personal use only — those photos belong to the chains/brands),
 * with Open Food Facts as a last resort. Results, including "nothing found", are cached in the DB;
 * misses are retried after [MISS_RETRY_MILLIS].
 *
 * Lookups are lazy (only for products on screen) and limited to a few at a time.
 */
class ProductImages(
    private val http: HttpClient,
    private val db: GroceryDatabase,
    private val nowMillis: () -> Long,
) {
    private val permits = Semaphore(4)
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightLock = Mutex()

    /** Ordered strategies; the first one that returns a URL wins. */
    private val lookups: List<suspend (String) -> String?> = listOf(
        ::ramiLevy,
        ::shufersal,
        ::openFoodFacts,
    )

    suspend fun imageUrl(barcode: String): String? {
        if (!isRealBarcode(barcode)) return null
        cached(barcode)?.let { return it.url }

        val lock = inFlightLock.withLock { inFlight.getOrPut(barcode) { Mutex() } }
        return lock.withLock {
            cached(barcode)?.let { return@withLock it.url } // another caller resolved it meanwhile
            val url = permits.withPermit { resolve(barcode) }
            withContext(Dispatchers.IO) { db.groceryQueries.upsertImage(barcode, url, nowMillis()) }
            url
        }
    }

    private data class Cached(val url: String?)

    private suspend fun cached(barcode: String): Cached? = withContext(Dispatchers.IO) {
        val row = db.groceryQueries.selectImage(barcode).executeAsOneOrNull() ?: return@withContext null
        if (row.url == null && nowMillis() - row.checked_at > MISS_RETRY_MILLIS) null else Cached(row.url)
    }

    private suspend fun resolve(barcode: String): String? {
        for (lookup in lookups) {
            val url = try {
                lookup(barcode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (url != null) return url
        }
        return null
    }

    /** Rami Levy serves photos at a fixed path per barcode. */
    private suspend fun ramiLevy(barcode: String): String? {
        val url = "https://img.rami-levy.co.il/product/$barcode/small.jpg"
        val response = http.get(url) { header("Referer", "https://www.rami-levy.co.il/") }
        val type = response.headers["Content-Type"].orEmpty()
        return url.takeIf { response.status.isSuccess() && type.startsWith("image/") }
    }

    /** Shufersal's product page links its Cloudinary photo, whose name contains the barcode. */
    private suspend fun shufersal(barcode: String): String? {
        val response = http.get("https://www.shufersal.co.il/online/he/p/P_$barcode")
        if (!response.status.isSuccess()) return null
        return shufersalImageFromPage(response.bodyAsText(), barcode)
    }

    private suspend fun openFoodFacts(barcode: String): String? {
        val response = http.get("https://world.openfoodfacts.org/api/v2/product/$barcode?fields=image_front_small_url")
        if (!response.status.isSuccess()) return null
        return offImagePattern.find(response.bodyAsText())?.groupValues?.get(1)?.replace("\\/", "/")
    }

    companion object {
        const val MISS_RETRY_MILLIS = 30L * 24 * 3_600_000

        private val offImagePattern = Regex(""""image_front_small_url"\s*:\s*"([^"]+)"""")
        private val cloudinaryPattern = Regex("""https://res\.cloudinary\.com/shufersal/[^"'\s)]+""")

        /** Internal chain codes ("chain:123") and short codes are never on the shopping sites. */
        fun isRealBarcode(barcode: String) = barcode.length >= 8 && barcode.all(Char::isDigit)

        fun shufersalImageFromPage(html: String, barcode: String): String? {
            val candidates = cloudinaryPattern.findAll(html).map { it.value.replace("&amp;", "&") }.toList()
            return candidates.firstOrNull { barcode in it && "products_large" in it }
                ?: candidates.firstOrNull { barcode in it }
        }
    }
}
