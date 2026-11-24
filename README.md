# groceries-all-kmp

Build one grocery list and see what it costs at each Israeli online supermarket.

Prices come from the files that chains must publish under the Price Transparency Law (no scraping
of the shopping sites). Everything runs on the phone: a weekly background job downloads each
chain's newest `PriceFull` file for its online store, parses it, and stores it in SQLite.

- **Shared logic:** Kotlin Multiplatform (`shared/`): portal listing, download, XML parsing, DB, basket math
- **Android UI:** Jetpack Compose + WorkManager (`androidApp/`)
- **iOS UI:** SwiftUI, later. The `iosArm64` / `iosSimulatorArm64` targets and `IosDriverFactory` are already in place.

## Chains

Chains with an online store, grouped by the portal they publish on. The online store is picked
automatically from each chain's Stores file (name contains "online" / "אונליין" / "אינטרנט" / "Wolt"),
and can be turned off per chain in the **רשתות** tab.

| Chain | Portal | Source class | Status |
|---|---|---|---|
| שופרסל | prices.shufersal.co.il | `ShufersalSource` (store 413) | Listing checked against the live site |
| קרפור | prices.carrefour.co.il | `PublishPriceSource` | Format from the maintained reference scraper |
| רמי לוי, יוחננוף, אושר עד, טיב טעם, קשת טעמים | url.publishedprices.co.il (Cerberus) | `CerberusSource` | Web login flow — **verify live** |
| ויקטורי, מחסני השוק | laibcatalog.co.il API | `LaibcatalogSource` | Format from the reference scraper |
| חצי חינם | shop.hazi-hinam.co.il/Prices | `HaziHinamSource` | Format from the reference scraper |
| קינג סטור | kingstore.binaprojects.com (HTTP) | `BinaSource` | Format from the reference scraper |
| וולט מרקט | wm-gateway.wolt.com daily index | `WoltSource` | Format from the reference scraper |

Not added: Quik (now part of Carrefour; its portal is deprecated in the reference scraper), and
chains without an online store or that aren't groceries (Super-Pharm, Good Pharm, Yellow, Dor Alon…).
Most portals only answer Israeli IPs. Portal formats are based on
[il-supermarket-scraper](https://github.com/OpenIsraeliSupermarkets/israeli-supermarket-scarpers).

### Adding a chain

1. Add `val NEWCHAIN = Chain("key", "<13-digit chain id>", "שם")` in `model/Models.kt`.
2. Write a `PriceSource` that lists the portal's files (`listFiles`). Downloading, gzip, UTF-8/UTF-16
   decoding and parsing are shared. Many chains share a platform (Cerberus `url.publishedprices.co.il`,
   laibcatalog), so one source class parameterised by chain can cover several.
3. Register it in `GroceryCore.sources`. If its online store ID is known, add it to
   `StoreDirectory.KNOWN_ONLINE_STORES`; otherwise it is auto-detected by name.

## Layout

```
shared/src/commonMain/kotlin/com/hencohen/grocery/
  model/       Chain, RawItem, ProductPrices, BasketComparison…
  parse/       CharInput (encoding detection), XmlPullParser (streaming), RecordReader,
               ItemMapping (field aliases, agorot, barcode keys), TransparencyFileName
  source/      PriceSource + ShufersalSource, CarrefourSource, FileDownloader
  data/        DriverFactory, StoreDirectory (which store per chain)
  domain/      SyncPrices, SearchProducts, OrderList, CompareBasket
  GroceryCore  composition root used by the native apps
shared/src/commonMain/sqldelight/…/Grocery.sq   schema + queries
androidApp/    GroceryApp, PriceSyncWorker, MainViewModel, MainActivity (Compose)
```

## Design notes

- **Prices are in agorot (`Long`)** so totals don't drift.
- **Matching across chains is by barcode** with leading zeros stripped. Internal codes
  (`ItemType 0`, e.g. loose produce) are prefixed with the chain key so they never match by accident.
- **Each chain is imported in one transaction**, so a failed or empty file leaves the previous prices in place.
- **Download URLs expire** (Shufersal's are signed Azure links), so the portal is always listed right before downloading.
- **Carrefour files can carry stale StoreIds inside**, so the store is taken from the file name.
- **Sync schedule:** weekly on Wi-Fi while charging, a one-off sync on first launch, and a manual "עדכון מחירים" button.

## First run checklist

Run these from a phone or computer in Israel; Carrefour's portal blocks foreign IPs.

1. CI runs `:shared:testDebugUnitTest` and builds the APK on every push.
2. Run the app and tap **עדכון מחירים**. The **רשתות** tab shows, per chain, when it last synced and any error.
3. If a chain fails, its portal format probably differs from the test fixtures in `PortalSourcesTest` —
   fix that source's `parseListing`, or turn the chain off.
4. The first full sync downloads roughly 5–15 MB per chain; later syncs skip chains whose file hasn't changed.

## Next milestones

- Promotions (`PromoFull`): a "has promo" badge first, then promo-price calculation
- Barcode scanner (ML Kit)
- Manual store picker when auto-detection fails or a chain has several online stores (`StoreNotFoundException.candidates`)
- Cerberus over FTP as a fallback if the web login changes
- iOS app (SwiftUI + BGTaskScheduler, SKIE for flows)
