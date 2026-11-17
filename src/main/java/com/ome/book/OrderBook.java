package com.ome.book;

import com.ome.model.ExecType;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.OrderStatus;
import com.ome.model.OrderType;
import com.ome.model.Reason;
import com.ome.model.Side;
import com.ome.model.Trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Limit order book for one instrument, matching with strict price-time priority.
 *
 * <p>Data structures:
 * <ul>
 *   <li>Each side is a {@code TreeMap<price, PriceLevel>} sorted best price first, so the best
 *       level is the first entry: O(log P) to add or remove a level, where P is the number of
 *       price levels.</li>
 *   <li>Each {@link PriceLevel} is an intrusive FIFO queue: time priority within a price.</li>
 *   <li>{@code resting} maps order id to order, so cancel and amend find an order in O(1) and
 *       unlink it in O(1).</li>
 * </ul>
 *
 * <p>Trades execute at the resting (maker) order's price. Every state change is published to
 * the {@link ExecutionListener} as an {@link ExecutionReport}, and every match as a {@link Trade}.
 *
 * <p>Not thread-safe: a book is owned by the engine's single thread. Inputs are assumed valid;
 * the engine validates them first.
 */
public final class OrderBook {

    private final Instrument instrument;
    private final String symbol;
    private final IdSource ids;
    private final ExecutionListener out;

    /** Bids, highest price first. */
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    /** Asks, lowest price first. */
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    /** Every resting order by id. */
    private final Map<Long, Order> resting = new HashMap<>();

    public OrderBook(Instrument instrument, IdSource ids, ExecutionListener out) {
        this.instrument = instrument;
        this.symbol = instrument.symbol();
        this.ids = ids;
        this.out = out;
    }

    public Instrument instrument() {
        return instrument;
    }

    // ---------------------------------------------------------------- commands

    /** Accepts a new, already validated order and matches it. */
    public void submit(long orderId, String account, Side side, OrderType type, long price, long quantity) {
        Order order = new Order(orderId, account, side, type, type.hasLimitPrice() ? price : 0, quantity);
        report(ExecType.NEW, order, 0, 0, 0, Reason.NONE);
        switch (type) {
            case LIMIT -> {
                match(order);
                if (order.leaves > 0) {
                    rest(order);
                }
            }
            case MARKET -> {
                match(order);
                if (order.leaves > 0) {
                    cancelRemainder(order, Reason.NO_LIQUIDITY);
                }
            }
            case IOC -> {
                match(order);
                if (order.leaves > 0) {
                    cancelRemainder(order, Reason.IOC_REMAINDER);
                }
            }
            case FOK -> {
                if (fillableQuantity(order) >= order.leaves) {
                    match(order);
                } else {
                    cancelRemainder(order, Reason.FOK_NOT_FILLABLE);
                }
            }
        }
    }

    /**
     * Cancels a resting order owned by {@code account}.
     *
     * @return false if no such order rests here for that account
     */
    public boolean cancel(long orderId, String account) {
        Order order = resting.get(orderId);
        if (order == null || !order.account.equals(account)) {
            return false;
        }
        unrest(order);
        cancelRemainder(order, Reason.CANCEL_REQUESTED);
        return true;
    }

    /**
     * Amends a resting order. Same price with an equal or lower quantity keeps queue priority;
     * anything else moves the order to the back of the queue at the new price, where it may trade.
     *
     * @return false if no such order rests here for that account
     */
    public boolean replace(long orderId, String account, long newPrice, long newQuantity) {
        Order order = resting.get(orderId);
        if (order == null || !order.account.equals(account)) {
            return false;
        }
        if (newPrice == order.price && newQuantity <= order.leaves) {
            order.level.reduce(order.leaves - newQuantity);
            order.leaves = newQuantity;
            report(ExecType.REPLACED, order, 0, 0, 0, Reason.NONE);
            return true;
        }
        unrest(order);
        order.price = newPrice;
        order.leaves = newQuantity;
        report(ExecType.REPLACED, order, 0, 0, 0, Reason.NONE);
        match(order);
        if (order.leaves > 0) {
            rest(order);
        }
        return true;
    }

    // ---------------------------------------------------------------- matching

    /** Trades {@code taker} against the opposite side, best price first, oldest order first. */
    private void match(Order taker) {
        TreeMap<Long, PriceLevel> opposite = taker.side == Side.BUY ? asks : bids;
        Iterator<PriceLevel> levels = opposite.values().iterator();
        while (taker.leaves > 0 && levels.hasNext()) {
            PriceLevel level = levels.next();
            if (taker.type != OrderType.MARKET && !crosses(taker, level.price)) {
                break;
            }
            Order maker;
            while (taker.leaves > 0 && (maker = level.head()) != null) {
                long qty = Math.min(taker.leaves, maker.leaves);
                level.reduce(qty);
                taker.fill(qty);
                maker.fill(qty);
                long tradeId = ids.nextTradeId();
                boolean takerBuys = taker.side == Side.BUY;
                out.onTrade(new Trade(tradeId, symbol, level.price, qty,
                        takerBuys ? taker.id : maker.id, takerBuys ? maker.id : taker.id, taker.side));
                report(ExecType.TRADE, taker, qty, level.price, tradeId, Reason.NONE);
                report(ExecType.TRADE, maker, qty, level.price, tradeId, Reason.NONE);
                if (maker.leaves == 0) {
                    level.remove(maker);
                    resting.remove(maker.id);
                }
            }
            if (level.isEmpty()) {
                levels.remove();
            }
        }
    }

