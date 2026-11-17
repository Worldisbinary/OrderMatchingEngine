package com.ome.book;

import com.ome.model.ExecType;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.OrderStatus;
import com.ome.model.OrderType;
import com.ome.model.Prices;
import com.ome.model.Side;
import com.ome.model.Trade;
import com.ome.support.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrderBook amend (replace) rules")
class ReplaceTest {

    private final Recorder events = new Recorder();
    private final OrderBook book = new OrderBook(new Instrument("TEST", 1), new SequentialIds(), events);
    private long nextId;

    @AfterEach
    void bookStaysConsistent() {
        book.verifyInvariants();
    }

    @Test
    @DisplayName("Same price, lower quantity: keeps its place in the queue")
    void reduceKeepsPriority() {
        long first = limit("a", Side.BUY, "100.00", 100);
        long second = limit("b", Side.BUY, "100.00", 100);

        assertTrue(book.replace(first, "a", px("100.00"), 40));
        assertEquals(ExecType.REPLACED, events.last(first).execType());
        assertEquals(40, events.last(first).leavesQty());

        limit("c", Side.SELL, "100.00", 50);
        assertEquals(List.of(first, second), buyers());
        assertEquals(List.of(40L, 10L), quantities());
    }

    @Test
    @DisplayName("Same price, higher quantity: goes to the back of the queue")
    void increaseLosesPriority() {
        long first = limit("a", Side.BUY, "100.00", 100);
        long second = limit("b", Side.BUY, "100.00", 100);

        assertTrue(book.replace(first, "a", px("100.00"), 150));
        limit("c", Side.SELL, "100.00", 100);

        assertEquals(List.of(second), buyers());
    }

    @Test
    @DisplayName("New price: joins the back of the queue at that price")
    void priceChangeLosesPriority() {
        long atHundred = limit("a", Side.BUY, "100.00", 100);
        long movedUp = limit("b", Side.BUY, "99.00", 100);

        assertTrue(book.replace(movedUp, "b", px("100.00"), 100));
        limit("c", Side.SELL, "100.00", 150);

        assertEquals(List.of(atHundred, movedUp), buyers());
        assertEquals(List.of(100L, 50L), quantities());
    }

    @Test
    @DisplayName("A new price that crosses trades immediately at the resting price")
    void replaceCrosses() {
        long buy = limit("a", Side.BUY, "99.00", 100);
        limit("b", Side.SELL, "101.00", 100);

        assertTrue(book.replace(buy, "a", px("102.00"), 100));

        assertEquals(List.of(ExecType.NEW, ExecType.REPLACED, ExecType.TRADE),
                events.reportsFor(buy).stream().map(ExecutionReport::execType).toList());
        assertEquals(px("101.00"), events.trades.get(0).price());
        assertEquals(OrderStatus.FILLED, events.last(buy).status());
        assertEquals(0, book.restingCount());
    }

    @Test
    @DisplayName("Amending a partly filled order changes only the open quantity")
    void replacePartiallyFilled() {
        long buy = limit("a", Side.BUY, "100.00", 100);
        limit("b", Side.SELL, "100.00", 30);

        assertTrue(book.replace(buy, "a", px("100.00"), 50));

        ExecutionReport last = events.last(buy);
        assertEquals(50, last.leavesQty());
        assertEquals(30, last.cumQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, last.status());
        assertEquals(50, book.snapshot(1).bestBid().quantity());
    }

    @Test
    @DisplayName("Unknown, filled or someone else's order: refused, nothing changes")
    void replaceRefused() {
        long buy = limit("a", Side.BUY, "100.00", 100);
        long filled = limit("a", Side.SELL, "105.00", 10);
        limit("b", Side.BUY, "105.00", 10);

        assertFalse(book.replace(buy, "intruder", px("101.00"), 10));
        assertFalse(book.replace(filled, "a", px("106.00"), 10));
        assertFalse(book.replace(12345, "a", px("100.00"), 10));
        assertEquals(px("100.00"), book.bestBid().getAsLong());
        assertEquals(100, book.snapshot(1).bestBid().quantity());
    }

    private long limit(String account, Side side, String price, long quantity) {
        long id = ++nextId;
        book.submit(id, account, side, OrderType.LIMIT, px(price), quantity);
        return id;
    }

    private List<Long> buyers() {
        return events.trades.stream().map(Trade::buyOrderId).toList();
    }

    private List<Long> quantities() {
        return events.trades.stream().map(Trade::quantity).toList();
    }

    private static long px(String price) {
        return Prices.parse(price);
    }
}
