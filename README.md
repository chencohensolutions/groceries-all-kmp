# groceries-all-kmp

Build one grocery list and see what it costs at each Israeli online supermarket.

Prices come from the files that chains must publish under the Price Transparency Law (no scraping
of the shopping sites). Everything runs on the phone: a weekly background job downloads each
chain's newest `PriceFull` file for its online store, parses it, and stores it in SQLite.

- **Shared logic:** Kotlin Multiplatform (`shared/`): portal listing, download, XML parsing, DB, basket math
- **Android UI:** Jetpack Compose + WorkManager (`androidApp/`)
- **iOS UI:** SwiftUI, later. The `iosArm64` / `iosSimulatorArm64` targets and `IosDriverFactory` are already in place.

## Chains

| Chain | Portal | Store used | Status |
|---|---|---|---|
| Shufersal | prices.shufersal.co.il | 413 (שופרסל ONLINE) | Listing format checked against the live site |
| Carrefour | prices.carrefour.co.il | auto-detected from the Stores file (name contains "online"/"אונליין") | **Unverified** — portal only answers Israeli IPs |

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

1. `./gradlew :shared:allTests` passes. The Carrefour listing test uses a **guessed** page shape.
2. Open `https://prices.carrefour.co.il/` and look at the page source. Fix
   `CarrefourSource.parseListing` to match the real HTML, and update `ListingAndBasketTest.carrefourListingResolvesAgainstPath` with a real snippet.
3. Check Shufersal's `catID` values in `ShufersalSource.catId` (2 = PricesFull, 5 = Stores) against the portal's filter dropdown.
4. Run the app and tap **עדכון מחירים**. The summary under the top bar shows per-chain results or errors.

## Next milestones

- Promotions (`PromoFull`): a "has promo" badge first, then promo-price calculation
- Delivery fee / minimum order settings screen (the DB table and `CompareBasket.setFees` already exist)
- Barcode scanner (ML Kit)
- Manual store picker when auto-detection fails (`StoreNotFoundException.candidates`)
- iOS app (SwiftUI + BGTaskScheduler, SKIE for flows)
