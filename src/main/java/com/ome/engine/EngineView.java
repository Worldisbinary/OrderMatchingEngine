package com.ome.engine;

import com.ome.book.BookSnapshot;
import com.ome.book.RestingOrder;
import com.ome.model.Instrument;

import java.util.Collection;
import java.util.List;

/** Read-only queries on the engine. */
public interface EngineView {

    Collection<Instrument> instruments();

    /** @throws IllegalArgumentException if the symbol is not listed */
    BookSnapshot snapshot(String symbol, int depth);

    /** @throws IllegalArgumentException if the symbol is not listed */
    List<RestingOrder> restingOrders(String symbol);

    /** Resting orders across all symbols. */
    int restingCount();

    long lastOrderId();

    long lastTradeId();

    long lastSeq();

    /** @throws IllegalStateException if any book is internally inconsistent */
    void verifyInvariants();
}
