package com.hencohen.grocery.android

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hencohen.grocery.GroceryCore
import com.hencohen.grocery.domain.ChainStatus
import com.hencohen.grocery.model.BasketComparison
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.OrderLine
import com.hencohen.grocery.model.ProductPrices
import com.hencohen.grocery.model.StoreInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StorePickerUi(
    val chain: Chain,
    val loading: Boolean = true,
    val stores: List<StoreInfo> = emptyList(),
    val error: String? = null,
)

data class SyncUi(
    val running: Boolean = false,
    val message: String? = null,
    /** Last sync result per chain key, e.g. "עודכנו 18,230 מוצרים" or "שגיאה: …". */
    val perChain: Map<String, String> = emptyMap(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val app: Application,
    private val core: GroceryCore,
) : ViewModel() {

    val chains: List<Chain> = core.chains

    val chainStatuses: StateFlow<List<ChainStatus>> =
        core.chainSettings.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val enabledChains: StateFlow<List<Chain>> =
        core.chainSettings.observeEnabled()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), core.chains)

    val query = MutableStateFlow("")

    private val syncState: StateFlow<SyncUi> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(PriceSyncWorker.MANUAL_WORK)
            .map { infos ->
                val info = infos.firstOrNull() ?: return@map SyncUi()
                SyncUi(
                    running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                    message = info.outputData.getString(PriceSyncWorker.KEY_SUMMARY),
                    perChain = info.outputData.getStringArray(PriceSyncWorker.KEY_DETAILS).orEmpty()
                        .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 } }
                        .associate { (key, text) -> key to text },
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SyncUi())

    val sync: StateFlow<SyncUi> = syncState

    /** Re-runs the search when the query changes or a sync finishes. */
    val results: StateFlow<List<ProductPrices>> =
        combine(query.debounce(250), syncState.map { it.running }) { q, _ -> q }
            .mapLatest { core.searchProducts(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val order: StateFlow<List<OrderLine>> =
        core.orderList.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val comparison: StateFlow<BasketComparison> =
        core.compareBasket.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BasketComparison(emptyList()))

    fun syncNow() = PriceSyncWorker.runNow(app, force = true)

    fun add(product: ProductPrices) {
        viewModelScope.launch { core.orderList.add(product.barcode, product.name) }
    }

    /** Minus on a search result; dropping to zero removes the item from the list. */
    fun decrement(product: ProductPrices) {
        val current = order.value.firstOrNull { it.barcode == product.barcode } ?: return
        viewModelScope.launch {
            core.orderList.setQuantity(product.barcode, product.name, current.quantity - 1)
        }
    }

    fun setQuantity(line: OrderLine, quantity: Double) {
        viewModelScope.launch { core.orderList.setQuantity(line.barcode, line.displayName, quantity) }
    }

    val storePicker = MutableStateFlow<StorePickerUi?>(null)

    /** Downloads the chain's store list so the user can pick the branch that delivers to them. */
    fun openStorePicker(chain: Chain) {
        storePicker.value = StorePickerUi(chain)
        viewModelScope.launch {
            storePicker.value = try {
                StorePickerUi(chain, loading = false, stores = core.stores.storesForPicker(chain))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StorePickerUi(chain, loading = false, error = e.message ?: e::class.simpleName)
            }
        }
    }

    fun closeStorePicker() {
        storePicker.value = null
    }

    fun pickStore(store: StoreInfo) {
        storePicker.value = null
        viewModelScope.launch {
            core.stores.setStore(store.chain, store.storeId, store.name)
            // Only chains whose newest file changed re-download; this one now points at a new store.
            PriceSyncWorker.runNow(app, force = false)
        }
    }

    fun setEnabled(chain: Chain, enabled: Boolean) {
        viewModelScope.launch { core.chainSettings.setEnabled(chain, enabled) }
    }

    fun setFees(chain: Chain, deliveryFee: Long, minOrder: Long) {
        viewModelScope.launch { core.chainSettings.setFees(chain, deliveryFee, minOrder) }
    }

    fun remove(line: OrderLine) {
        viewModelScope.launch { core.orderList.remove(line.barcode) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GroceryApp
                MainViewModel(app, app.core)
            }
        }
    }
}
