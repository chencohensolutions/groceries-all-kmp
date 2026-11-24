package com.hencohen.grocery.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hencohen.grocery.model.BasketComparison
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.ChainTotal
import com.hencohen.grocery.model.OrderLine
import com.hencohen.grocery.model.ProductPrices
import com.hencohen.grocery.model.formatShekels

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(viewModel(factory = MainViewModel.Factory))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sync by vm.sync.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val comparison by vm.comparison.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("השוואת סל") },
                actions = {
                    TextButton(onClick = vm::syncNow, enabled = !sync.running) {
                        Text(if (sync.running) "מסנכרן…" else "עדכון מחירים")
                    }
                },
            )
        },
        bottomBar = {
            // Pinned on both tabs so the totals update live while adding items from search.
            if (order.isNotEmpty() && comparison.totals.isNotEmpty()) {
                SavingsBar(comparison, onClick = { tab = 1 })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sync.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            sync.message?.takeIf { !sync.running }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("חיפוש") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("הרשימה שלי (${order.size})") })
            }
            when (tab) {
                0 -> SearchTab(vm)
                else -> OrderTab(vm)
            }
        }
    }
}

@Composable
private fun SearchTab(vm: MainViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val quantities = order.associate { it.barcode to it.quantity }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            label = { Text("שם מוצר או ברקוד") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { it.barcode }) { product ->
                ProductRow(
                    product = product,
                    chains = vm.chains,
                    quantity = quantities[product.barcode],
                    onAdd = { vm.add(product) },
                    onMinus = { vm.decrement(product) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProductRow(
    product: ProductPrices,
    chains: List<Chain>,
    quantity: Double?,
    onAdd: () -> Unit,
    onMinus: () -> Unit,
) {
    val cheapest = product.prices.values.minOrNull()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.bodyLarge)
            product.manufacturer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chains.forEach { chain ->
                    val price = product.prices[chain]
                    Text(
                        "${chain.displayName} ${price?.formatShekels() ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (price != null && price == cheapest && product.prices.size > 1) FontWeight.Bold else null,
                    )
                }
            }
        }
        // "Add" turns into a − n + stepper once the item is on the list, so it's always
        // visible what's already been added without opening the list.
        if (quantity == null) {
            Button(onClick = onAdd) { Text("הוסף") }
        } else {
            QuantityStepper(quantity, onMinus = onMinus, onPlus = onAdd)
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Double, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) {
            Text("−")
        }
        Text(
            formatQuantity(quantity),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp),
        )
        Button(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) {
            Text("+")
        }
    }
}

private fun formatQuantity(q: Double): String =
    if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()

@Composable
private fun SavingsBar(comparison: BasketComparison, onClick: () -> Unit) {
    val best = comparison.cheapestComplete
    val bestTotal = comparison.totals.firstOrNull { it.chain == best }
    val savings = comparison.savings
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (best != null && bestTotal != null) {
                    Text("הכי זול: ${best.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (savings != null && savings > 0) "חוסך ${savings.formatShekels()} מול היקר ביותר"
                        else "כל המוצרים זמינים",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("אין רשת עם כל המוצרים", style = MaterialTheme.typography.titleMedium)
                    Text("הקש לפרטים – מה חסר בכל רשת", style = MaterialTheme.typography.bodySmall)
                }
            }
            bestTotal?.let {
                Text(it.total.formatShekels(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OrderTab(vm: MainViewModel) {
    val order by vm.order.collectAsStateWithLifecycle()
    val comparison by vm.comparison.collectAsStateWithLifecycle()

    if (order.isEmpty()) {
        Text("הרשימה ריקה – חפש מוצרים והוסף אותם.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ComparisonCards(comparison) }
        item { Spacer(Modifier.padding(4.dp)) }
        items(order, key = { it.barcode }) { line ->
            OrderRow(
                line = line,
                onMinus = { vm.setQuantity(line, line.quantity - 1) },
                onPlus = { vm.setQuantity(line, line.quantity + 1) },
                onRemove = { vm.remove(line) },
            )
        }
    }
}

@Composable
private fun ComparisonCards(comparison: BasketComparison) {
    val best = comparison.cheapestComplete
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        comparison.totals.sortedBy { it.total }.forEach { total ->
            ChainTotalCard(total, isBest = total.chain == best)
        }
    }
}

@Composable
private fun ChainTotalCard(total: ChainTotal, isBest: Boolean) {
    Card(
        colors = if (isBest) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(total.chain.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(total.total.formatShekels(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "מוצרים ${total.subtotal.formatShekels()} + משלוח ${total.deliveryFee.formatShekels()}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (isBest) Text("הכי זול", color = MaterialTheme.colorScheme.primary)
            if (total.missing.isNotEmpty()) {
                Text(
                    "חסרים ${total.missing.size}: " + total.missing.joinToString { it.displayName },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (total.belowMinimum) {
                Text(
                    "מתחת למינימום הזמנה (${total.minOrder.formatShekels()})",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OrderRow(line: OrderLine, onMinus: () -> Unit, onPlus: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(line.displayName, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(
            formatQuantity(line.quantity),
            modifier = Modifier.width(36.dp).padding(horizontal = 8.dp),
        )
        OutlinedButton(onClick = onPlus) { Text("+") }
        TextButton(onClick = onRemove) { Text("הסר") }
    }
}
