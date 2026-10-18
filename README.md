# Order Matching Engine

An in-memory limit order book and matching engine in Java 17. It matches orders with
price-time priority, supports four order types across several symbols, and publishes
order, trade and market-data events on an asynchronous event bus.

I built it to understand exchange microstructure from the data-structure level up:
how a book stays sorted, how partial fills and cancels work, and how market data is
derived from the trade stream.

## What it does

1. Orders are submitted through `Exchange`, the single entry point.
2. `MatchingEngine` routes each order to the `OrderBook` for its symbol.
3. The book matches against the opposite side, best price first and oldest order first
   at the same price. Trades execute at the resting (maker) order's price.
4. Trades and order-state changes go onto an `EventBus` (a `BlockingQueue` drained by a
   background thread). `MarketDataService` consumes them and keeps per-symbol
   snapshots: best bid and ask, spread, mid, last price, VWAP and volume.

## Order types

| Type | Behaviour |
|---|---|
| LIMIT | Fills at the limit price or better. Any remainder rests on the book. |
| MARKET | Fills against whatever liquidity is available and never rests. |
| IOC (immediate-or-cancel) | Fills what it can immediately and cancels the rest. |
| FOK (fill-or-kill), `OrderType.FOC` in code | Fills completely or not at all. The book is untouched if it cannot fill. |

## Data structures

| Structure | Used for | Cost |
|---|---|---|
| `TreeMap<Double, PriceLevel>` (bids descending, asks ascending) | sorted price levels | insert or remove a level O(log n); best price via `firstKey()` O(log n) |
| `ArrayDeque<Order>` per price level | FIFO time priority within a level | O(1) at the head |
| `HashMap<Long, Double>` order id to price | locating an order for cancel | O(1) to find the level, then a linear scan within it |
| `BlockingQueue` | event bus between matching and consumers | |
| `ConcurrentHashMap` | market-data snapshots read by other threads | |
| `AtomicLong` | order and trade id generation | |

## How to run

Requires JDK 17 or newer.

```bash
# compile and run the demo (7 scenarios: seeding, crossing limit, market sweep, IOC, FOK, cancel, second symbol)
javac -encoding UTF-8 -d out $(find src/main -name "*.java")
java -cp out com.ome.Main
```

On Windows PowerShell:

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java -Path src\main | ForEach-Object FullName)
java -cp out com.ome.Main
```

## Tests

`src/test/java/com/ome/OrderBookTest.java` has 18 JUnit 5 tests covering each order type,
partial fills, maker-price execution, price-time priority, multi-level sweeps, cancels,
input validation, spread, mid and VWAP. There is no build file yet, so run them with the
JUnit console launcher:

```bash
curl -LO https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar
javac -encoding UTF-8 -d out -cp junit-platform-console-standalone-1.10.2.jar $(find src -name "*.java")
java -jar junit-platform-console-standalone-1.10.2.jar execute --class-path out --scan-class-path
```

## Project structure

```
src/main/java/com/ome/
  model/        Order, Trade, Side, OrderType, OrderStatus
  book/         OrderBook (matching logic), PriceLevel (FIFO queue at one price)
  engine/       MatchingEngine (routes orders per symbol, prints per-order wall time)
  exchange/     Exchange (public facade)
  feed/         EventBus and event types
  marketdata/   MarketDataService, MarketDataSnapshot
  Main.java     scenario walkthrough
src/test/java/com/ome/OrderBookTest.java
```

## Limitations

- Prices are `double`. A production engine would use integer ticks to avoid
  floating-point key mismatches.
- Orders must be submitted from a single thread, because the book is not synchronised.
- The engine has not been benchmarked. The per-order time it prints is one `System.nanoTime()`
  difference on a cold JVM, including event publication, so it is not a latency figure.
- Cancelling scans the orders at one price level. There is no amend or replace, no
  persistence and no network interface.

## Licence

MIT, see [LICENSE](LICENSE).
