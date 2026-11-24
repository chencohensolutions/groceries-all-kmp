package com.hencohen.grocery.domain

import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.ProductPrices
import com.hencohen.grocery.model.byKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Searches by name fragment or exact barcode; returns each product with its price per chain. */
class SearchProducts(
    private val db: GroceryDatabase,
    private val chains: List<Chain>,
) {

    suspend operator fun invoke(query: String, limit: Int = 50): List<ProductPrices> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val rows = withContext(Dispatchers.IO) {
            db.groceryQueries.searchProducts(q, limit.toLong()).executeAsList()
        }
        return rows.groupBy { it.barcode }.map { (barcode, chainRows) ->
            val first = chainRows.first()
            ProductPrices(
                barcode = barcode,
                name = first.name,
                manufacturer = chainRows.firstNotNullOfOrNull { it.manufacturer },
                prices = chainRows.mapNotNull { row ->
                    chains.byKey(row.chain)?.let { it to row.price }
                }.toMap(),
            )
        }.sortedBy { it.name }
    }
}
