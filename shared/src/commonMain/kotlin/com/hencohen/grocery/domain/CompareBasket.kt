package com.hencohen.grocery.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.Agorot
import com.hencohen.grocery.model.BasketComparison
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.ChainTotal
import com.hencohen.grocery.model.OrderLine
import com.hencohen.grocery.model.byKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlin.math.roundToLong

data class ChainFees(val deliveryFee: Agorot = 0, val minOrder: Agorot = 0)

/** Live per-chain totals for the current order list. Recomputes when the list, fees or prices change. */
class CompareBasket(
    private val db: GroceryDatabase,
    private val orderList: OrderList,
    private val chains: List<Chain>,
) {
    private val q get() = db.groceryQueries

    fun observe(): Flow<BasketComparison> = combine(
        orderList.observe(),
        q.selectSettings().asFlow().mapToList(Dispatchers.IO),
        q.selectSyncStates().asFlow().mapToList(Dispatchers.IO), // re-price after each sync
    ) { lines, settings, _ ->
        val fees = settings.mapNotNull { s ->
            chains.byKey(s.chain)?.let { it to ChainFees(s.delivery_fee, s.min_order) }
        }.toMap()
        val disabled = settings.filter { it.enabled == 0L }.map { it.chain }.toSet()
        val activeChains = chains.filter { it.key !in disabled }
        val prices = if (lines.isEmpty()) emptyMap() else {
            q.pricesForBarcodes(lines.map { it.barcode }).executeAsList()
                .mapNotNull { row -> chains.byKey(row.chain)?.let { Triple(it, row.barcode, row.price) } }
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, pairs) -> pairs.toMap() }
        }
        compareBasket(lines, prices, fees, activeChains)
    }.flowOn(Dispatchers.IO)
}

/** Pure basket math, kept separate so it's easy to test. */
fun compareBasket(
    lines: List<OrderLine>,
    prices: Map<Chain, Map<String, Agorot>>,
    fees: Map<Chain, ChainFees>,
    chains: List<Chain>,
): BasketComparison {
    val totals = chains.map { chain ->
        val chainPrices = prices[chain].orEmpty()
        val chainFees = fees[chain] ?: ChainFees()
        val (found, missing) = lines.partition { it.barcode in chainPrices }
        val subtotal = found.sumOf { line -> (chainPrices.getValue(line.barcode) * line.quantity).roundToLong() }
        val delivery = if (lines.isEmpty()) 0L else chainFees.deliveryFee
        ChainTotal(
            chain = chain,
            subtotal = subtotal,
            deliveryFee = delivery,
            minOrder = chainFees.minOrder,
            total = subtotal + delivery,
            missing = missing,
        )
    }
    return BasketComparison(totals)
}
