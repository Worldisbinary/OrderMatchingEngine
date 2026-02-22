# Order Matching Engine

A high-performance, real-time order matching engine built in Java that simulates the core mechanics of modern stock exchanges like NYSE and NASDAQ. Implements strict **price-time priority** matching with support for all four standard order types.

---

## What Does This Project Do?

When you place an order on a stock exchange (e.g. "Buy 100 shares of AAPL at $150"), something needs to find a matching seller and execute the trade. That's exactly what this engine does.

This project simulates that entire process:
1. **Traders submit orders** (buy or sell, at a price, for a quantity)
2. **The engine matches them** using price-time priority — best price first, earliest order first at the same price
3. **Trades are executed** and recorded with nanosecond latency tracking
4. **Market data is updated** in real time (best bid, best ask, spread, VWAP)

---

## Order Types Supported

| Order Type | Behaviour |
|------------|-----------|
| **LIMIT**  | Execute at your specified price or better. If not fully filled, the remainder sits on the book waiting for a match. |
| **MARKET** | Execute immediately at whatever price is available. Never sits on the book. |
| **IOC** (Immediate-Or-Cancel) | Fill as much as possible right now, cancel whatever is left. |
| **FOC** (Fill-Or-Cancel) | Fill the entire order or cancel it completely — no partial fills allowed. |

---

## Project Structure

```
src/main/java/com/ome/
├── model/
│   ├── Order.java           # Represents a single order (symbol, side, type, price, quantity)
│   ├── Trade.java           # Records a matched trade between a buyer and seller
│   ├── Side.java            # BUY or SELL
│   ├── OrderType.java       # LIMIT, MARKET, IOC, FOC
│   └── OrderStatus.java     # NEW → OPEN → PARTIALLY_FILLED → FILLED / CANCELLED
│
├── book/
│   ├── OrderBook.java       # The core order book — maintains all resting orders for one symbol
│   └── PriceLevel.java      # A queue of orders at the same price (time priority)
│
├── engine/
│   └── MatchingEngine.java  # Routes orders to the right book, collects trades, tracks latency
│
├── exchange/
│   └── Exchange.java        # Top-level facade — the only entry point for submitting orders
│
├── feed/
│   ├── EventBus.java        # Async publish-subscribe bus for market events
│   ├── MarketEvent.java     # Interface for all events
│   ├── OrderEvent.java      # Fired when an order changes state
│   └── TradeEvent.java      # Fired when a trade is executed
│
├── marketdata/
│   ├── MarketDataService.java    # Listens to trades, builds market snapshots
│   └── MarketDataSnapshot.java  # Point-in-time view: bid, ask, spread, VWAP, volume
│
└── Main.java                # Runs a full simulation with 7 scenarios
```

---

## Key Data Structures & Why

| Structure | Used For | Why |
|-----------|----------|-----|
| `TreeMap<Double, PriceLevel>` | Order book (bids & asks) | Keeps prices sorted automatically. Best bid/ask in O(1) via `firstKey()` |
| `ArrayDeque<Order>` | Orders at each price level | FIFO queue — gives time priority within a price level in O(1) |
| `HashMap<Long, Double>` | Order lookup index | O(1) cancellation — find which price level an order is at instantly |
| `BlockingQueue` | Event bus | Decouples matching engine from market data consumers safely |
| `ConcurrentHashMap` | Market data snapshots | Thread-safe reads from multiple consumers simultaneously |

---

## How Price-Time Priority Works

Imagine the order book looks like this:

```
ASKS (sellers)
  150.50  →  400 shares
  150.25  →  200 shares
  150.00  →  250 shares   ← best ask (lowest sell price)
────────────────────────
  149.90  →  150 shares   ← best bid (highest buy price)
  149.75  →  400 shares
  149.50  →  200 shares
BIDS (buyers)
```

If a new BUY order comes in at $150.25:
1. It first matches against the best ask → fills at $150.00 (250 shares)
2. Then moves to the next level → fills at $150.25 (up to 200 shares)
3. If anything is left unfilled → it rests on the bid side at $149.90

Within each price level, the **earliest order gets filled first** (time priority).

---

## Scenarios Simulated in Main.java

| Scenario | What Happens |
|----------|-------------|
| 1 | Seed AAPL book with resting LIMIT orders on both sides |
| 2 | LIMIT BUY crosses the spread — partial match, remainder rests |
| 3 | MARKET SELL sweeps all available bids aggressively |
| 4 | IOC BUY — fills what's available, cancels the rest immediately |
| 5 | FOC BUY — cancelled (not enough liquidity), then filled (exact match) |
| 6 | Order cancellation — remove a resting order from the book |
| 7 | TSLA multi-symbol support — engine handles multiple stocks simultaneously |

---

## Market Data Computed in Real Time

After every trade the engine updates:
- **Best Bid / Best Ask** — top of each side of the book
- **Spread** — difference between best ask and best bid
- **Mid Price** — halfway between bid and ask
- **Last Traded Price** — price of the most recent trade
- **VWAP** — Volume Weighted Average Price (total turnover ÷ total volume)
- **Total Volume** — cumulative shares traded

---

## How to Run

**Requires:** Java 17+

```powershell
# Compile
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter "*.java" -Path "src\main" | Select-Object -ExpandProperty FullName)

# Run
java -cp out com.ome.Main
```

---

## Technologies Used

- **Java 17**
- **TreeMap** — sorted price levels for O(log n) order book operations
- **ArrayDeque** — FIFO time priority within each price level
- **BlockingQueue** — producer-consumer event pipeline
- **ConcurrentHashMap** — thread-safe market data storage
- **AtomicLong** — lock-free order and trade ID generation
- **ExecutorService / Thread** — background market data dissemination

---

*Built to simulate real-world exchange microstructure — inspired by Bloomberg EMSX and NYSE matching engine architecture.*
