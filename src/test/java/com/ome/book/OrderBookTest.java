package com.ome.book;

import com.ome.model.ExecType;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.OrderStatus;
import com.ome.model.OrderType;
import com.ome.model.Prices;
import com.ome.model.Reason;
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

@DisplayName("OrderBook matching")
class OrderBookTest {

    private final Recorder events = new Recorder();
    private final OrderBook book = new OrderBook(new Instrument("TEST", 1), new SequentialIds(), events);
    private long nextId;

    @AfterEach
    void bookStaysConsistent() {
        book.verifyInvariants();
    }

    // ---------------------------------------------------------------- LIMIT

    @Test
    @DisplayName("LIMIT: rests when nothing crosses")
    void limitRestsOnEmptyBook() {
        long buy = order("a", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertTrue(events.trades.isEmpty());
        assertEquals(OrderStatus.OPEN, events.last(buy).status());
        assertEquals(Prices.parse("100.00"), book.bestBid().getAsLong());
        assertEquals(1, book.restingCount());
    }

    @Test
    @DisplayName("LIMIT: equal prices cross and both orders fill")
    void limitExactFill() {
        long sell = order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        long buy = order("b", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertTrades("100.00 x 100");
        assertEquals(OrderStatus.FILLED, events.last(sell).status());
        assertEquals(OrderStatus.FILLED, events.last(buy).status());
        assertEquals(0, book.restingCount());
    }

    @Test
    @DisplayName("LIMIT: partial fill rests the remainder as PARTIALLY_FILLED")
    void limitPartialFillRestsRemainder() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 50);
        long buy = order("b", Side.BUY, OrderType.LIMIT, "100.00", 150);

        assertTrades("100.00 x 50");
        ExecutionReport last = events.last(buy);
        assertEquals(OrderStatus.PARTIALLY_FILLED, last.status());
        assertEquals(100, last.leavesQty());
        assertEquals(50, last.cumQty());
        assertEquals(List.of(new BookSnapshot.Level(Prices.parse("100.00"), 100, 1)), book.snapshot(5).bids());
    }

    @Test
    @DisplayName("LIMIT: trades at the resting (maker) price, not the incoming price")
    void executesAtMakerPrice() {
        order("a", Side.SELL, OrderType.LIMIT, "99.00", 100);
        order("b", Side.BUY, OrderType.LIMIT, "101.00", 100);

        assertTrades("99.00 x 100");
    }

