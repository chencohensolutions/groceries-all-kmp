package com.hencohen.grocery.domain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.OrderLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The shopping list. Items are keyed by barcode so they can be priced at every chain. */
class OrderList(private val db: GroceryDatabase) {
    private val q get() = db.groceryQueries

    fun observe(): Flow<List<OrderLine>> =
        q.selectOrder().asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { OrderLine(it.barcode, it.display_name, it.quantity) }
        }

    suspend fun add(barcode: String, displayName: String, quantity: Double = 1.0) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val existing = q.selectOrderItem(barcode).executeAsOneOrNull()
                q.upsertOrderItem(barcode, displayName, (existing?.quantity ?: 0.0) + quantity)
            }
        }
    }

    suspend fun setQuantity(barcode: String, displayName: String, quantity: Double) {
        withContext(Dispatchers.IO) {
            if (quantity <= 0.0) {
                q.deleteOrderItem(barcode)
            } else {
                q.upsertOrderItem(barcode, displayName, quantity)
            }
        }
    }

    suspend fun remove(barcode: String) {
        withContext(Dispatchers.IO) { q.deleteOrderItem(barcode) }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { q.clearOrder() }
    }
}
