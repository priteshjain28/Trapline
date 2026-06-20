package com.trapline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Minimal Zerodha Kite Connect v3 REST client.
 * Docs: https://kite.trade/docs/connect/v3/
 *
 * You need your own API key + secret from https://kite.trade (paid).
 * Set the Redirect URL of your Kite app to anything (e.g. https://127.0.0.1) —
 * this app captures the request_token from that redirect inside the WebView.
 */
class Kite(
    private val apiKey: String,
    private val apiSecret: String
) {
    var accessToken: String? = null
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val base = "https://api.kite.trade"

    fun loginUrl(): String = "https://kite.zerodha.com/connect/login?v=3&api_key=$apiKey"

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Exchange the request_token (from the login redirect) for an access_token. */
    suspend fun createSession(requestToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val checksum = sha256(apiKey + requestToken + apiSecret)
            val body = FormBody.Builder()
                .add("api_key", apiKey)
                .add("request_token", requestToken)
                .add("checksum", checksum)
                .build()
            val req = Request.Builder()
                .url("$base/session/token")
                .header("X-Kite-Version", "3")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                val json = JSONObject(txt)
                if (json.optString("status") == "success") {
                    val tok = json.getJSONObject("data").getString("access_token")
                    accessToken = tok
                    Result.success(tok)
                } else {
                    Result.failure(Exception(json.optString("message", "login failed: $txt")))
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun authedGet(path: String): JSONObject {
        val req = Request.Builder()
            .url("$base$path")
            .header("X-Kite-Version", "3")
            .header("Authorization", "token $apiKey:$accessToken")
            .get().build()
        http.newCall(req).execute().use { resp ->
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    /** Last traded price for an instrument like "NSE:NIFTY 50". */
    suspend fun ltp(instrument: String): Result<Double> = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(instrument, "UTF-8")
            val json = authedGet("/quote/ltp?i=$enc")
            if (json.optString("status") == "success") {
                val price = json.getJSONObject("data")
                    .getJSONObject(instrument)
                    .getDouble("last_price")
                Result.success(price)
            } else Result.failure(Exception(json.optString("message", "ltp failed")))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Place a regular order. tradingSymbol e.g. "NIFTY2561925100PE", exchange "NFO".
     * transactionType "BUY"/"SELL", product "MIS" (intraday), orderType "MARKET"/"LIMIT".
     */
    suspend fun placeOrder(
        exchange: String,
        tradingSymbol: String,
        transactionType: String,
        quantity: Int,
        product: String = "MIS",
        orderType: String = "MARKET",
        price: Double? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fb = FormBody.Builder()
                .add("tradingsymbol", tradingSymbol)
                .add("exchange", exchange)
                .add("transaction_type", transactionType)
                .add("order_type", orderType)
                .add("quantity", quantity.toString())
                .add("product", product)
                .add("validity", "DAY")
            if (orderType == "LIMIT" && price != null) fb.add("price", price.toString())
            val req = Request.Builder()
                .url("$base/orders/regular")
                .header("X-Kite-Version", "3")
                .header("Authorization", "token $apiKey:$accessToken")
                .post(fb.build())
                .build()
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (json.optString("status") == "success") {
                    Result.success(json.getJSONObject("data").getString("order_id"))
                } else Result.failure(Exception(json.optString("message", "order rejected")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}
