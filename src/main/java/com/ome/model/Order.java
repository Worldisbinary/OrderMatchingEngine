package com.ome.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single order in the matching engine.
 *
 * Key design choices:
 *  - orderId is a monotonically increasing long for fast comparison & logging
 *  - timestamp is nanosecond-precision for correct time-priority within a price level
 *  - filledQuantity tracked separately so we always know original intent
 *  - price is double; in production you'd use a fixed-point long (e.g. price * 10000)
 *    to avoid floating-point rounding — noted for interviewer awareness
 */
public class Order {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long        orderId;
    private final String      symbol;
    private final Side        side;
    private final OrderType   type;
    private final double      price;            // limit price (0 for MARKET)
    private final int         originalQuantity;
    private       int         remainingQuantity;
    private       int         filledQuantity;
    private       OrderStatus status;
    private final long        timestamp;        // nanoseconds — for time priority

    public Order(String symbol, Side side, OrderType type, double price, int quantity) {
        validateOrder(symbol, side, type, price, quantity);

        this.orderId           = ID_GENERATOR.getAndIncrement();
        this.symbol            = symbol.toUpperCase();
        this.side              = side;
        this.type              = type;
        this.price             = price;
        this.originalQuantity  = quantity;
        this.remainingQuantity = quantity;
        this.filledQuantity    = 0;
        this.status            = OrderStatus.NEW;
        this.timestamp         = System.nanoTime();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validateOrder(String symbol, Side side, OrderType type,
                                       double price, int quantity) {
        if (symbol == null || symbol.isBlank())
            throw new IllegalArgumentException("Symbol cannot be blank.");
        if (side == null)
            throw new IllegalArgumentException("Side cannot be null.");
        if (type == null)
            throw new IllegalArgumentException("OrderType cannot be null.");
        if (quantity <= 0)
            throw new IllegalArgumentException("Quantity must be positive. Got: " + quantity);
        if (type == OrderType.LIMIT && price <= 0)
            throw new IllegalArgumentException("LIMIT order must have a positive price. Got: " + price);
        if (type == OrderType.IOC && price <= 0)
            throw new IllegalArgumentException("IOC order must have a positive price. Got: " + price);
        if (type == OrderType.FOC && price <= 0)
            throw new IllegalArgumentException("FOC order must have a positive price. Got: " + price);
    }

    // ── State Mutations (package-private: only engine should mutate) ──────────

    /**
     * Fill a portion of this order.
     * @param qty quantity being filled in this execution
     */
    public void fill(int qty) {
        if (qty <= 0 || qty > remainingQuantity)
            throw new IllegalArgumentException(
                "Invalid fill qty " + qty + " for order with remaining " + remainingQuantity);

        remainingQuantity -= qty;
        filledQuantity    += qty;
        status = (remainingQuantity == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        if (status == OrderStatus.FILLED)
            throw new IllegalStateException("Cannot cancel a fully filled order.");
        this.status = OrderStatus.CANCELLED;
    }

    public void markOpen() {
        this.status = OrderStatus.OPEN;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public long        getOrderId()            { return orderId; }
    public String      getSymbol()             { return symbol; }
    public Side        getSide()               { return side; }
    public OrderType   getType()               { return type; }
    public double      getPrice()              { return price; }
    public int         getOriginalQuantity()   { return originalQuantity; }
    public int         getRemainingQuantity()  { return remainingQuantity; }
    public int         getFilledQuantity()     { return filledQuantity; }
    public OrderStatus getStatus()             { return status; }
    public long        getTimestamp()          { return timestamp; }

    public boolean isFilled()    { return status == OrderStatus.FILLED; }
    public boolean isCancelled() { return status == OrderStatus.CANCELLED; }
    public boolean isActive()    { return status == OrderStatus.OPEN
                                       || status == OrderStatus.PARTIALLY_FILLED
                                       || status == OrderStatus.NEW; }

    // ── Display ───────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format(
            "Order#%04d [%s | %s | %s | Price: %s | Qty: %d/%d | Status: %s]",
            orderId, symbol, side, type,
            type == OrderType.MARKET ? "MARKET" : String.format("%.2f", price),
            remainingQuantity, originalQuantity, status
        );
    }
}
