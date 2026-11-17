package com.ome.model;

/**
 * Receives the engine's output. Callbacks run on the engine's single thread, in sequence
 * order, so implementations must be fast and must not block.
 */
public interface ExecutionListener {

    default void onReport(ExecutionReport report) {
    }

    default void onTrade(Trade trade) {
    }
}
