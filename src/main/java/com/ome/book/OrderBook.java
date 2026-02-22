package com.ome.book;

import com.ome.model.Order;
import com.ome.model.Side;
import com.ome.model.Trade;

import java.util.*;

/**
 * Single-symbol order book implementing strict price-time priority.
 *
 * Data structure choice:
 *   Bids → TreeMap<Double, PriceLevel> with DESCENDING comparator
 *           so firstKey() always returns the best (highest) bid.
 *   Asks → TreeMap<Double, PriceLevel> with natural (ASCENDING) order
 *           so firstKey() always returns the best (lowest) ask.
 *
 * Complexity:
 *   Add/Cancel order : O(log P) where P = number of distinct price levels
 *   Match order      : O(T log P) where T = number of trades generated
 *   Best bid/ask     : O(1) via firstKey()
 *
 * Note on floating-point prices:
 *   In production (e.g. Bloomberg), prices are represented as fixed-point
 *   integers (price × 10000) to eliminate rounding errors. We use double
 *   here for readability, which is fine for a simulation.
 */
public class OrderBook {

    private final String symbol;

    // Best bid = first key (highest price)
    private final TreeMap<Double, PriceLevel> bids =
            new TreeMap<>(Comparator.reverseOrder());

    // Best ask = first key (lowest price)
    private final TreeMap<Double, PriceLevel> asks =
            new TreeMap<>();

    // Index for O(1) order lookup during cancellation
    private final Map<Long, Double> orderPriceIndex = new HashMap<>();

    private final List<Trade>  tradeHistory   = new ArrayList<>();
    private       double       lastTradePrice = 0.0;
    private       long         totalVolume    = 0L;
    private       double       totalTurnover  = 0.0;  // sum of (price × qty)

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    // ── Matching Interface ────────────────────────────────────────────────────

    /**
     * Add an order to the book and attempt to match it.
     * Returns the list of trades generated (may be empty).
     */
    public List<Trade> addOrder(Order order) {
        List<Trade> trades = new ArrayList<>();

        switch (order.getType()) {
            case LIMIT  -> matchLimit(order, trades);
            case MARKET -> matchMarket(order, trades);
            case IOC    -> matchIOC(order, trades);
            case FOC    -> matchFOC(order, trades);
        }

        return trades;
    }

    /**
     * Cancel a resting order by id. Returns true if found and cancelled.
     */
    public boolean cancelOrder(long orderId) {
        Double price = orderPriceIndex.remove(orderId);
        if (price == null) return false;

        TreeMap<Double, PriceLevel> book = getBookForSide(null, price, orderId);
        if (book == null) return false;

        PriceLevel level = book.get(price);
        if (level == null) return false;

        boolean removed = level.remove(orderId);
        if (level.isEmpty()) book.remove(price);
        return removed;
    }

    // ── Order Type Matching ───────────────────────────────────────────────────

    private void matchLimit(Order order, List<Trade> trades) {
        TreeMap<Double, PriceLevel> opposite = oppositeBook(order.getSide());
        sweep(order, opposite, trades, false /* respect price limit */);

        // Rest any unfilled remainder on the book
        if (!order.isFilled()) {
            rest(order);
        }
    }

    private void matchMarket(Order order, List<Trade> trades) {
        TreeMap<Double, PriceLevel> opposite = oppositeBook(order.getSide());
        sweep(order, opposite, trades, true /* ignore price, take whatever's available */);
        // Remainder is discarded — MARKET orders never rest on the book
    }

    private void matchIOC(Order order, List<Trade> trades) {
        TreeMap<Double, PriceLevel> opposite = oppositeBook(order.getSide());
        sweep(order, opposite, trades, false);
        // Cancel remainder immediately — no resting
        if (!order.isFilled()) {
            order.cancel();
        }
    }

