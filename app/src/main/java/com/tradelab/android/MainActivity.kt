package com.tradelab.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale

data class Trade(
    val symbol: String,
    val side: String,
    val quantity: Int,
    val price: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TradeLabScreen()
            }
        }
    }
}

@Composable
fun TradeLabScreen() {
    var capitalText by remember { mutableStateOf("50000") }
    var symbol by remember { mutableStateOf("NIFTY 50") }
    var quantityText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("BUY") }
    var trades by remember { mutableStateOf(listOf<Trade>()) }

    val capital = capitalText.toDoubleOrNull() ?: 0.0
    val invested = trades.filter { it.side == "BUY" }
        .sumOf { it.quantity * it.price }
    val available = capital - invested

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("TRADELAB", style = MaterialTheme.typography.headlineMedium)
                Text("Paper Trading Dashboard")
                Text("Market data: Not connected")
            }

            item {
                Text("Virtual Capital", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = capitalText,
                    onValueChange = { capitalText = it },
                    label = { Text("Starting capital (₹)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    singleLine = true
                )
                Text("Available funds: ₹${"%.2f".format(Locale.US, available)}")
            }

            item {
                Text("Watchlist", style = MaterialTheme.typography.titleLarge)
                listOf("NIFTY 50", "BANK NIFTY", "SENSEX").forEach {
                    Text("• $it  |  Price unavailable")
                }
            }

            item {
                HorizontalDivider()
                Text("Paper Order", style = MaterialTheme.typography.titleLarge)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BUY", "SELL").forEach { option ->
                        FilterChip(
                            selected = side == option,
                            onClick = { side = option },
                            label = { Text(option) }
                        )
                    }
                }

                listOf("NIFTY 50", "BANK NIFTY", "SENSEX").forEach {
                    FilterChip(
                        selected = symbol == it,
                        onClick = { symbol = it },
                        label = { Text(it) }
                    )
                }

                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Manual entry price (₹)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    singleLine = true
                )

                Button(onClick = {
                    val qty = quantityText.toIntOrNull() ?: 0
                    val price = priceText.toDoubleOrNull() ?: 0.0
                    val value = qty * price

                    if (qty > 0 && price > 0 &&
                        (side == "SELL" || value <= available)
                    ) {
                        trades = trades + Trade(symbol, side, qty, price)
                        priceText = ""
                    }
                }) {
                    Text("Place Paper Order")
                }
                Text("Manual prices are simulated entries, not live quotes.")
            }

            item {
                HorizontalDivider()
                Text("Paper Orders & Positions", style = MaterialTheme.typography.titleLarge)
                Text("Orders: ${trades.size}")
                Text("Paper P&L: ₹0.00 (live valuation not connected)")
            }

            items(trades) { trade ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("${trade.side}  ${trade.symbol}")
                        Text("Qty: ${trade.quantity}  |  Price: ₹${trade.price}")
                    }
                }
            }
        }
    }
}
