package com.ome.book;

/**
 * Plain counters starting at 1. Not thread-safe: the engine is single-threaded, which is
 * also what makes ids deterministic across a replay.
 */
public final class SequentialIds implements IdSource {

    private long seq;
    private long tradeId;
    private long orderId;

    @Override
    public long nextSeq() {
        return ++seq;
    }

    @Override
    public long nextTradeId() {
        return ++tradeId;
    }

    public long nextOrderId() {
        return ++orderId;
    }

    public long lastOrderId() {
        return orderId;
    }

    public long lastTradeId() {
        return tradeId;
    }

    public long lastSeq() {
        return seq;
    }
}
