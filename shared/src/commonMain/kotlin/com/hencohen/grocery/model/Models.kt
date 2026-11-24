package com.hencohen.grocery.model

/**
 * A supermarket chain. [key] is the stable ID stored in the database; [chainId] is the GS1 chain
 * code used in price-transparency file names. Not an enum, so new chains can be registered
 * without touching the rest of the code — see `GroceryCore.sources`.
 */
data class Chain(
    val key: String,
    val chainId: String,
    val displayName: String,
    /** Some chains publish under more than one GS1 code (e.g. after mergers). */
    val altChainIds: List<String> = emptyList(),
    /** Every store is an online/delivery store (Wolt Market), so any store will do. */
    val allStoresOnline: Boolean = false,
) {
    val allChainIds: List<String> get() = listOf(chainId) + altChainIds

    companion object {
        // Chains with an online/delivery store, grouped by the portal platform they publish on.
        val SHUFERSAL = Chain("shufersal", "7290027600007", "שופרסל")
        val CARREFOUR = Chain("carrefour", "7290055700007", "קרפור")

        // Cerberus (url.publishedprices.co.il)
        val RAMI_LEVY = Chain("rami_levy", "7290058140886", "רמי לוי")
        val TIV_TAAM = Chain("tiv_taam", "7290873255550", "טיב טעם")
        val KESHET_TEAMIM = Chain("keshet_teamim", "7290785400000", "קשת טעמים")
        val YOHANANOF = Chain("yohananof", "7290803800003", "יוחננוף")
        val OSHER_AD = Chain("osher_ad", "7290103152017", "אושר עד")

        // laibcatalog.co.il API
        val VICTORY = Chain("victory", "7290696200003", "ויקטורי", altChainIds = listOf("7290058103393"))
        val MAHSANEI_HASHUK =
            Chain("mahsanei_hashuk", "7290661400001", "מחסני השוק", altChainIds = listOf("7290633800006"))

        // Own portals
        val HAZI_HINAM = Chain("hazi_hinam", "7290700100008", "חצי חינם")
        val WOLT_MARKET = Chain("wolt_market", "7290058249350", "וולט מרקט", allStoresOnline = true)

        // Bina (binaprojects.com)
        val KING_STORE = Chain("king_store", "7290058108879", "קינג סטור")
    }
}

fun List<Chain>.byKey(key: String): Chain? = firstOrNull { it.key == key }

/** Prices are kept in agorot (1/100 ₪) to avoid floating-point drift in totals. */
typealias Agorot = Long

fun Agorot.formatShekels(): String {
    val abs = kotlin.math.abs(this)
    val sign = if (this < 0) "-" else ""
    return "$sign₪${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/** One row from a PriceFull file, already normalised across chains. */
data class RawItem(
    val barcode: String,
    val name: String,
    val manufacturer: String?,
    val price: Agorot,
    val unitQty: String?,
    val quantity: String?,
    val unitOfMeasure: String?,
    val unitPrice: Agorot?,
    val isWeighted: Boolean,
)

/** One row from a chain's Stores file. */
data class StoreInfo(
    val chain: Chain,
    val storeId: String,
    val name: String,
    val city: String?,
    val address: String? = null,
)

/** A downloadable file from a chain's transparency portal. */
data class PortalFile(
    val name: String,
    val url: String,
    /** Store number parsed from the file name (more reliable than the StoreId inside Carrefour files). */
    val storeId: String?,
    /** Sortable timestamp parsed from the file name, e.g. "202611240300". */
    val timestamp: String,
)

/** Search result: one product with whatever prices each chain has for it. */
data class ProductPrices(
    val barcode: String,
    val name: String,
    val manufacturer: String?,
    val prices: Map<Chain, Agorot>,
)

data class OrderLine(
    val barcode: String,
    val displayName: String,
    val quantity: Double,
)

data class ChainTotal(
    val chain: Chain,
    val subtotal: Agorot,
    val deliveryFee: Agorot,
    val minOrder: Agorot,
    val total: Agorot,
    val missing: List<OrderLine>,
) {
    val belowMinimum: Boolean get() = subtotal < minOrder
}

data class BasketComparison(
    val totals: List<ChainTotal>,
) {
    /** Cheapest chain among those that have every item; null if none has them all. */
    val cheapestComplete: Chain?
        get() = totals.filter { it.missing.isEmpty() }.minByOrNull { it.total }?.chain

    /**
     * How much the cheapest complete basket saves versus the most expensive complete one.
     * Null when fewer than two chains carry every item, since the totals wouldn't be comparable.
     */
    val savings: Agorot?
        get() {
            val complete = totals.filter { it.missing.isEmpty() }
            if (complete.size < 2) return null
            return complete.maxOf { it.total } - complete.minOf { it.total }
        }
}
