package com.ome.feed;

import com.ome.model.Order;

/**
 * Event published when an order changes state.
 */
public class OrderEvent implements MarketEvent {

    public enum Type {
        RECEIVED,
        OPEN,
        FILLED,
        CANCELLED
    }

    private final Type  type;
    private final Order order;
    private final long  publishedAt;

    public OrderEvent(Type type, Order order) {
        this.type        = type;
        this.order       = order;
        this.publishedAt = System.nanoTime();
    }

    public Order getOrder()       { return order; }
    public Type  getOrderType()   { return type; }
    public long  getPublishedAt() { return publishedAt; }

    @Override
    public EventType getType() {
        return switch (type) {
            case RECEIVED  -> EventType.ORDER_RECEIVED;
            case OPEN      -> EventType.ORDER_OPEN;
            case FILLED    -> EventType.ORDER_FILLED;
            case CANCELLED -> EventType.ORDER_CANCELLED;
        };
    }

    @Override
    public String toString() {
        return String.format("[ORDER_EVENT | %s] %s", type, order);
    }
}
