package com.hencohen.grocery.domain

import com.hencohen.grocery.data.StoreDirectory
import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.parse.HebrewSearch
import com.hencohen.grocery.parse.XmlPullParser
import com.hencohen.grocery.parse.readRecords
import com.hencohen.grocery.parse.toRawItem
import com.hencohen.grocery.source.FileDownloader
import com.hencohen.grocery.source.FileKind
import com.hencohen.grocery.source.PriceSource
import com.hencohen.grocery.source.latest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

sealed interface ChainSyncResult {
    val chain: Chain
    data class Updated(override val chain: Chain, val fileName: String, val itemCount: Int) : ChainSyncResult
    data class UpToDate(override val chain: Chain, val fileName: String) : ChainSyncResult
    data class Failed(override val chain: Chain, val error: Throwable) : ChainSyncResult
}

/**
 * Downloads each chain's newest PriceFull file for its chosen store and replaces that chain's
 * prices. Each chain is imported in one transaction: if parsing fails halfway, the old prices stay.
 * Chains are independent — one failing doesn't stop the others.
 */
class SyncPrices(
    private val sources: List<PriceSource>,
    private val settings: ChainSettings,
    private val downloader: FileDownloader,
    private val stores: StoreDirectory,
    private val db: GroceryDatabase,
    private val nowMillis: () -> Long,
) {
    suspend operator fun invoke(force: Boolean = false): List<ChainSyncResult> =
        withContext(Dispatchers.IO) {
            val enabled = settings.enabledChains().toSet()
            sources.filter { it.chain in enabled }.map { source ->
                try {
                    syncChain(source, force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ChainSyncResult.Failed(source.chain, e)
                }
            }
        }

    private suspend fun syncChain(source: PriceSource, force: Boolean): ChainSyncResult {
        val chain = source.chain
        val storeId = stores.resolveStoreId(chain)
        val file = source.latest(FileKind.PRICE_FULL, storeId)
            ?: error("No PriceFull file listed for ${chain.key} store $storeId")

        val previous = db.groceryQueries.selectSyncState(chain.key).executeAsOneOrNull()
        if (!force && previous?.file_name == file.name) return ChainSyncResult.UpToDate(chain, file.name)

        val path = downloader.download(file, source.downloadUrl(file))
        try {
            val count = downloader.parse(path) { parser -> importItems(chain, parser) }
            check(count > 0) { "${file.name} contained no items" }
            db.groceryQueries.upsertSyncState(chain.key, file.name, nowMillis(), count.toLong())
            return ChainSyncResult.Updated(chain, file.name, count)
        } finally {
            downloader.delete(path)
        }
    }

    private fun importItems(chain: Chain, parser: XmlPullParser): Int {
        var count = 0
        val q = db.groceryQueries
        db.transaction {
            q.deleteChainProducts(chain.key)
            parser.readRecords("Item", "Product") { record ->
                val item = record.toRawItem(chain) ?: return@readRecords
                q.insertProduct(
                    chain = chain.key,
                    barcode = item.barcode,
                    name = item.name,
                    manufacturer = item.manufacturer,
                    price = item.price,
                    unit_qty = item.unitQty,
                    quantity = item.quantity,
                    unit_of_measure = item.unitOfMeasure,
                    unit_price = item.unitPrice,
                    is_weighted = if (item.isWeighted) 1L else 0L,
                    search_name = HebrewSearch.searchName(item.name),
                    match_key = HebrewSearch.matchKey(item.barcode, item.name),
                )
                count++
            }
            // An empty file would wipe the chain's prices; roll back instead.
            if (count == 0) rollback()
        }
        return count
    }
}
