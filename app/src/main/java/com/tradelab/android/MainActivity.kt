package com.tradelab.android

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

private const val KEY_ALIAS = "tradelab_upstox_analytics_v1"
private const val PREFS = "tradelab_secure_settings"
private const val TOKEN_KEY = "upstox_analytics_token"

data class Trade(val symbol: String, val side: String, val quantity: Int, val price: Double)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TradeLabScreen(
                    loadToken = { SecureTokenStore.read(this) },
                    saveToken = { SecureTokenStore.write(this, it) },
                    removeToken = { SecureTokenStore.clear(this) }
                )
            }
        }
    }
}

private object SecureTokenStore {
    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun write(activity: ComponentActivity, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val payload = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        activity.getSharedPreferences(PREFS, 0).edit().putString(TOKEN_KEY, encoded).apply()
    }

    fun read(activity: ComponentActivity): String? {
        val encoded = activity.getSharedPreferences(PREFS, 0).getString(TOKEN_KEY, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun clear(activity: ComponentActivity) {
        activity.getSharedPreferences(PREFS, 0).edit().remove(TOKEN_KEY).apply()
    }
}

private data class QuoteResult(val price: Double?, val message: String)

private fun fetchNiftyLtp(token: String): QuoteResult {
    val key = URLEncoder.encode("NSE_INDEX|Nifty 50", "UTF-8")
    val connection = (URL("https://api.upstox.com/v3/market-quote/ltp?instrument_key=$key")
        .openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            QuoteResult(null, "Upstox returned HTTP $code. Check token validity and API permissions.")
        } else {
            val data = JSONObject(body).optJSONObject("data")
            var ltp: Double? = null
            if (data != null) {
                val it = data.keys()
                while (it.hasNext()) {
                    val obj = data.optJSONObject(it.next())
                    val value = obj?.optDouble("last_price", Double.NaN) ?: Double.NaN
                    if (value.isFinite() && value > 0) { ltp = value; break }
                }
            }
            if (ltp != null) QuoteResult(ltp, "Live NIFTY 50 quote received.")
            else QuoteResult(null, "No NIFTY 50 quote was returned.")
        }
    } catch (e: Exception) {
        QuoteResult(null, "Connection failed: ${e.localizedMessage ?: "please try again"}")
    } finally { connection.disconnect() }
}

@Composable
fun TradeLabScreen(
    loadToken: () -> String?,
    saveToken: (String) -> Unit,
    removeToken: () -> Unit
) {
    var capitalText by remember { mutableStateOf("50000") }
    var symbol by remember { mutableStateOf("NIFTY 50") }
    var quantityText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("BUY") }
    var trades by remember { mutableStateOf(listOf<Trade>()) }

    var tokenInput by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(loadToken() != null) }
    var connectionStatus by remember {
        mutableStateOf(if (tokenSaved) "Analytics Token saved securely on this device." else "Not connected")
    }
    var loading by remember { mutableStateOf(false) }
    var niftyLtp by remember { mutableStateOf<Double?>(null) }

    val capital = capitalText.toDoubleOrNull() ?: 0.0
    val invested = trades.filter { it.side == "BUY" }.sumOf { it.quantity * it.price }
    val available = capital - invested

    fun testConnection() {
        val token = loadToken()
        if (token.isNullOrBlank()) {
            connectionStatus = "Save your Analytics Token first."
            return
        }
        loading = true
        connectionStatus = "Fetching NIFTY 50 quote…"
        thread {
            val result = fetchNiftyLtp(token)
            Handler(Looper.getMainLooper()).post {
                niftyLtp = result.price
                connectionStatus = result.message
                loading = false
                if (result.price != null) {
                    symbol = "NIFTY 50"
                    priceText = String.format(Locale.US, "%.2f", result.price)
                }
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("TRADELAB", style = MaterialTheme.typography.headlineMedium)
                Text("Paper Trading Dashboard")
                Text("Market data: ${niftyLtp?.let { "NIFTY 50 live quote received" } ?: "Not connected"}")
            }
            item {
                Text("Upstox Analytics", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Analytics Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = tokenInput.isNotBlank(), onClick = {
                        saveToken(tokenInput.trim())
                        tokenInput = ""
                        tokenSaved = true
                        connectionStatus = "Token encrypted and saved on this device."
                    }) { Text("Save securely") }
                    OutlinedButton(enabled = tokenSaved, onClick = {
                        removeToken()
                        tokenSaved = false
                        niftyLtp = null
                        connectionStatus = "Saved token removed."
                    }) { Text("Remove token") }
                }
                Button(
                    enabled = tokenSaved && !loading,
                    onClick = { testConnection() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "Connecting…" else "Test Upstox connection") }
                Text(connectionStatus, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Token is encrypted using Android Keystore. Never paste it into GitHub or share it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Text("Virtual Capital", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = capitalText, onValueChange = { capitalText = it },
                    label = { Text("Starting capital (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Text("Available funds: ₹${"%.2f".format(Locale.US, available)}")
            }
            item {
                Text("Watchlist", style = MaterialTheme.typography.titleLarge)
                Text("NIFTY 50  |  ${niftyLtp?.let { "₹${"%.2f".format(Locale.US, it)}" } ?: "Price unavailable"}")
                Text("BANK NIFTY  |  Price unavailable")
                Text("SENSEX  |  Price unavailable")
                Text("Only NIFTY 50 live quote is implemented in this first increment.")
            }
            item {
                HorizontalDivider()
                Text("Paper Order", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BUY", "SELL").forEach { option ->
                        FilterChip(selected = side == option, onClick = { side = option }, label = { Text(option) })
                    }
                }
                listOf("NIFTY 50", "BANK NIFTY", "SENSEX").forEach { option ->
                    FilterChip(selected = symbol == option, onClick = { symbol = option }, label = { Text(option) })
                }
                OutlinedTextField(
                    value = quantityText, onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = priceText, onValueChange = { priceText = it },
                    label = { Text("Price (₹) – live NIFTY quote prefills when available") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    val qty = quantityText.toIntOrNull() ?: 0
                    val price = priceText.toDoubleOrNull() ?: 0.0
                    val value = qty * price
                    if (qty > 0 && price > 0 && (side == "SELL" || value <= available)) {
                        trades = trades + Trade(symbol, side, qty, price)
                        priceText = ""
                    }
                }) { Text("Place Paper Order") }
                Text("Manual prices remain available as a fallback. Paper orders only.")
            }
            item {
                HorizontalDivider()
                Text("Paper Orders & Positions", style = MaterialTheme.typography.titleLarge)
                Text("Orders: ${trades.size}")
                Text("Paper P&L: ₹0.00 (position valuation not implemented yet)")
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
