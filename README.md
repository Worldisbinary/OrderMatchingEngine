# Real-Time Order Matching Engine

> **Java · Data Structures · System Design**  
> Simulates real-world exchange microstructure with price-time priority matching.

---

## Overview

A production-grade order matching engine that implements the core algorithms used in modern stock exchanges (NYSE, NASDAQ, Bloomberg's EMSX). It supports all four standard order types, maintains a price-time priority order book, disseminates market data events, and computes real-time market statistics.

---

## Architecture

```
com.ome/
├── model/                  # Core domain objects
│   ├── Order.java          # Order entity — id, symbol, side, type, price, qty, status
│   ├── Trade.java          # Immutable trade execution record
│   ├── Side.java           # BUY / SELL
│   ├── OrderType.java      # LIMIT | MARKET | IOC | FOC
│   └── OrderStatus.java    # NEW → OPEN → PARTIALLY_FILLED → FILLED / CANCELLED
│
├── book/                   # Order book data structures
│   ├── PriceLevel.java     # FIFO queue of orders at one price (time priority)
│   └── OrderBook.java      # TreeMap<Price, PriceLevel> — price-time priority book
│
├── engine/
│   └── MatchingEngine.java # Routes orders to books, collects trades, tracks latency
│
├── exchange/
│   └── Exchange.java       # Top-level facade — the only public API for clients
│
├── feed/                   # Event-driven market data pipeline
│   ├── MarketEvent.java    # Marker interface for all events
│   ├── OrderEvent.java     # Order lifecycle events (RECEIVED, OPEN, FILLED, CANCELLED)
│   ├── TradeEvent.java     # Published when a trade is executed
│   └── EventBus.java       # Async pub/sub bus (BlockingQueue + dispatcher thread)
│
├── marketdata/
│   ├── MarketDataSnapshot.java  # Immutable point-in-time market data (bid, ask, spread, VWAP…)
│   └── MarketDataService.java   # Subscribes to trades, refreshes snapshots
│
└── Main.java               # 7-scenario simulation demo
```

---

## Order Types

| Type | Behaviour |
|------|-----------|
| **LIMIT** | Execute at specified price or better; rest on book if unmatched |
| **MARKET** | Execute immediately at best available price; never rests on book |
| **IOC** | Fill as much as possible immediately, cancel any remainder |
| **FOC** | Fill the entire quantity or cancel the whole order (all-or-nothing) |

---

## Key Design Decisions

### Price-Time Priority
- **Bids**: `TreeMap<Double, PriceLevel>` with **descending** comparator → `firstKey()` = best bid (highest)
- **Asks**: `TreeMap<Double, PriceLevel>` with **ascending** (natural) order → `firstKey()` = best ask (lowest)
- **Within a price level**: `ArrayDeque<Order>` gives FIFO ordering → strict time priority

### Complexity
| Operation | Complexity |
|-----------|-----------|
| Submit order | O(T log P) where T = trades, P = price levels |
| Cancel order | O(log P) using price index for O(1) lookup |
| Best bid/ask | O(1) via `TreeMap.firstKey()` |

### FOC Dry-Run
FOC orders simulate available liquidity before touching the book — the book is never partially consumed then rolled back.

### Event-Driven Architecture
The `EventBus` uses a `LinkedBlockingQueue` to decouple the latency-critical matching loop from downstream consumers. This mirrors how real exchanges disseminate via FAST/ITCH protocols.

### Floating-Point Note
Prices use `double` here for readability. In production (Bloomberg, NYSE), prices are stored as **fixed-point integers** (`price × 10000`) to eliminate IEEE 754 rounding errors.

---

## Build & Run

**Prerequisites**: Java 17+, Maven 3.8+

```bash
# Compile and run
mvn compile exec:java -Dexec.mainClass="com.ome.Main"

# Run tests
mvn test

# Build jar
mvn package
java -jar target/order-matching-engine-1.0.0.jar
```

**Without Maven** (compile manually):
```bash
find src/main/java -name "*.java" | xargs javac -d out/
java -cp out/ com.ome.Main
```

---

## Sample Output

```
╔════════════════════════════════════════════════════════════╗
║           Real-Time Order Matching Engine                  ║
║     Price-Time Priority | LIMIT | MARKET | IOC | FOC       ║
╚════════════════════════════════════════════════════════════╝

SCENARIO 1: Seeding AAPL Order Book...
┌─────────────────────────────────────────────────────┐
│           ORDER BOOK  AAPL                          │
├───────────────┬───────────────┬─────────────────────┤
│     150.50    │     400       │  ASK 🔴             │
│     150.25    │     200       │  ASK 🔴             │
│     150.00    │     250       │  ASK 🔴             │
├───────────────┼───────────────┼─────────────────────┤
│  SPREAD       │  0.10         │  MID: 149.95        │
├───────────────┼───────────────┼─────────────────────┤
│     149.90    │     150       │  BID 🟢             │
│     149.75    │     400       │  BID 🟢             │
│     149.50    │     200       │  BID 🟢             │
└─────────────────────────────────────────────────────┘
```

---

## Tests

17 unit tests covering all order types, edge cases, price-time priority, market data correctness, and order validation.

```bash
mvn test
```

---

*Built to demonstrate exchange microstructure for a Bloomberg internship application.*
