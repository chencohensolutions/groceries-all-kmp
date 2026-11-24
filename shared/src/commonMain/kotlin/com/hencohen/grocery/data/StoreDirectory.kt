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
        val online = pickOnlineStore(stores) ?: throw StoreNotFoundException(chain, stores)
        setStore(chain, online.storeId, online.name)
        return online.storeId
    }

    fun setStore(chain: Chain, storeId: String, storeName: String?) {
        queries.upsertStore(chain.key, storeId, storeName)
    }

    /** Downloads and parses the chain's latest Stores file. */
    suspend fun listStores(chain: Chain): List<StoreInfo> {
        val source = sources.first { it.chain == chain }
        val file = source.latest(FileKind.STORES) ?: error("No Stores file listed for ${chain.key}")
        val path = downloader.download(file)
        try {
            return downloader.parse(path) { parser ->
                val stores = mutableListOf<StoreInfo>()
                parser.readRecords("Store") { record -> record.toStoreInfo(chain)?.let(stores::add) }
                stores
            }
        } finally {
            downloader.delete(path)
        }
    }

    companion object {
        val KNOWN_ONLINE_STORES = mapOf(Chain.SHUFERSAL to ShufersalSource.ONLINE_STORE_ID)

        private val strongKeywords = listOf("online", "אונליין", "און ליין", "on line")
        private val weakKeywords = listOf("אינטרנט", "משלוחים", "ecommerce", "e-commerce")

        fun pickOnlineStore(stores: List<StoreInfo>): StoreInfo? {
            fun matches(store: StoreInfo, words: List<String>) =
                words.any { store.name.contains(it, ignoreCase = true) }
            return stores.firstOrNull { matches(it, strongKeywords) }
                ?: stores.firstOrNull { matches(it, weakKeywords) }
        }
    }
}
