# Sac Grocery Prices

An Android app that compares grocery staple prices across Sacramento stores
(Midtown / Downtown / East Sac, ZIP 95816), with data that refreshes itself every day.

## How it works

1. **Daily job** (`.github/workflows/update-prices.yml`) runs `scraper/fetch_prices.py`
   every morning on GitHub's servers. It reads each store's current weekly ad,
   matches ad items to the staples list, converts prices to a common unit
   (per lb, per dozen, per oz) and publishes `prices.json` and `history.json`
   to the `data` branch.
2. **The app** downloads those two files when opened (and keeps the last copy
   for offline use). No account, server or API key is involved.
3. **App builds** (`.github/workflows/build-apk.yml`) publish an installable APK
   to the Releases page whenever the app code changes.

## What the prices are

Prices come from store weekly ads, so they are advertised sale and member
prices, not every shelf price. An item shows for a store only when that store
advertises it; otherwise the app shows the last advertised price, dimmed, with its date.

| Store | Data |
|---|---|
| Safeway, Save Mart, Raley's, Grocery Outlet, Sprouts, Smart & Final, Foods Co | Weekly grocery ad |
| Target, Costco | Grocery items from their general ad (few) |
| Walmart | Ad is almost all non-grocery |
| WinCo, Trader Joe's | No ads published; always blank |

A unit price is shown only when the ad states a size. Ads that bundle several
products at one price without a clear size show the plain price, marked "size not in ad".

## Install on your phone

Open this on the phone and tap the downloaded file:

    https://github.com/Thechristianfreed/Grocery-Pricing/releases/latest/download/grocery-prices.apk

Android will ask once to allow installs from your browser.

## Customising

- `scraper/staples.json`: the staples list and the words used to match ad items.
- `scraper/stores.json`: ZIP code and stores.

Edits take effect on the next daily run (or run "Update prices" by hand from the Actions tab).

## Developing

    python3 scraper/fetch_prices.py --out data      # fetch prices locally
    ./gradlew testDebugUnitTest                      # unit + screen tests
    ./gradlew recordRoborazziDebug                   # render screens to app/build/screenshots
    ./gradlew assembleDebug                          # build an APK

`sideload.keystore` is a fixed signing key kept in the repo so each build can
update the previous install. It is not a Play Store key.
