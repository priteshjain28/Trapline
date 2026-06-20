# Trapline — Android (live Zerodha) build

A native Android app (Kotlin + Jetpack Compose) that logs into **Zerodha Kite Connect**, streams
**live prices**, draws a live candlestick + VWAP chart, runs the **VWAP liquidity-trap** signal
engine with a hard **risk governor + kill switch**, and places **real orders** on a one-tap confirm.

## 1. Get the APK (no PC needed)

1. Create a new GitHub repo and upload **everything in this folder** (keep the structure).
2. Go to the repo's **Actions** tab → enable workflows if prompted.
3. Either push to `main` or open **Actions → Build APK → Run workflow**.
4. When the run finishes (~3–5 min), open it → **Artifacts** → download **trapline-debug-apk**.
5. Unzip → copy `app-debug.apk` to your phone → tap to install (allow "install unknown apps").

It's a **debug** APK (self-signed, for sideloading) — not a Play Store release.

## 2. Connect to the live market

You need your own **Kite Connect** API (paid, ~₹500/mo) from <https://kite.trade>:

1. Create an app there → note the **API key** and **API secret**.
2. Set the app's **Redirect URL** to anything, e.g. `https://127.0.0.1`.
3. Open Trapline → paste key + secret → **Login with Zerodha** → log in in the WebView.
   The app captures the `request_token` from the redirect and exchanges it for a session.

## 3. Trade flow

- Pick direction (Bear→Put / Bull→Call) and instrument (Nifty / Bank Nifty).
- The engine builds candles + VWAP from the live feed and auto-ticks what it can detect
  (price vs VWAP, stretch). You confirm the judgement rules (breadth, retest, option-chain).
- Type the **option tradingsymbol** you want to buy (NFO), and entry / stop / target premiums.
- The sizer computes affordable lots from your risk budget; the gate turns **GO** only when
  every rule + R:R floor + risk checks pass. Tap **FIRE**, confirm, order goes to Kite.

## Honest limits (read these)

- **No profit guarantee.** Validate expectancy on small size first. F&O can lose everything.
- **VWAP here is price-based**, not volume-weighted, because this build polls LTP every 2s.
  For true VWAP, upgrade `Kite.kt`/`Engine.kt` to the Kite **WebSocket ticker** (volume ticks).
- **Semi-auto only.** Unattended auto-fire needs SEBI's algo-ID + whitelisted static IP via your
  broker (rules in force since Oct 2025). One-tap confirm keeps you in the legal retail lane.
- **Security:** the API secret lives in-app for the session. Fine for a personal phone; never
  ship this with hard-coded keys.
- This is a **starting codebase**. If the CI build throws an error, paste the Actions log to me
  and I'll fix it — Android builds often need a tweak or two the first time.
