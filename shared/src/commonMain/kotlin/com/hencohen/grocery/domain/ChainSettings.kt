package com.hencohen.grocery.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.Agorot
import com.hencohen.grocery.model.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ChainStatus(
    val chain: Chain,
    val enabled: Boolean,
    val fees: ChainFees,
    /** Epoch millis of the last successful import, null if never synced. */
    val syncedAt: Long?,
    val itemCount: Long?,
    /** The store whose prices are used, once chosen or auto-detected. */
    val storeId: String?,
    val storeName: String?,
)

/** Per-chain on/off switch, delivery fee / minimum order, and last-sync info. */
class ChainSettings(
    private val db: GroceryDatabase,
    private val chains: List<Chain>,
) {
    private val q get() = db.groceryQueries

    fun observe(): Flow<List<ChainStatus>> = combine(
        q.selectSettings().asFlow().mapToList(Dispatchers.IO),
        q.selectSyncStates().asFlow().mapToList(Dispatchers.IO),
        q.selectStores().asFlow().mapToList(Dispatchers.IO),
    ) { settings, syncs, stores ->
        chains.map { chain ->
            val s = settings.firstOrNull { it.chain == chain.key }
            val sync = syncs.firstOrNull { it.chain == chain.key }
            val store = stores.firstOrNull { it.chain == chain.key }
            ChainStatus(
                chain = chain,
                enabled = s?.enabled != 0L,
                fees = ChainFees(s?.delivery_fee ?: 0L, s?.min_order ?: 0L),
                syncedAt = sync?.synced_at,
                itemCount = sync?.item_count,
                storeId = store?.store_id,
                storeName = store?.store_name,
            )
        }
    }

    fun observeEnabled(): Flow<List<Chain>> =
        observe().map { list -> list.filter { it.enabled }.map { it.chain } }

    suspend fun enabledChains(): List<Chain> = withContext(Dispatchers.IO) {
        val disabled = q.selectSettings().executeAsList().filter { it.enabled == 0L }.map { it.chain }.toSet()
        chains.filter { it.key !in disabled }
    }

    suspend fun setEnabled(chain: Chain, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.transaction {
            q.ensureSettings(chain.key)
            q.updateEnabled(if (enabled) 1L else 0L, chain.key)
        }
    }

    suspend fun setFees(chain: Chain, deliveryFee: Agorot, minOrder: Agorot) = withContext(Dispatchers.IO) {
        db.transaction {
            q.ensureSettings(chain.key)
            q.updateFees(deliveryFee, minOrder, chain.key)
        }
    }
}
