package com.hencohen.grocery

import com.hencohen.grocery.data.DriverFactory
import com.hencohen.grocery.data.StoreDirectory
import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.domain.CompareBasket
import com.hencohen.grocery.domain.OrderList
import com.hencohen.grocery.domain.SearchProducts
import com.hencohen.grocery.domain.SyncPrices
import com.hencohen.grocery.source.CarrefourSource
import com.hencohen.grocery.source.FileDownloader
import com.hencohen.grocery.source.PriceSource
import com.hencohen.grocery.source.ShufersalSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Composition root for the shared module. Each platform creates one instance (Android: in the
 * Application class; iOS: in the App struct) and the native UI talks only to the use cases here.
 *
 * Adding a chain: write a [PriceSource] for its portal (or reuse a platform source — many
 * chains share the Cerberus / laibcatalog portals) and add it to [sources]. Nothing else changes.
 */
class GroceryCore(driverFactory: DriverFactory) {

    private val http = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000L
            requestTimeoutMillis = 300_000L // PriceFull files run 5–15 MB compressed
        }
        defaultRequest {
            header("Accept-Language", "he-IL,he;q=0.9,en;q=0.8")
            header("User-Agent", "GroceryCompare/0.1 (personal use)")
        }
    }

    private val db = GroceryDatabase(driverFactory.create())
    private val downloader = FileDownloader(http)

    val sources: List<PriceSource> = listOf(
        ShufersalSource(http),
        CarrefourSource(http),
    )

    val chains = sources.map { it.chain }

    val stores = StoreDirectory(sources, downloader, db)

    @OptIn(ExperimentalTime::class)
    val syncPrices = SyncPrices(sources, downloader, stores, db) { Clock.System.now().toEpochMilliseconds() }

    val searchProducts = SearchProducts(db, chains)

    val orderList = OrderList(db)

    val compareBasket = CompareBasket(db, orderList, chains)
}