    @Test
    @DisplayName("LIMIT: earlier order at the same price fills first")
    void timePriority() {
        long first = order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("b", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("c", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertEquals(first, events.trades.get(0).sellOrderId());
        assertEquals(1, book.restingCount());
    }

    @Test
    @DisplayName("LIMIT: better price fills first even if it arrived later")
    void pricePriority() {
        order("a", Side.SELL, OrderType.LIMIT, "101.00", 100);
        long better = order("b", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("c", Side.BUY, OrderType.LIMIT, "101.00", 100);

        assertEquals(better, events.trades.get(0).sellOrderId());
        assertTrades("100.00 x 100");
    }

    @Test
    @DisplayName("LIMIT: sweeps several levels and rests nothing once filled")
    void sweepsLevels() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("a", Side.SELL, OrderType.LIMIT, "101.00", 100);
        order("a", Side.SELL, OrderType.LIMIT, "102.00", 100);
        long buy = order("b", Side.BUY, OrderType.LIMIT, "102.00", 250);

        assertTrades("100.00 x 100", "101.00 x 100", "102.00 x 50");
        assertEquals(OrderStatus.FILLED, events.last(buy).status());
        assertEquals(List.of(new BookSnapshot.Level(Prices.parse("102.00"), 50, 1)), book.snapshot(5).asks());
        assertTrue(book.bestBid().isEmpty());
    }

    @Test
    @DisplayName("LIMIT: a sell sweeps bids from the highest down")
    void sellSweepsBids() {
        order("a", Side.BUY, OrderType.LIMIT, "99.00", 100);
        order("a", Side.BUY, OrderType.LIMIT, "100.00", 100);
        order("b", Side.SELL, OrderType.LIMIT, "99.00", 150);

        assertTrades("100.00 x 100", "99.00 x 50");
        assertEquals(Side.SELL, events.trades.get(0).aggressorSide());
    }

    @Test
    @DisplayName("LIMIT: a buy below the best ask does not trade")
    void noCrossNoTrade() {
        order("a", Side.SELL, OrderType.LIMIT, "100.05", 100);
        order("b", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertTrue(events.trades.isEmpty());
        assertEquals(2, book.restingCount());
    }

    // ---------------------------------------------------------------- MARKET, IOC, FOK

    @Test
    @DisplayName("MARKET: takes any price and never rests")
    void marketSweeps() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("a", Side.SELL, OrderType.LIMIT, "105.00", 100);
        long buy = order("b", Side.BUY, OrderType.MARKET, null, 150);

        assertTrades("100.00 x 100", "105.00 x 50");
        assertEquals(OrderStatus.FILLED, events.last(buy).status());
        assertTrue(book.bestBid().isEmpty());
    }

    @Test
    @DisplayName("MARKET: remainder is cancelled with NO_LIQUIDITY")
    void marketWithoutLiquidity() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 30);
        long buy = order("b", Side.BUY, OrderType.MARKET, null, 100);

        ExecutionReport last = events.last(buy);
        assertEquals(ExecType.CANCELLED, last.execType());
        assertEquals(Reason.NO_LIQUIDITY, last.reason());
        assertEquals(30, last.cumQty());
        assertEquals(0, last.leavesQty());
        assertEquals(0, book.restingCount());
    }

