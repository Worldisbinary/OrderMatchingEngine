package com.ome.feed;

/**
 * Marker interface for all events flowing through the EventBus.
 */
public interface MarketEvent {

    enum EventType {
        ORDER_RECEIVED,
        ORDER_OPEN,
        ORDER_FILLED,
        ORDER_CANCELLED,
        TRADE
    }

    EventType getType();
}
