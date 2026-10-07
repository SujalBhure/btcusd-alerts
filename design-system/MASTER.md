# MASTER design system — BTCUSD Alerts (anti-slop enforced)

## Ground
- Dark terminal ground `#0B0E11` full-surface (never pure #000, lift 3-5% to avoid OLED smear + amber halo). Grain 5% overlay to stop banding.
- Grouping by 4% tint shift (`#14181D` cell, `#1C2127` nested), NO borders on every box, NO shadows on void, NO purple/blue gradients ever.

## Color roles (color carries state only)
- `amber #F7A600` — primary action only (Save alert, Dismiss uses neutral).
- `green #0ECB81` — price up / above-hit. `red #F6465D` — price down / below-hit.
- Text: `#F2F2F0` primary, `rgba(235,235,245,.6)` secondary, `.3` tertiary.

## Type
- System face (Roboto/device), tabular numerals on ALL prices/countdowns (no jitter).
- Ramp: 12 metadata mono-caps +1 tracking, 14 body, 15-17 section, 34 display price (`-1px` tracking). Weights 400/500 only.
- Machinery labels: `BTCUSD PERP • BYBIT • LAST` 12px mono caps tertiary.

## Grid
edges 20 / inset 16 / icon well 36 / targets 48dp / rows 56 / spacing 4-8-12-16-24-32-48 / left edges exactly 3 x-positions (20, 36, 84).

## Motion (2 curves)
- `fast: 150ms ease-out` — price tick, press scale .97.
- `strong: 350ms spring` — sheet up, alarm entrance with 60% scrim.
- Reduced-motion: keep opacity/color, drop transforms. Haptic light on save + trigger only.

## Screens
1. **Home (one screen + sheets):** hero price 34pt tabular + 24h change chip + live candle chart (real `/v5/market/kline?category=inverse&symbol=BTCUSD` data, green/red candles, last-price dashed line) + alert rows (56dp, direction glyph + target + distance-in-$) + bottom `+` pill.
2. **Add sheet:** title, Above/Below segmented choice (current marked), price field 16px min, footnote `Watches Bybit BTCUSD perp lastPrice`, Save pill.
3. **Alarm (lock-screen):** black full-bleed, huge price, `CROSSED ABOVE 86,000`, two thumb buttons: Dismiss (neutral) / Snooze 5m (amber). `setShowWhenLocked + setTurnScreenOn`.

## Dim test
At 25% brightness: price + Dismiss still findable at 60%+ luminance, hairlines replaced by tint shifts, amber dimmed 20%.

## Icon/splash
Launcher: rounded-square `#0B0E11` + single amber candlestick (exact rects, no freehand). Splash = same ground, no logo-on-white.
