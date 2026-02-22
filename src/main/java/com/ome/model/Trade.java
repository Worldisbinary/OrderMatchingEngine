package com.ome.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable record of a matched trade between a buy and sell order.
 *
 * Each trade records:
 *  - which buy/sell orders were matched
 *  - the execution price (maker's limit price — standard exchange convention)
 *  - the quantity matched
 *  - nanosecond timestamp for latency analysis
 */
public final class Trade {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long    tradeId;
    private final String  symbol;
    private final long    buyOrderId;
    private final long    sellOrderId;
    private final double  executionPrice;  // always the resting (maker) order's price
    private final int     quantity;
    private final long    timestampNanos;
    private final Instant instant;

    public Trade(String symbol, long buyOrderId, long sellOrderId,
                 double executionPrice, int quantity) {
        this.tradeId        = ID_GENERATOR.getAndIncrement();
        this.symbol         = symbol;
        this.buyOrderId     = buyOrderId;
        this.sellOrderId    = sellOrderId;
        this.executionPrice = executionPrice;
        this.quantity       = quantity;
        this.timestampNanos = System.nanoTime();
        this.instant        = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public long    getTradeId()        { return tradeId; }
    public String  getSymbol()         { return symbol; }
    public long    getBuyOrderId()     { return buyOrderId; }
    public long    getSellOrderId()    { return sellOrderId; }
    public double  getExecutionPrice() { return executionPrice; }
    public int     getQuantity()       { return quantity; }
    public long    getTimestampNanos() { return timestampNanos; }
    public Instant getInstant()        { return instant; }

    public double  getNotionalValue()  { return executionPrice * quantity; }

    @Override
    public String toString() {
        return String.format(
            "  ✅ TRADE #%04d | %-5s | Price: %8.2f | Qty: %6d | Notional: %10.2f | Buy#%04d vs Sell#%04d",
            tradeId, symbol, executionPrice, quantity,
            getNotionalValue(), buyOrderId, sellOrderId
        );
    }
}
