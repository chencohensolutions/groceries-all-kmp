package com.hencohen.grocery.data

import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.StoreInfo
import com.hencohen.grocery.parse.readRecords
import com.hencohen.grocery.parse.toStoreInfo
import com.hencohen.grocery.source.FileDownloader
import com.hencohen.grocery.source.FileKind
import com.hencohen.grocery.source.PriceSource
import com.hencohen.grocery.source.ShufersalSource
import com.hencohen.grocery.source.latest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** The chain publishes no store marked as online, so the user has to pick the branch that delivers to them. */
class StoreNotFoundException(val chain: Chain, val candidates: List<StoreInfo>) :
    Exception("No online store found for ${chain.key} among ${candidates.size} stores")

/**
 * Decides which store's prices to use per chain. Order: saved choice → known online store ID →
 * auto-detect from the chain's Stores file by name. The result is saved, so detection runs once.
 */
class StoreDirectory(
    private val sources: List<PriceSource>,
    private val downloader: FileDownloader,
    private val db: GroceryDatabase,
) {
    private val queries get() = db.groceryQueries

    suspend fun resolveStoreId(chain: Chain): String {
        queries.selectStore(chain.key).executeAsOneOrNull()?.let { return it.store_id }

        val known = KNOWN_ONLINE_STORES[chain]
        if (known != null) {
            setStore(chain, known, null)
            return known
        }

        val stores = listStores(chain)
        val online = pickOnlineStore(stores)
            ?: stores.firstOrNull()?.takeIf { chain.allStoresOnline }
            ?: throw StoreNotFoundException(chain, stores)
        setStore(chain, online.storeId, online.name)
        return online.storeId
    }

    fun setStore(chain: Chain, storeId: String, storeName: String?) {
        queries.upsertStore(chain.key, storeId, storeName)
    }

    /** The chain's stores for a picker: online stores first, then by city and name. */
    suspend fun storesForPicker(chain: Chain): List<StoreInfo> = withContext(Dispatchers.IO) {
        listStores(chain)
            .distinctBy { it.storeId }
            .sortedWith(
                compareBy<StoreInfo>({ pickOnlineStore(listOf(it)) == null }, { it.city.orEmpty() }, { it.name }),
            )
    }

    /** Downloads and parses the chain's latest Stores file. */
    suspend fun listStores(chain: Chain): List<StoreInfo> {
        val source = sources.first { it.chain == chain }
        val file = source.latest(FileKind.STORES) ?: error("No Stores file listed for ${chain.key}")
        val path = downloader.download(file, source.downloadUrl(file))
        try {
            return downloader.parse(path) { parser ->
                val stores = mutableListOf<StoreInfo>()
                parser.readRecords("Store", "Branch") { record -> record.toStoreInfo(chain)?.let(stores::add) }
                stores
            }
        } finally {
            downloader.delete(path)
        }
    }

    companion object {
        val KNOWN_ONLINE_STORES = mapOf(Chain.SHUFERSAL to ShufersalSource.ONLINE_STORE_ID)

        private val strongKeywords = listOf("online", "אונליין", "און ליין", "on line", "אינטרנט")
        private val weakKeywords = listOf("משלוחים", "ecommerce", "e-commerce", "wolt", "delivery")

        fun pickOnlineStore(stores: List<StoreInfo>): StoreInfo? {
            fun matches(store: StoreInfo, words: List<String>) =
                words.any { store.name.contains(it, ignoreCase = true) }
            return stores.firstOrNull { matches(it, strongKeywords) }
                ?: stores.firstOrNull { matches(it, weakKeywords) }
        }
    }
}
