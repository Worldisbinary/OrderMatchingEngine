package com.ome.marketdata;

import com.ome.book.OrderBook;
import com.ome.feed.EventBus;
import com.ome.feed.MarketEvent;
import com.ome.feed.TradeEvent;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Subscribes to TRADE events from the EventBus and maintains a current
 * MarketDataSnapshot for each symbol.
 *
 * Consumers (UIs, risk engines, algo strategies) call getSnapshot() to
 * get the latest point-in-time view without touching the live order book.
 *
 * This is analogous to how Bloomberg's B-PIPE / SAPI feeds work:
 * the terminal shows you a snapshot, not a live lock on the book.
 */
public class MarketDataService {

    private final Map<String, MarketDataSnapshot> snapshots = new ConcurrentHashMap<>();

    // Books reference needed to read bid/ask depth after a trade
    private final Map<String, Supplier<OrderBook>> bookProvider;

    public MarketDataService(EventBus eventBus, Map<String, Supplier<OrderBook>> bookProvider) {
        this.bookProvider = bookProvider;

        // Subscribe to trade events — each trade triggers a snapshot refresh
        eventBus.subscribe(MarketEvent.EventType.TRADE, event -> {
            TradeEvent te     = (TradeEvent) event;
            String     symbol = te.getTrade().getSymbol();
            refreshSnapshot(symbol);
        });
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void refreshSnapshot(String symbol) {
        Supplier<OrderBook> supplier = bookProvider.get(symbol);
        if (supplier == null) return;

        OrderBook book = supplier.get();
        if (book == null) return;

        double spread   = book.getSpread();
        double midPrice = book.getMidPrice();

        MarketDataSnapshot snap = new MarketDataSnapshot.Builder()
                .symbol(symbol)
                .bestBid(book.getBestBid())
                .bestAsk(book.getBestAsk())
                .spread(Double.isNaN(spread) ? 0 : spread)
                .midPrice(Double.isNaN(midPrice) ? 0 : midPrice)
                .lastTradePrice(book.getLastTradePrice())
                .vwap(book.getVWAP())
                .totalVolume(book.getTotalVolume())
                .bidDepth(book.getBidDepth())
                .askDepth(book.getAskDepth())
                .build();

        snapshots.put(symbol, snap);
        System.out.println("  " + snap);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public MarketDataSnapshot getSnapshot(String symbol) {
        return snapshots.get(symbol.toUpperCase());
    }

    public Collection<MarketDataSnapshot> getAllSnapshots() {
        return Collections.unmodifiableCollection(snapshots.values());
    }

    public void printAllSnapshots() {
        System.out.println();
        System.out.println("══════════════════ LIVE MARKET DATA ══════════════════");
        if (snapshots.isEmpty()) {
            System.out.println("  No market data available yet.");
        } else {
            snapshots.values().forEach(System.out::println);
        }
        System.out.println("══════════════════════════════════════════════════════");
        System.out.println();
    }
}
