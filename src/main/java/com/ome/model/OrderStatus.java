package com.ome.model;

/** Lifecycle status of an order, as carried on every execution report. */
public enum OrderStatus {
    /** Accepted, nothing filled yet. */
    OPEN,
    /** Some quantity filled, some still open. */
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    /** True once the order can no longer trade. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
