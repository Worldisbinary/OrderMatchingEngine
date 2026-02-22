package com.ome.model;

/**
 * Lifecycle status of an order.
 */
public enum OrderStatus {
    NEW,           // Just submitted
    OPEN,          // Resting on the order book
    PARTIALLY_FILLED, // Some quantity has been matched
    FILLED,        // Fully matched
    CANCELLED,     // Cancelled by user or engine (IOC/FOC residual)
    REJECTED       // Rejected (e.g. invalid parameters)
}
