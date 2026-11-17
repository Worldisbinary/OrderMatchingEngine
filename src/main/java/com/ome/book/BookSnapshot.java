package com.ome.book;

import java.util.List;

/**
 * Aggregated depth: open quantity and order count per price level, best price first on
 * each side.
 */
public record BookSnapshot(String symbol, List<Level> bids, List<Level> asks) {

    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    public record Level(long price, long quantity, int orders) {
    }

    /** Best bid, or null if there are no bids. */
    public Level bestBid() {
        return bids.isEmpty() ? null : bids.get(0);
    }

    /** Best ask, or null if there are no asks. */
    public Level bestAsk() {
        return asks.isEmpty() ? null : asks.get(0);
    }
}
