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
import com.hencohen.grocery.model.BasketComparison
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.OrderLine
import com.hencohen.grocery.model.ProductPrices
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

data class SyncUi(val running: Boolean = false, val message: String? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val app: Application,
    private val core: GroceryCore,
) : ViewModel() {

    val chains: List<Chain> = core.chains

    val query = MutableStateFlow("")

    private val syncState: StateFlow<SyncUi> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(PriceSyncWorker.MANUAL_WORK)
            .map { infos ->
                val info = infos.firstOrNull() ?: return@map SyncUi()
                SyncUi(
                    running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                    message = info.outputData.getString(PriceSyncWorker.KEY_SUMMARY),
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

    fun setQuantity(line: OrderLine, quantity: Double) {
        viewModelScope.launch { core.orderList.setQuantity(line.barcode, line.displayName, quantity) }
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
