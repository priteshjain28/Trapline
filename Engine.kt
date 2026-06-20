package com.trapline.app

import kotlin.math.abs

data class Candle(
    val start: Long,
    var open: Double,
    var high: Double,
    var low: Double,
    var close: Double,
    var volume: Double = 0.0
)

enum class Verdict { GO, WAIT, NO_TRADE }
enum class Dir { BEAR, BULL }

/**
 * Builds intraday candles from polled last-traded-prices and computes a session VWAP.
 * For a starting build we approximate VWAP using price (typical price) since tick volume
 * is not polled here; upgrade to the Kite WebSocket ticker for true volume-weighted VWAP.
 */
class CandleSeries(private val bucketMs: Long = 5 * 60_000L) {
    val candles = mutableListOf<Candle>()
    private var cumPV = 0.0
    private var cumN = 0.0

    fun onPrice(ts: Long, price: Double) {
        val bucket = ts - (ts % bucketMs)
        val last = candles.lastOrNull()
        if (last == null || last.start != bucket) {
            candles.add(Candle(bucket, price, price, price, price))
        } else {
            last.high = maxOf(last.high, price)
            last.low = minOf(last.low, price)
            last.close = price
        }
        cumPV += price
        cumN += 1.0
    }

    fun vwap(): Double = if (cumN > 0) cumPV / cumN else (candles.lastOrNull()?.close ?: 0.0)
    fun last(): Candle? = candles.lastOrNull()
    fun sessionHigh(): Double = candles.maxOfOrNull { it.high } ?: 0.0
    fun sessionLow(): Double = candles.minOfOrNull { it.low } ?: 0.0
}

/** A single tweakable rule the trader confirms or the engine auto-detects. */
data class Rule(val key: String, val label: String, var checked: Boolean = false)

class Strategy {
    var dir = Dir.BEAR

    val bearRules = listOf(
        Rule("morning", "Morning push UP, then stalled"),
        Rule("breadth", "Move NOT broad-based (weak breadth)"),
        Rule("stretch", "Price was stretched above VWAP"),
        Rule("below", "Closed BELOW VWAP (5-min)"),
        Rule("retest", "VWAP retest FAILED"),
        Rule("chain", "Option chain confirms (call OI build)"),
        Rule("event", "No event landmine in window"),
        Rule("window", "Time window 12:30–14:45")
    )
    val bullRules = listOf(
        Rule("morning", "Morning flush DOWN, then stalled"),
        Rule("breadth", "Selling NOT broad-based"),
        Rule("stretch", "Price was stretched below VWAP"),
        Rule("above", "Reclaimed ABOVE VWAP (5-min)"),
        Rule("retest", "VWAP retest HELD"),
        Rule("chain", "Option chain confirms (put OI build)"),
        Rule("event", "No event landmine in window"),
        Rule("window", "Time window 12:30–14:45")
    )

    fun rules() = if (dir == Dir.BEAR) bearRules else bullRules

    /** Auto-tick the rules the engine can infer from live price vs VWAP. */
    fun autoDetect(series: CandleSeries) {
        val c = series.last() ?: return
        val v = series.vwap()
        if (dir == Dir.BEAR) {
            bearRules.first { it.key == "below" }.checked = c.close < v
            bearRules.first { it.key == "stretch" }.checked = series.sessionHigh() > v * 1.003
        } else {
            bullRules.first { it.key == "above" }.checked = c.close > v
            bullRules.first { it.key == "stretch" }.checked = series.sessionLow() < v * 0.997
        }
    }

    fun confirmedCount() = rules().count { it.checked }
    fun allConfirmed() = rules().all { it.checked }
}

class RiskGovernor {
    var capital = 100_000.0
    var riskPerTradePct = 1.0
    var dailyLossStopPct = 2.0
    var maxTradesPerDay = 2
    var roundTripCostPerLot = 60.0
    var minRR = 1.5

    var tradesToday = 0
    var lossToday = 0.0

    fun budget() = capital * riskPerTradePct / 100.0
    fun dailyStop() = capital * dailyLossStopPct / 100.0
    fun deskClosed() = tradesToday >= maxTradesPerDay || lossToday >= dailyStop()

    data class Sizing(
        val rr: Double, val riskPerLot: Double, val lots: Int,
        val totalRisk: Double, val totalReward: Double, val outlay: Double
    )

    fun size(lotSize: Int, entry: Double, stop: Double, target: Double): Sizing {
        val rUnit = abs(entry - stop)
        val rewUnit = abs(target - entry)
        val rr = if (rUnit > 0) rewUnit / rUnit else 0.0
        val riskPerLot = rUnit * lotSize + roundTripCostPerLot
        val lots = if (riskPerLot > 0) (budget() / riskPerLot).toInt() else 0
        val totalRisk = lots * riskPerLot
        val totalReward = lots * (rewUnit * lotSize) - lots * roundTripCostPerLot
        val outlay = lots * entry * lotSize
        return Sizing(rr, riskPerLot, lots, totalRisk, totalReward, outlay)
    }

    fun verdict(strategy: Strategy, s: Sizing): Pair<Verdict, List<String>> {
        val blockers = mutableListOf<String>()
        if (!strategy.allConfirmed()) blockers.add("Checklist incomplete")
        if (s.rr < minRR) blockers.add("R:R ${"%.1f".format(s.rr)} below ${minRR}")
        if (s.lots < 1) blockers.add("Stop too wide — 0 lots fit budget")
        if (tradesToday >= maxTradesPerDay) blockers.add("Max trades reached")
        if (lossToday >= dailyStop()) blockers.add("Daily loss stop hit")
        val v = when {
            blockers.isEmpty() -> Verdict.GO
            strategy.confirmedCount() > 0 -> Verdict.WAIT
            else -> Verdict.NO_TRADE
        }
        return v to blockers
    }
}
