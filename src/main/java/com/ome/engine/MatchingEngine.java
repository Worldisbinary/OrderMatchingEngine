package com.ome.engine;

import com.ome.book.OrderBook;
import com.ome.feed.EventBus;
import com.ome.feed.OrderEvent;
import com.ome.feed.TradeEvent;
import com.ome.model.Order;
import com.ome.model.Trade;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MatchingEngine is the central coordinator of the exchange simulation.
 *
 * Responsibilities:
 *  1. Route incoming orders to the correct symbol's OrderBook
 *  2. Collect resulting trades and publish them to the EventBus
 *  3. Track per-order latency in nanoseconds
 *  4. Provide order cancellation
 *
 * One OrderBook per symbol — books are created lazily on first order.
 * Thread-safety: ConcurrentHashMap for the book registry; individual books
 * are NOT thread-safe by design (in a real engine you'd shard by symbol).
 */
public class MatchingEngine {

    private final Map<String, OrderBook> books    = new ConcurrentHashMap<>();
    private final EventBus               eventBus;
    private       long                   totalOrders = 0;
    private       long                   totalTrades = 0;

    public MatchingEngine(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submit an order to the engine.
     * @return list of trades generated (may be empty)
     */
    public List<Trade> submit(Order order) {
        long start = System.nanoTime();
        totalOrders++;

        OrderBook book = books.computeIfAbsent(order.getSymbol(), OrderBook::new);

        // Publish order received event
        eventBus.publish(new OrderEvent(OrderEvent.Type.RECEIVED, order));

        // Match
        List<Trade> trades = book.addOrder(order);

        // Publish trade events
        for (Trade t : trades) {
            eventBus.publish(new TradeEvent(t));
            totalTrades++;
        }

        // Publish final order status event
        OrderEvent.Type finalEvent = resolveOrderEvent(order);
        eventBus.publish(new OrderEvent(finalEvent, order));

        long latencyNs = System.nanoTime() - start;
        System.out.printf("  ⏱  Latency: %,d ns%n", latencyNs);

        return trades;
    }

    /**
     * Cancel a resting order by id.
     */
    public boolean cancel(String symbol, long orderId) {
        OrderBook book = books.get(symbol.toUpperCase());
        if (book == null) {
            System.out.printf("  ⚠️  No order book for %s%n", symbol);
            return false;
        }
        boolean cancelled = book.cancelOrder(orderId);
        if (cancelled) {
            System.out.printf("  🗑  Order #%04d cancelled from %s book.%n", orderId, symbol);
        } else {
            System.out.printf("  ⚠️  Order #%04d not found in %s (already filled?).%n", orderId, symbol);
        }
        return cancelled;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public OrderBook getBook(String symbol) {
        return books.get(symbol.toUpperCase());
    }

    public void printBook(String symbol) {
        OrderBook book = books.get(symbol.toUpperCase());
        if (book == null) System.out.printf("No order book for %s%n", symbol);
        else book.printOrderBook();
    }

    public void printStats() {
        System.out.println();
        System.out.println("┌─────────────────────────────────────┐");
        System.out.println("│        ENGINE STATISTICS            │");
        System.out.println("├─────────────────────────────────────┤");
        System.out.printf ("│  Total Orders Processed: %-10d │%n", totalOrders);
        System.out.printf ("│  Total Trades Generated: %-10d │%n", totalTrades);
        System.out.printf ("│  Active Books:           %-10d │%n", books.size());
        System.out.println("└─────────────────────────────────────┘");
        System.out.println();
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private OrderEvent.Type resolveOrderEvent(Order order) {
        if (order.isFilled())    return OrderEvent.Type.FILLED;
        if (order.isCancelled()) return OrderEvent.Type.CANCELLED;
        return OrderEvent.Type.OPEN;
    }
}
