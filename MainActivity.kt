package com.trapline.app

import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val INK = Color(0xFF0B0E14)
private val PANEL = Color(0xFF141A25)
private val LINE = Color(0xFF26303F)
private val TXT = Color(0xFFE7ECF3)
private val MUT = Color(0xFF8A97AC)
private val GO = Color(0xFF2EE6A6)
private val WAIT = Color(0xFFF4B740)
private val NO = Color(0xFFFF5D6C)
private val ACC = Color(0xFF7AA2FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var kite by remember { mutableStateOf<Kite?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Surface(color = INK, modifier = Modifier.fillMaxSize()) {
        when {
            showLogin && kite != null -> KiteWebLogin(kite!!) { token ->
                scope.launch {
                    status = "Exchanging token…"
                    kite!!.createSession(token)
                        .onSuccess { loggedIn = true; showLogin = false; status = "Connected" }
                        .onFailure { status = it.message ?: "Login failed"; showLogin = false }
                }
            }
            loggedIn && kite != null -> Dashboard(kite!!)
            else -> LoginSetup(apiKey, apiSecret, status,
                onKey = { apiKey = it }, onSecret = { apiSecret = it },
                onConnect = {
                    if (apiKey.isNotBlank() && apiSecret.isNotBlank()) {
                        kite = Kite(apiKey.trim(), apiSecret.trim())
                        showLogin = true
                    } else status = "Enter your Kite API key and secret"
                })
        }
    }
}

@Composable
fun LoginSetup(
    apiKey: String, apiSecret: String, status: String,
    onKey: (String) -> Unit, onSecret: (String) -> Unit, onConnect: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("TRAPLINE", color = TXT, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
        Text("Connect your Zerodha Kite account", color = MUT, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Field("Kite API key", apiKey, onKey)
        Field("Kite API secret", apiSecret, onSecret)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(containerColor = GO),
            modifier = Modifier.fillMaxWidth()
        ) { Text("LOGIN WITH ZERODHA", color = INK, fontWeight = FontWeight.Bold) }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp)); Text(status, color = WAIT, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Needs a paid Kite Connect subscription (kite.trade). Your key is stored only on this " +
                "device for this session. Set your app's Redirect URL to anything (e.g. https://127.0.0.1) — " +
                "this screen captures the login token from that redirect.",
            color = MUT, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

@Composable
fun KiteWebLogin(kite: Kite, onToken: (String) -> Unit) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                fun capture(url: String?): Boolean {
                    if (url == null) return false
                    val t = Uri.parse(url).getQueryParameter("request_token")
                    if (!t.isNullOrBlank()) { onToken(t); return true }
                    return false
                }
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean =
                    capture(r?.url?.toString())
                override fun onPageStarted(v: WebView?, url: String?, f: android.graphics.Bitmap?) {
                    capture(url)
                }
            }
            loadUrl(kite.loginUrl())
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun Dashboard(kite: Kite) {
    val series = remember { CandleSeries() }
    val strategy = remember { Strategy() }
    val gov = remember { RiskGovernor() }

    var tick by remember { mutableStateOf(0L) }
    var price by remember { mutableStateOf(0.0) }
    var err by remember { mutableStateOf("") }

    var instrument by remember { mutableStateOf("NSE:NIFTY 50") }
    var lotSize by remember { mutableStateOf(65) }
    var optionSymbol by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("120") }
    var stop by remember { mutableStateOf("95") }
    var target by remember { mutableStateOf("175") }
    var confirmOrder by remember { mutableStateOf(false) }
    var orderMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // live poll loop
    LaunchedEffect(instrument) {
        while (true) {
            kite.ltp(instrument)
                .onSuccess { p ->
                    price = p
                    series.onPrice(System.currentTimeMillis(), p)
                    strategy.autoDetect(series)
                    tick++
                    err = ""
                }
                .onFailure { err = it.message ?: "feed error" }
            delay(2000)
        }
    }

    val sizing = gov.size(lotSize, entry.toDoubleOrNull() ?: 0.0,
        stop.toDoubleOrNull() ?: 0.0, target.toDoubleOrNull() ?: 0.0)
    val (verdict, blockers) = gov.verdict(strategy, sizing)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRAPLINE", color = TXT, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text("LIVE  ${"%.2f".format(price)}", color = GO,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))

        // verdict gate
        val gateColor = when (verdict) { Verdict.GO -> GO; Verdict.WAIT -> WAIT; else -> NO }
        Box(
            Modifier.fillMaxWidth().border(1.dp, gateColor, RoundedCornerShape(16.dp))
                .background(PANEL, RoundedCornerShape(16.dp)).padding(22.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(verdict.name.replace("_", " "), color = gateColor,
                    fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (blockers.isEmpty()) "All confirmed · ${sizing.lots} lot(s) · ${"%.1f".format(sizing.rr)}R"
                    else blockers.joinToString(" · "),
                    color = MUT, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // direction
        Row {
            DirChip("BEAR · PUT", strategy.dir == Dir.BEAR) { strategy.dir = Dir.BEAR; tick++ }
            Spacer(Modifier.width(8.dp))
            DirChip("BULL · CALL", strategy.dir == Dir.BULL) { strategy.dir = Dir.BULL; tick++ }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            InstChip("NIFTY", instrument == "NSE:NIFTY 50") { instrument = "NSE:NIFTY 50"; lotSize = 65 }
            Spacer(Modifier.width(8.dp))
            InstChip("BANKNIFTY", instrument == "NSE:NIFTY BANK") { instrument = "NSE:NIFTY BANK"; lotSize = 30 }
        }
        Spacer(Modifier.height(14.dp))

        // chart
        key(tick) { Chart(series) }
        Text("VWAP ${"%.2f".format(series.vwap())}  ·  candles ${series.candles.size}",
            color = MUT, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (err.isNotEmpty()) Text(err, color = NO, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))

        // checklist
        SectionLabel("CONFIRMATIONS")
        key(tick) {
            strategy.rules().forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { r.checked = !r.checked; tick++ }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(20.dp).background(
                            if (r.checked) GO else PANEL, RoundedCornerShape(6.dp)
                        ).border(1.dp, LINE, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(11.dp))
                    Text(r.label, color = TXT, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // sizer + risk
        SectionLabel("POSITION & RISK")
        Field("Option tradingsymbol (NFO), e.g. NIFTY2561925100PE", optionSymbol) { optionSymbol = it }
        Row {
            Box(Modifier.weight(1f)) { Field("Entry ₹", entry) { entry = it } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { Field("Stop ₹", stop) { stop = it } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { Field("Target ₹", target) { target = it } }
        }
        InfoLine("Affordable size", "${sizing.lots} lot  (qty ${sizing.lots * lotSize})")
        InfoLine("Capital at risk", "₹${sizing.totalRisk.toInt()}")
        InfoLine("Reward if target", "₹${sizing.totalReward.toInt()}")
        InfoLine("Reward : Risk", "${"%.2f".format(sizing.rr)} : 1")
        Spacer(Modifier.height(14.dp))

        // place order
        Button(
            onClick = { confirmOrder = true },
            enabled = verdict == Verdict.GO && optionSymbol.isNotBlank() && !gov.deskClosed(),
            colors = ButtonDefaults.buttonColors(containerColor = GO, disabledContainerColor = PANEL),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (gov.deskClosed()) "DESK CLOSED FOR TODAY" else "FIRE ORDER (${sizing.lots} LOT)",
                color = if (verdict == Verdict.GO && !gov.deskClosed()) INK else MUT,
                fontWeight = FontWeight.Bold
            )
        }
        if (orderMsg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(orderMsg, color = ACC, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Semi-auto by design: the engine finds the setup, you confirm the fire. " +
                "Unattended auto-execution needs SEBI algo-ID + static IP via your broker.",
            color = MUT, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(30.dp))
    }

    if (confirmOrder) {
        AlertDialog(
            onDismissRequest = { confirmOrder = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmOrder = false
                    val side = if (strategy.dir == Dir.BEAR) "BUY" else "BUY"
                    scope.launch {
                        kite.placeOrder(
                            exchange = "NFO", tradingSymbol = optionSymbol.trim(),
                            transactionType = side, quantity = sizing.lots * lotSize
                        ).onSuccess { gov.tradesToday++; orderMsg = "Order placed · id $it" }
                            .onFailure { orderMsg = "Rejected: ${it.message}" }
                    }
                }) { Text("FIRE", color = GO) }
            },
            dismissButton = { TextButton(onClick = { confirmOrder = false }) { Text("Cancel", color = MUT) } },
            title = { Text("Confirm live order", color = TXT) },
            text = {
                Text(
                    "BUY ${sizing.lots * lotSize} qty of $optionSymbol (NFO, MIS, MARKET).\n" +
                        "Capital at risk ≈ ₹${sizing.totalRisk.toInt()}. This is a REAL order.",
                    color = MUT
                )
            },
            containerColor = PANEL
        )
    }
}

@Composable
fun Chart(series: CandleSeries) {
    Canvas(
        Modifier.fillMaxWidth().height(180.dp)
            .background(Color(0xFF10141D), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        val cs = series.candles
        if (cs.isEmpty()) return@Canvas
        val hi = cs.maxOf { it.high }; val lo = cs.minOf { it.low }
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0
        val w = size.width; val h = size.height
        val cw = w / cs.size
        fun y(v: Double) = (h - (v - lo) / range * h).toFloat()
        // vwap line
        val vy = y(series.vwap())
        drawLine(ACC, Offset(0f, vy), Offset(w, vy), strokeWidth = 1.5f)
        cs.forEachIndexed { i, c ->
            val cx = i * cw + cw / 2
            val up = c.close >= c.open
            val col = if (up) GO else NO
            drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), strokeWidth = 1.5f)
            val top = y(maxOf(c.open, c.close)); val bot = y(minOf(c.open, c.close))
            drawRect(col, Offset(i * cw + cw * 0.2f, top),
                androidx.compose.ui.geometry.Size(cw * 0.6f, (bot - top).coerceAtLeast(1f)))
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = MUT, fontSize = 11.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = TXT, fontFamily = FontFamily.Monospace),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ACC, unfocusedBorderColor = LINE,
            focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
fun DirChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) ACC else PANEL, RoundedCornerShape(10.dp))
            .border(1.dp, LINE, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(text, color = if (on) INK else MUT, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun InstChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color(0xFF1A2231) else PANEL, RoundedCornerShape(10.dp))
            .border(1.dp, if (on) ACC else LINE, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = if (on) TXT else MUT, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = MUT, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, color = MUT, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(v, color = TXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
