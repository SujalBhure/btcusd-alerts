# BTCUSD Perpetual Alerts — premium call-style alarm app

Native Kotlin / Jetpack Compose / Material 3 Expressive.
Data: Bybit BTCUSD **inverse perpetual** `lastPrice` only (no USDT).

## Brief (anti-slop-design method)
- **Idea:** "The trading desk that wakes you." The live BTCUSD perp chart IS the hero; everything else is quiet chrome.
- **Reference:** Bybit Pro dark terminal (`#0B0E11` ground, amber `#F7A600` action, green `#0ECB81` / red `#F6465D` state only) + Android Clock alarm full-screen pattern.
- **Surface:** mobile (one thumb, night, interrupted glance). Primary actions bottom half. Dim-state tested.
- **Grid contract:** edges 20px / cell inset 16px / targets 48dp min / type 13-15-17 + display 34 / radii 12 groups, pill primary.
- **Depth budget:** 1 living element (price numeral + chart pulse). Rest static.
- **Skill used:** `design-system/anti-slop-design/` (cloned from srikarsunchu/anti-slop-design) — `SKILL.md` gates + `surfaces/mobile.md` + `principles/hand-made.md` enforced.

## Phone-only build (no PC)
1. Install GitHub app / Chrome on phone, create repo, upload this folder.
2. GitHub Actions `build-apk.yml` builds `app-debug.apk` in cloud.
3. Download artifact to `/mnt/sdcard/Download/` and tap to install.
4. Allow: Notifications, Full-screen alarms, Battery unrestricted, DND access (optional for bypass).

## Run
- Open app → live `BTCUSD Perp • lastPrice` streams via `wss://stream.bybit.com/v5/public/inverse` topic `tickers.BTCUSD`.
- Tap `+` → enter price → Above/Below → Save. Service keeps checking even locked.
- On hit: full-screen incoming-call UI, looping `STREAM_ALARM` at max + vibration until Dismiss/Snooze.
- Test with `Test ring` in settings before funding a trade.

## Expand later
All USD inverse: change `SYMBOL` to `ETHUSD`, `XRPUSD`… same `category=inverse`. No USDT.