    private static boolean crosses(Order taker, long levelPrice) {
        return taker.side == Side.BUY ? taker.price >= levelPrice : taker.price <= levelPrice;
    }

    /** Quantity a FOK order could trade right now, stopping once it is enough. */
    private long fillableQuantity(Order taker) {
        long available = 0;
        for (PriceLevel level : (taker.side == Side.BUY ? asks : bids).values()) {
            if (!crosses(taker, level.price)) {
                break;
            }
            available += level.totalQty();
            if (available >= taker.leaves) {
                break;
            }
        }
        return available;
    }

    private void rest(Order order) {
        TreeMap<Long, PriceLevel> side = order.side == Side.BUY ? bids : asks;
        PriceLevel level = side.get(order.price);
        if (level == null) {
            level = new PriceLevel(order.price);
            side.put(order.price, level);
        }
        level.append(order);
        resting.put(order.id, order);
    }

    private void unrest(Order order) {
        PriceLevel level = order.level;
        level.remove(order);
        if (level.isEmpty()) {
            (order.side == Side.BUY ? bids : asks).remove(level.price);
        }
        resting.remove(order.id);
    }

    private void cancelRemainder(Order order, Reason reason) {
        order.leaves = 0;
        order.status = OrderStatus.CANCELLED;
        report(ExecType.CANCELLED, order, 0, 0, 0, reason);
    }

    private void report(ExecType type, Order order, long lastQty, long lastPrice, long tradeId, Reason reason) {
        out.onReport(new ExecutionReport(ids.nextSeq(), type, order.id, order.account, symbol, order.side,
                order.type, order.price, order.leaves, order.cum, lastQty, lastPrice, tradeId, order.status,
                reason));
    }

    // ---------------------------------------------------------------- queries

    /** Top {@code depth} price levels on each side. */
    public BookSnapshot snapshot(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        }
        return new BookSnapshot(symbol, levels(bids, depth), levels(asks, depth));
    }

    private static List<BookSnapshot.Level> levels(TreeMap<Long, PriceLevel> side, int depth) {
        List<BookSnapshot.Level> levels = new ArrayList<>(Math.min(depth, side.size()));
        for (PriceLevel level : side.values()) {
            if (levels.size() == depth) {
                break;
            }
            levels.add(new BookSnapshot.Level(level.price, level.totalQty(), level.size()));
        }
        return levels;
    }

    /** Every resting order in priority order: bids best first, then asks best first. */
    public List<RestingOrder> restingOrders() {
        List<RestingOrder> orders = new ArrayList<>(resting.size());
        for (TreeMap<Long, PriceLevel> side : List.of(bids, asks)) {
            for (PriceLevel level : side.values()) {
                for (Order o = level.head(); o != null; o = o.next) {
                    orders.add(new RestingOrder(o.id, o.account, o.side, o.price, o.leaves, o.cum));
                }
            }
        }
        return orders;
    }

    public int restingCount() {
        return resting.size();
    }

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /**
     * Checks every structural invariant: levels sorted and non-empty, queue links intact,
     * level totals correct, the id index matching the queues, and the book not crossed.
     *
     * @throws IllegalStateException on the first violation
     */
    public void verifyInvariants() {
        int queued = 0;
        for (TreeMap<Long, PriceLevel> side : List.of(bids, asks)) {
            Side expected = side == bids ? Side.BUY : Side.SELL;
            for (Map.Entry<Long, PriceLevel> entry : side.entrySet()) {
                PriceLevel level = entry.getValue();
                PriceLevel.check(entry.getKey() == level.price, "level stored under the wrong price " + level.price);
                PriceLevel.check(!level.isEmpty(), "empty level left at " + level.price);
                level.verify();
                for (Order o = level.head(); o != null; o = o.next) {
                    PriceLevel.check(o.side == expected, "order " + o.id + " is on the wrong side");
                    PriceLevel.check(resting.get(o.id) == o, "order " + o.id + " missing from the index");
                    queued++;
                }
            }
        }
        PriceLevel.check(queued == resting.size(),
                "index has " + resting.size() + " orders but queues hold " + queued);
        if (!bids.isEmpty() && !asks.isEmpty()) {
            PriceLevel.check(bids.firstKey() < asks.firstKey(),
                    "crossed book: bid " + bids.firstKey() + " >= ask " + asks.firstKey());
        }
    }
}