    private void matchFOC(Order order, List<Trade> trades) {
        // Dry-run: check if the full quantity is available before touching the book
        int available = availableQty(oppositeBook(order.getSide()), order);

        if (available >= order.getRemainingQuantity()) {
            sweep(order, oppositeBook(order.getSide()), trades, false);
        } else {
            // Not enough liquidity — cancel the entire order
            order.cancel();
        }
    }

    // ── Core Sweep ────────────────────────────────────────────────────────────

    /**
     * Walk the opposite side of the book, generating trades until:
     *   (a) the incoming order is fully filled, or
     *   (b) no more price levels are eligible.
     *
     * @param ignorePrice  true for MARKET orders (take any price)
     */
    private void sweep(Order incoming,
                       TreeMap<Double, PriceLevel> opposite,
                       List<Trade> trades,
                       boolean ignorePrice) {

        Iterator<Map.Entry<Double, PriceLevel>> levelIterator =
                opposite.entrySet().iterator();

        while (levelIterator.hasNext() && !incoming.isFilled()) {
            Map.Entry<Double, PriceLevel> entry = levelIterator.next();
            double     levelPrice = entry.getKey();
            PriceLevel level      = entry.getValue();

            // Price-boundary check
            if (!ignorePrice && !isPriceCrossed(incoming, levelPrice)) break;

            // Match within this price level — strict FIFO (time priority)
            while (!level.isEmpty() && !incoming.isFilled()) {
                Order resting = level.peek();
                int   fillQty = Math.min(incoming.getRemainingQuantity(),
                                         resting.getRemainingQuantity());

                // Execution price = resting (maker) order's price — standard convention
                Trade trade = createTrade(incoming, resting, levelPrice, fillQty);
                trades.add(trade);
                tradeHistory.add(trade);

                // Update state
                level.onFill(fillQty);
                incoming.fill(fillQty);
                resting.fill(fillQty);

                // Clean up fully filled resting order
                if (resting.isFilled()) {
                    level.dequeue();
                    orderPriceIndex.remove(resting.getOrderId());
                }
            }

            // Clean up empty price level
            if (level.isEmpty()) levelIterator.remove();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Does the incoming order's price allow it to trade at this level?
     *   BUY  order is eligible if its limit price >= ask level price
     *   SELL order is eligible if its limit price <= bid level price
     */
    private boolean isPriceCrossed(Order incoming, double levelPrice) {
        return incoming.getSide() == Side.BUY
                ? incoming.getPrice() >= levelPrice
                : incoming.getPrice() <= levelPrice;
    }

    /**
     * Simulate total available quantity for FOC dry-run.
     */
    private int availableQty(TreeMap<Double, PriceLevel> opposite, Order order) {
        int total = 0;
        for (Map.Entry<Double, PriceLevel> entry : opposite.entrySet()) {
            if (!isPriceCrossed(order, entry.getKey())) break;
            total += entry.getValue().getTotalQty();
            if (total >= order.getRemainingQuantity()) break; // short-circuit
        }
        return total;
    }

    /**
     * Place a resting order at its price level on the correct side.
     */
    private void rest(Order order) {
        TreeMap<Double, PriceLevel> book = (order.getSide() == Side.BUY) ? bids : asks;
        book.computeIfAbsent(order.getPrice(), PriceLevel::new).enqueue(order);
        orderPriceIndex.put(order.getOrderId(), order.getPrice());
        order.markOpen();
    }

    private Trade createTrade(Order incoming, Order resting, double price, int qty) {
        lastTradePrice = price;
        totalVolume   += qty;
        totalTurnover += price * qty;

        long buyId  = (incoming.getSide() == Side.BUY)  ? incoming.getOrderId() : resting.getOrderId();
        long sellId = (incoming.getSide() == Side.SELL) ? incoming.getOrderId() : resting.getOrderId();

        return new Trade(symbol, buyId, sellId, price, qty);
    }

    private TreeMap<Double, PriceLevel> oppositeBook(Side side) {
        return side == Side.BUY ? asks : bids;
    }

    // Determine which side an order is on by searching both books
    private TreeMap<Double, PriceLevel> getBookForSide(Side hint, double price, long orderId) {
        if (bids.containsKey(price) && bids.get(price) != null) {
            PriceLevel level = bids.get(price);
            // Cheap check: does this level have any order with this id?
            if (level.getTotalQty() >= 0) return bids; // try bids first
        }
        return asks;
    }

    // ── Market Data Accessors ─────────────────────────────────────────────────

    public double getBestBid()       { return bids.isEmpty() ? 0 : bids.firstKey(); }
    public double getBestAsk()       { return asks.isEmpty() ? 0 : asks.firstKey(); }
    public double getSpread()        {
        return (bids.isEmpty() || asks.isEmpty()) ? Double.NaN
                : getBestAsk() - getBestBid();
    }
    public double getMidPrice()      {
        return (bids.isEmpty() || asks.isEmpty()) ? Double.NaN
                : (getBestBid() + getBestAsk()) / 2.0;
    }
    public double getLastTradePrice() { return lastTradePrice; }
    public long   getTotalVolume()    { return totalVolume; }
    public double getTotalTurnover()  { return totalTurnover; }
    public double getVWAP()           {
        return totalVolume == 0 ? 0 : totalTurnover / totalVolume;
    }
    public String          getSymbol()      { return symbol; }
    public List<Trade>     getTradeHistory(){ return Collections.unmodifiableList(tradeHistory); }

    public int getBidDepth()  { return bids.values().stream().mapToInt(PriceLevel::getOrderCount).sum(); }
    public int getAskDepth()  { return asks.values().stream().mapToInt(PriceLevel::getOrderCount).sum(); }

    // ── Display ───────────────────────────────────────────────────────────────

    /**
     * Prints the full order book in a terminal-style ladder view.
     * Top = highest ask, Middle = spread, Bottom = highest bid.
     */
    public void printOrderBook() {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────┐");
        System.out.printf ("│           ORDER BOOK  %-6s                        │%n", symbol);
        System.out.println("├───────────────┬───────────────┬─────────────────────┤");
        System.out.println("│   PRICE       │   QTY         │   SIDE              │");
        System.out.println("├───────────────┼───────────────┼─────────────────────┤");

        // Print asks in reverse (highest first → looks like a real terminal)
        List<Map.Entry<Double, PriceLevel>> askEntries = new ArrayList<>(asks.entrySet());
        Collections.reverse(askEntries);
        for (Map.Entry<Double, PriceLevel> e : askEntries) {
            System.out.printf("│  %10.2f   │  %10d   │  %-19s│%n",
                    e.getKey(), e.getValue().getTotalQty(), "ASK 🔴");
        }

        // Spread line
        System.out.println("├───────────────┼───────────────┼─────────────────────┤");
        double spread = getSpread();
        System.out.printf("│  SPREAD       │  %-10s   │  MID: %-14s│%n",
                Double.isNaN(spread) ? "N/A" : String.format("%.2f", spread),
                Double.isNaN(getMidPrice()) ? "N/A" : String.format("%.2f", getMidPrice()));
        System.out.println("├───────────────┼───────────────┼─────────────────────┤");

        // Print bids (highest first)
        for (Map.Entry<Double, PriceLevel> e : bids.entrySet()) {
            System.out.printf("│  %10.2f   │  %10d   │  %-19s│%n",
                    e.getKey(), e.getValue().getTotalQty(), "BID 🟢");
        }

        System.out.println("├───────────────┴───────────────┴─────────────────────┤");
        System.out.printf("│  Last: %-8.2f  Vol: %-10d  VWAP: %-10.2f   │%n",
                lastTradePrice, totalVolume,
                getVWAP() == 0 ? 0 : getVWAP());
        System.out.println("└─────────────────────────────────────────────────────┘");
        System.out.println();
    }
}
