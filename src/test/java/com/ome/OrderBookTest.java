package com.ome;

import com.ome.book.OrderBook;
import com.ome.model.*;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the OrderBook matching logic.
 * Tests each order type, edge cases, and price-time priority.
 */
@DisplayName("OrderBook Matching Engine Tests")
class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook("TEST");
    }

    // ── LIMIT Orders ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("LIMIT: no match when book is empty")
    void limitOrder_noMatch_emptyBook() {
        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 100.0, 100);
        List<Trade> trades = book.addOrder(buy);

        assertTrue(trades.isEmpty());
        assertEquals(OrderStatus.OPEN, buy.getStatus());
        assertEquals(100, buy.getRemainingQuantity());
    }

    @Test
    @DisplayName("LIMIT: exact fill when prices cross")
    void limitOrder_exactFill() {
        Order sell = new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 100);
        book.addOrder(sell);

        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 100.0, 100);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(100, trades.get(0).getQuantity());
        assertEquals(100.0, trades.get(0).getExecutionPrice());
        assertTrue(buy.isFilled());
        assertTrue(sell.isFilled());
    }

    @Test
    @DisplayName("LIMIT: partial fill — remainder rests on book")
    void limitOrder_partialFill_restOnBook() {
        Order sell = new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 50);
        book.addOrder(sell);

        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 100.0, 150);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(50, trades.get(0).getQuantity());
        assertEquals(100, buy.getRemainingQuantity());     // 150 - 50 = 100 remaining
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(100.0, book.getBestBid());            // remainder rests on bid side
    }

    @Test
    @DisplayName("LIMIT: execution at maker (resting) price, not taker price")
    void limitOrder_executionAtMakerPrice() {
        // Sell rests at 100.00
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 100));

        // Buy comes in at 101.00 (willing to pay more) — should execute at 100.00
        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 101.0, 100);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(100.0, trades.get(0).getExecutionPrice(), "Should execute at maker price");
    }

    @Test
    @DisplayName("LIMIT: price-time priority — earlier order at same price fills first")
    void limitOrder_timePriority() {
        Order sell1 = new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 50); // arrives first
        Order sell2 = new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 50); // arrives second
        book.addOrder(sell1);
        book.addOrder(sell2);

        // Buy enough to fill sell1 exactly
        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 100.0, 50);
        book.addOrder(buy);

        assertTrue(sell1.isFilled(),      "sell1 should be filled first (time priority)");
        assertFalse(sell2.isFilled(),     "sell2 should still be open");
    }

    @Test
    @DisplayName("LIMIT: sweep multiple price levels")
    void limitOrder_sweepMultipleLevels() {
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 100));
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 101.0, 100));
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 102.0, 100));

        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 101.0, 200);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(2, trades.size(),  "Should match at 100 and 101, not 102");
        assertTrue(buy.isFilled());
    }

    // ── MARKET Orders ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("MARKET: fills at whatever price is available")
    void marketOrder_fillsAtAnyPrice() {
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 105.0, 200));

        Order buy = new Order("TEST", Side.BUY, OrderType.MARKET, 0, 100);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(105.0, trades.get(0).getExecutionPrice());
        assertTrue(buy.isFilled());
    }

    @Test
    @DisplayName("MARKET: partial fill when book has insufficient liquidity")
    void marketOrder_partialFill_insufficientLiquidity() {
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 50));

        Order buy = new Order("TEST", Side.BUY, OrderType.MARKET, 0, 200);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(50, trades.get(0).getQuantity());
        assertEquals(150, buy.getRemainingQuantity()); // 150 unfilled, discarded
    }

    // ── IOC Orders ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IOC: partial fill, remainder cancelled — never rests on book")
    void iocOrder_partialFill_cancelRemainder() {
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 60));

        Order ioc = new Order("TEST", Side.BUY, OrderType.IOC, 100.0, 200);
        List<Trade> trades = book.addOrder(ioc);

        assertEquals(1, trades.size());
        assertEquals(60, trades.get(0).getQuantity());
        assertEquals(OrderStatus.CANCELLED, ioc.getStatus());
        assertEquals(0.0, book.getBestBid(), "IOC remainder must NOT rest on book");
    }

    @Test
    @DisplayName("IOC: no match at all → full cancel, nothing on book")
    void iocOrder_noMatch_fullCancel() {
        // Book is empty or price doesn't cross
        Order ioc = new Order("TEST", Side.BUY, OrderType.IOC, 99.0, 100);
        List<Trade> trades = book.addOrder(ioc);

        assertTrue(trades.isEmpty());
        assertEquals(OrderStatus.CANCELLED, ioc.getStatus());
    }

    // ── FOC Orders ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FOC: full fill when sufficient liquidity exists")
    void focOrder_fullFill() {
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 300));

        Order foc = new Order("TEST", Side.BUY, OrderType.FOC, 100.0, 300);
        List<Trade> trades = book.addOrder(foc);

        assertEquals(1, trades.size());
        assertTrue(foc.isFilled());
    }

    @Test
    @DisplayName("FOC: cancelled when insufficient liquidity — book untouched")
    void focOrder_cancelled_insufficientLiquidity() {
        Order sell = new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 50);
        book.addOrder(sell);

        Order foc = new Order("TEST", Side.BUY, OrderType.FOC, 100.0, 200);
        List<Trade> trades = book.addOrder(foc);

        assertTrue(trades.isEmpty(),                   "No trades should be generated");
        assertEquals(OrderStatus.CANCELLED, foc.getStatus());
        assertEquals(50, sell.getRemainingQuantity(),  "Resting sell must remain untouched");
        assertEquals(100.0, book.getBestAsk(),         "Ask side must be unchanged");
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Market data: spread, mid-price, VWAP calculated correctly")
    void marketData_correctCalculations() {
        book.addOrder(new Order("TEST", Side.BUY,  OrderType.LIMIT, 99.0, 100));
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 101.0, 100));

        assertEquals(99.0,  book.getBestBid(),  0.001);
        assertEquals(101.0, book.getBestAsk(),  0.001);
        assertEquals(2.0,   book.getSpread(),   0.001);
        assertEquals(100.0, book.getMidPrice(), 0.001);
    }

    @Test
    @DisplayName("VWAP: weighted average over multiple trades")
    void marketData_vwap() {
        // Two sells at different prices
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 100.0, 100));
        book.addOrder(new Order("TEST", Side.SELL, OrderType.LIMIT, 102.0, 100));

        // Buy sweeps both levels
        book.addOrder(new Order("TEST", Side.BUY, OrderType.LIMIT, 102.0, 200));

        // VWAP = (100*100 + 102*100) / 200 = 101.0
        assertEquals(101.0, book.getVWAP(), 0.001, "VWAP should be 101.0");
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cancel: successfully removes resting order from book")
    void cancel_removesRestingOrder() {
        Order buy = new Order("TEST", Side.BUY, OrderType.LIMIT, 99.0, 100);
        book.addOrder(buy);

        boolean cancelled = book.cancelOrder(buy.getOrderId());

        assertTrue(cancelled);
        assertEquals(0.0, book.getBestBid(), "Book should be empty after cancel");
    }

    @Test
    @DisplayName("Cancel: returns false for unknown order id")
    void cancel_unknownOrderId() {
        boolean result = book.cancelOrder(99999L);
        assertFalse(result);
    }

    // ── Order Validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Validation: LIMIT order with non-positive price is rejected")
    void validation_limitOrderZeroPrice_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Order("TEST", Side.BUY, OrderType.LIMIT, 0, 100));
    }

    @Test
    @DisplayName("Validation: non-positive quantity is rejected")
    void validation_zeroQuantity_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Order("TEST", Side.BUY, OrderType.LIMIT, 100.0, 0));
    }
}
