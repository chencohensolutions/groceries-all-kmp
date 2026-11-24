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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hencohen.grocery.domain.ChainStatus
import com.hencohen.grocery.model.BasketComparison
import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.ChainTotal
import com.hencohen.grocery.model.OrderLine
import com.hencohen.grocery.model.ProductPrices
import com.hencohen.grocery.model.StoreInfo
import com.hencohen.grocery.model.formatShekels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

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
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("רשתות") })
            }
            when (tab) {
                0 -> SearchTab(vm)
                1 -> OrderTab(vm)
                else -> ChainsTab(vm)
            }
        }
    }
}

@Composable
private fun SearchTab(vm: MainViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val enabledChains by vm.enabledChains.collectAsStateWithLifecycle()
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
                    chains = enabledChains,
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
    // With a dozen chains a full price row won't fit, so show the cheapest and expand on tap.
    var expanded by rememberSaveable(product.barcode) { mutableStateOf(false) }
    val offers = chains.mapNotNull { chain -> product.prices[chain]?.let { chain to it } }.sortedBy { it.second }
    val best = offers.firstOrNull()

    Column(
        Modifier.fillMaxWidth().clickable(enabled = offers.size > 1) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.bodyLarge)
                product.manufacturer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (best == null) {
                    Text("לא נמכר ברשתות הפעילות", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(best.second.formatShekels(), fontWeight = FontWeight.Bold)
                        Text(best.first.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (offers.size > 1) {
                            Text(
                                if (expanded) "▲" else "+${offers.size - 1} רשתות ▼",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
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
        if (expanded) {
            offers.drop(1).forEach { (chain, price) ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(chain.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(price.formatShekels(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        " (+${(price - best!!.second).formatShekels()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainsTab(vm: MainViewModel) {
    val statuses by vm.chainStatuses.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    val picker by vm.storePicker.collectAsStateWithLifecycle()
    picker?.let { StorePickerDialog(it, onPick = vm::pickStore, onDismiss = vm::closeStorePicker) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "רשתות עם חנות אונליין. כבו רשתות שלא מעניינות אתכם – הן לא יסונכרנו ולא ייכללו בהשוואה.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(statuses, key = { it.chain.key }) { status ->
            ChainCard(
                status = status,
                lastResult = sync.perChain[status.chain.key],
                syncedText = status.syncedAt?.let { "עודכן ${dateFormat.format(Date(it))} · ${status.itemCount} מוצרים" }
                    ?: "טרם סונכרן",
                onEnabled = { vm.setEnabled(status.chain, it) },
                onPickStore = { vm.openStorePicker(status.chain) },
                onFees = { fee, min -> vm.setFees(status.chain, fee, min) },
            )
        }
    }
}

@Composable
private fun ChainCard(
    status: ChainStatus,
    lastResult: String?,
    syncedText: String,
    onEnabled: (Boolean) -> Unit,
    onPickStore: () -> Unit,
    onFees: (Long, Long) -> Unit,
) {
    var fee by remember(status.chain.key) { mutableStateOf(shekelsText(status.fees.deliveryFee)) }
    var min by remember(status.chain.key) { mutableStateOf(shekelsText(status.fees.minOrder)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(status.chain.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(syncedText, style = MaterialTheme.typography.bodySmall)
                    lastResult?.takeIf { it.startsWith("שגיאה") }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Switch(checked = status.enabled, onCheckedChange = onEnabled)
            }
            if (status.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "סניף: " + (status.storeName ?: status.storeId ?: "לא נבחר"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPickStore) {
                        Text(if (status.storeId == null) "בחר סניף" else "שנה סניף")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fee,
                        onValueChange = { fee = it; onFees(parseShekels(it), parseShekels(min)) },
                        label = { Text("דמי משלוח ₪") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = min,
                        onValueChange = { min = it; onFees(parseShekels(fee), parseShekels(it)) },
                        label = { Text("מינימום הזמנה ₪") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorePickerDialog(state: StorePickerUi, onPick: (StoreInfo) -> Unit, onDismiss: () -> Unit) {
    var filter by remember(state.chain.key) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } },
        title = { Text("סניף ${state.chain.displayName}") },
        text = {
            Column {
                when {
                    state.loading -> {
                        Text("טוען רשימת סניפים…")
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    state.error != null -> Text("שגיאה: ${state.error}", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text(
                            "בחרו את הסניף שמשלוח אליכם יוצא ממנו (לרוב הקרוב לבית). חנויות אונליין מופיעות ראשונות.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("חיפוש עיר או סניף") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        val shown = state.stores.filter {
                            filter.isBlank() || it.name.contains(filter.trim(), true) || it.city.orEmpty().contains(filter.trim(), true)
                        }
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown, key = { it.storeId }) { store ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { onPick(store) }.padding(vertical = 10.dp),
                                ) {
                                    Text(store.name, style = MaterialTheme.typography.bodyLarge)
                                    store.city?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun shekelsText(agorot: Long): String =
    if (agorot == 0L) "" else if (agorot % 100 == 0L) (agorot / 100).toString() else (agorot / 100.0).toString()

private fun parseShekels(text: String): Long =
    ((text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) * 100).roundToLong()

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
        // Complete baskets first (cheapest on top), then partial ones; chains with none of the
        // items (not synced, or don't stock them) collapse into one line.
        val (withItems, without) = comparison.totals.partition { it.subtotal > 0 }
        withItems.sortedWith(compareBy({ it.missing.size }, { it.total })).forEach { total ->
            ChainTotalCard(total, isBest = total.chain == best)
        }
        if (without.isNotEmpty()) {
            Text(
                "ללא נתונים לרשימה: " + without.joinToString { it.chain.displayName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
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
