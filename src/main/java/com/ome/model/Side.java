package com.ome.model;

/**
 * Represents the direction of an order.
 */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
