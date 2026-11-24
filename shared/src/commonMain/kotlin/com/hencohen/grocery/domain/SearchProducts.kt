package com.hencohen.grocery.domain

import com.hencohen.grocery.db.GroceryDatabase
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.ProductPrices
import com.hencohen.grocery.model.byKey
import com.hencohen.grocery.parse.HebrewSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Text or barcode search across every chain.
 *
 * 1. The query is normalised and stemmed (HebrewSearch), so spelling and plural variants match.
 * 2. Candidates come from the longest query word (word-start match); the other words are checked here.
 * 3. Matches are grouped by match key — the barcode, or the normalised name for items with only
 *    an internal chain code — and every chain's rows for those keys are loaded, so each result
 *    shows the price at every chain that sells it, even if only one chain's name matched.
 */
class SearchProducts(
    private val db: GroceryDatabase,
    private val chains: List<Chain>,
) {

    suspend operator fun invoke(query: String, limit: Int = 100): List<ProductPrices> {
        val raw = query.trim()
        val terms = HebrewSearch.terms(raw)
        if (raw.length < 2 || terms.isEmpty()) return emptyList()
        val driving = terms.maxBy { it.length }

        return withContext(Dispatchers.IO) {
            val q = db.groceryQueries
            val matched = q.searchCandidates(driving, raw, CANDIDATE_LIMIT).executeAsList()
                .filter { it.barcode == raw || HebrewSearch.matchesAll(it.search_name, terms) }

            // Rank: name starts with the query, then sold at more chains, then shorter names.
            val keys = matched.groupBy { it.match_key }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<String, List<com.hencohen.grocery.db.SearchCandidates>>>(
                        { e -> if (e.value.any { it.search_name.startsWith(" $driving") }) 0 else 1 },
                        { e -> -e.value.map { it.chain }.distinct().size },
                        { e -> e.value.minOf { it.name.length } },
                    ),
                )
                .take(limit)
                .map { it.key }
            if (keys.isEmpty()) return@withContext emptyList()

            val rows = keys.chunked(500).flatMap { q.rowsForMatchKeys(it).executeAsList() }.groupBy { it.match_key }
            keys.mapNotNull { key ->
                val group = rows[key] ?: return@mapNotNull null
                ProductPrices(
                    barcode = key,
                    name = group.minBy { it.name.length }.name,
                    manufacturer = group.firstNotNullOfOrNull { it.manufacturer },
                    prices = group.mapNotNull { row -> chains.byKey(row.chain)?.let { it to row.price } }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, prices) -> prices.min() },
                )
            }
        }
    }

    private companion object {
        const val CANDIDATE_LIMIT = 3_000L
    }
}
