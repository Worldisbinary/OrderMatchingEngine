package com.ome.exchange;

import com.ome.engine.MatchingEngine;
import com.ome.feed.EventBus;
import com.ome.marketdata.MarketDataService;
import com.ome.marketdata.MarketDataSnapshot;
import com.ome.model.Order;
import com.ome.model.Trade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exchange is the top-level facade.
 *
 * External clients (trading apps, tests, simulations) only interact with
 * the Exchange — they never touch the MatchingEngine or OrderBook directly.
 *
 * This mirrors how real exchanges expose a FIX/OUCH gateway that hides
 * all internal complexity from the outside world.
 */
public class Exchange {

    private final String            name;
    private final EventBus          eventBus;
    private final MatchingEngine    engine;
    private final MarketDataService marketData;

    public Exchange(String name) {
        this.name      = name;
        this.eventBus  = new EventBus();
        this.engine    = new MatchingEngine(eventBus);

        // Wire MarketDataService: it needs a way to get the latest OrderBook per symbol
        Map<String, java.util.function.Supplier<com.ome.book.OrderBook>> bookSuppliers = new HashMap<>();
        // We use a lambda that lazily reads from the engine
        this.marketData = new MarketDataService(eventBus,
                new LazyBookSupplierMap(engine));
    }

    // ── Order Management ──────────────────────────────────────────────────────

    /**
     * Submit an order to the exchange. Returns all trades generated.
     */
    public List<Trade> submit(Order order) {
        return engine.submit(order);
    }

    /**
     * Cancel a resting order.
     */
    public boolean cancel(String symbol, long orderId) {
        return engine.cancel(symbol, orderId);
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    public MarketDataSnapshot getSnapshot(String symbol) {
        return marketData.getSnapshot(symbol);
    }

    public void printBook(String symbol) {
        engine.printBook(symbol);
    }

    public void printAllSnapshots() {
        marketData.printAllSnapshots();
    }

    public void printStats() {
        engine.printStats();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void shutdown() throws InterruptedException {
        eventBus.shutdown();
        System.out.printf("🔒 Exchange [%s] shut down.%n", name);
    }

    public String getName() { return name; }

    // ── Inner: lazy book supplier map ─────────────────────────────────────────

    /**
     * Adapts the MatchingEngine's book registry into the Supplier<OrderBook>
     * map that MarketDataService expects, without exposing the engine directly.
     */
    private static class LazyBookSupplierMap
            extends HashMap<String, java.util.function.Supplier<com.ome.book.OrderBook>> {

        private final MatchingEngine engine;

        LazyBookSupplierMap(MatchingEngine engine) {
            this.engine = engine;
        }

        @Override
        public java.util.function.Supplier<com.ome.book.OrderBook> get(Object key) {
            return () -> engine.getBook((String) key);
        }

        @Override
        public boolean containsKey(Object key) {
            return engine.getBook((String) key) != null;
        }
    }
}
