package com.ome.feed;

import com.ome.model.Trade;

/**
 * Event published to the EventBus when a trade is executed.
 */
public class TradeEvent implements MarketEvent {

    private final Trade trade;
    private final long  publishedAt;

    public TradeEvent(Trade trade) {
        this.trade       = trade;
        this.publishedAt = System.nanoTime();
    }

    public Trade getTrade()       { return trade; }
    public long  getPublishedAt() { return publishedAt; }

    @Override
    public EventType getType() { return EventType.TRADE; }

    @Override
    public String toString() {
        return "[TRADE_EVENT] " + trade;
    }
}