    @Test
    @DisplayName("IOC: fills what it can, cancels the rest")
    void iocPartial() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 50);
        long ioc = order("b", Side.BUY, OrderType.IOC, "100.00", 100);

        assertTrades("100.00 x 50");
        ExecutionReport last = events.last(ioc);
        assertEquals(OrderStatus.CANCELLED, last.status());
        assertEquals(Reason.IOC_REMAINDER, last.reason());
        assertEquals(50, last.cumQty());
        assertEquals(0, book.restingCount());
    }

    @Test
    @DisplayName("IOC: nothing to match means a full cancel and nothing on the book")
    void iocNoMatch() {
        order("a", Side.SELL, OrderType.LIMIT, "101.00", 50);
        long ioc = order("b", Side.BUY, OrderType.IOC, "100.00", 100);

        assertTrue(events.trades.isEmpty());
        assertEquals(OrderStatus.CANCELLED, events.last(ioc).status());
        assertTrue(book.bestBid().isEmpty());
    }

    @Test
    @DisplayName("FOK: fills completely across levels when enough is there")
    void fokFills() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("a", Side.SELL, OrderType.LIMIT, "101.00", 100);
        long fok = order("b", Side.BUY, OrderType.FOK, "101.00", 150);

        assertTrades("100.00 x 100", "101.00 x 50");
        assertEquals(OrderStatus.FILLED, events.last(fok).status());
    }

    @Test
    @DisplayName("FOK: not enough liquidity means no trades and an untouched book")
    void fokKilled() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        long fok = order("b", Side.BUY, OrderType.FOK, "100.00", 150);

        assertTrue(events.trades.isEmpty());
        assertEquals(Reason.FOK_NOT_FILLABLE, events.last(fok).reason());
        assertEquals(List.of(new BookSnapshot.Level(Prices.parse("100.00"), 100, 1)), book.snapshot(5).asks());
    }

    @Test
    @DisplayName("FOK: liquidity beyond the limit price does not count")
    void fokIgnoresLiquidityPastLimit() {
        order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        order("a", Side.SELL, OrderType.LIMIT, "102.00", 100);
        long fok = order("b", Side.BUY, OrderType.FOK, "101.00", 150);

        assertTrue(events.trades.isEmpty());
        assertEquals(OrderStatus.CANCELLED, events.last(fok).status());
    }

    // ---------------------------------------------------------------- cancel

    @Test
    @DisplayName("Cancel: removes the order and reports CANCEL_REQUESTED")
    void cancel() {
        long buy = order("a", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertTrue(book.cancel(buy, "a"));
        ExecutionReport last = events.last(buy);
        assertEquals(ExecType.CANCELLED, last.execType());
        assertEquals(Reason.CANCEL_REQUESTED, last.reason());
        assertEquals(0, book.restingCount());
        assertFalse(book.cancel(buy, "a"), "second cancel of the same order");
    }

    @Test
    @DisplayName("Cancel: removing from the middle of a queue keeps the others in order")
    void cancelMiddleOfQueue() {
        long first = order("a", Side.SELL, OrderType.LIMIT, "100.00", 50);
        long middle = order("b", Side.SELL, OrderType.LIMIT, "100.00", 50);
        long last = order("c", Side.SELL, OrderType.LIMIT, "100.00", 50);

        assertTrue(book.cancel(middle, "b"));
        order("d", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertEquals(List.of(first, last), events.trades.stream().map(Trade::sellOrderId).toList());
    }

    @Test
    @DisplayName("Cancel: only the owning account can cancel")
    void cancelNeedsOwner() {
        long buy = order("a", Side.BUY, OrderType.LIMIT, "100.00", 100);

        assertFalse(book.cancel(buy, "intruder"));
        assertFalse(book.cancel(999, "a"));
        assertEquals(1, book.restingCount());
    }

    // ---------------------------------------------------------------- reports and depth

    @Test
    @DisplayName("Reports: NEW first, then one TRADE per side per fill, sequence numbers increasing")
    void reportShape() {
        long sell = order("a", Side.SELL, OrderType.LIMIT, "100.00", 100);
        long buy = order("b", Side.BUY, OrderType.LIMIT, "100.00", 40);

        assertEquals(List.of(ExecType.NEW, ExecType.TRADE),
                events.reportsFor(buy).stream().map(ExecutionReport::execType).toList());
        ExecutionReport makerFill = events.last(sell);
        assertEquals(40, makerFill.lastQty());
        assertEquals(Prices.parse("100.00"), makerFill.lastPrice());
        assertEquals(events.trades.get(0).tradeId(), makerFill.tradeId());
        for (int i = 1; i < events.reports.size(); i++) {
            assertTrue(events.reports.get(i).seq() > events.reports.get(i - 1).seq());
        }
    }

    @Test
    @DisplayName("Snapshot: aggregates levels best first and respects depth")
    void snapshotDepth() {
        order("a", Side.BUY, OrderType.LIMIT, "99.00", 10);
        order("a", Side.BUY, OrderType.LIMIT, "100.00", 20);
        order("b", Side.BUY, OrderType.LIMIT, "100.00", 30);
        order("a", Side.BUY, OrderType.LIMIT, "98.00", 40);
        order("a", Side.SELL, OrderType.LIMIT, "101.00", 5);

        BookSnapshot top2 = book.snapshot(2);
        assertEquals(List.of(new BookSnapshot.Level(Prices.parse("100.00"), 50, 2),
                new BookSnapshot.Level(Prices.parse("99.00"), 10, 1)), top2.bids());
        assertEquals(Prices.parse("101.00"), top2.bestAsk().price());
        assertEquals(3, book.snapshot(10).bids().size(), "levels 100.00, 99.00 and 98.00");
    }

    // ---------------------------------------------------------------- helpers

    private long order(String account, Side side, OrderType type, String price, long quantity) {
        long id = ++nextId;
        book.submit(id, account, side, type, price == null ? 0 : Prices.parse(price), quantity);
        return id;
    }

    private void assertTrades(String... expected) {
        List<String> actual = events.trades.stream()
                .map(t -> Prices.format(t.price()) + " x " + t.quantity())
                .toList();
        assertEquals(List.of(expected), actual);
    }
}
