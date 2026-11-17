package com.ome.book;

import com.ome.model.OrderType;
import com.ome.model.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deliberately naive order book used as a test oracle: one flat list, best order found by a
 * full scan every time. It is slow but short enough to check by eye, and it shares no code
 * with {@link OrderBook}.
 */
final class ReferenceBook {

    private static final class Ref {
        final long id;
        final String account;
        final Side side;
        long price;
        long leaves;
        long cum;
        long priority;

        Ref(long id, String account, Side side, long price, long quantity) {
            this.id = id;
            this.account = account;
            this.side = side;
            this.price = price;
            this.leaves = quantity;
        }
    }

    private final List<Ref> resting = new ArrayList<>();
    private long arrival;

    /** Returns the trades as "price x qty buy=.. sell=..". */
    List<String> submit(long id, String account, Side side, OrderType type, long price, long quantity) {
        Ref order = new Ref(id, account, side, type == OrderType.MARKET ? 0 : price, quantity);
        List<String> trades = new ArrayList<>();
        if (type == OrderType.FOK && available(order, type) < quantity) {
            return trades;
        }
        match(order, type, trades);
        if (type == OrderType.LIMIT && order.leaves > 0) {
            rest(order);
        }
        return trades;
    }

    boolean cancel(long id, String account) {
        Ref order = find(id, account);
        if (order == null) {
            return false;
        }
        resting.remove(order);
        return true;
    }

    List<String> replace(long id, String account, long newPrice, long newQuantity, boolean[] accepted) {
        List<String> trades = new ArrayList<>();
        Ref order = find(id, account);
        accepted[0] = order != null;
        if (order == null) {
            return trades;
        }
        if (newPrice == order.price && newQuantity <= order.leaves) {
            order.leaves = newQuantity;
            return trades;
        }
        resting.remove(order);
        order.price = newPrice;
        order.leaves = newQuantity;
        match(order, OrderType.LIMIT, trades);
        if (order.leaves > 0) {
            rest(order);
        }
        return trades;
    }

    /** "id side price leaves cum" in priority order: bids best first, then asks best first. */
    List<String> state() {
        Comparator<Ref> bids = Comparator.<Ref>comparingLong(r -> -r.price).thenComparingLong(r -> r.priority);
        Comparator<Ref> asks = Comparator.<Ref>comparingLong(r -> r.price).thenComparingLong(r -> r.priority);
        List<String> out = new ArrayList<>();
        resting.stream().filter(r -> r.side == Side.BUY).sorted(bids).forEach(r -> out.add(describe(r)));
        resting.stream().filter(r -> r.side == Side.SELL).sorted(asks).forEach(r -> out.add(describe(r)));
        return out;
    }

    static String describe(long id, Side side, long price, long leaves, long cum) {
        return id + " " + side + " " + price + " " + leaves + " " + cum;
    }

    private static String describe(Ref r) {
        return describe(r.id, r.side, r.price, r.leaves, r.cum);
    }

    private void match(Ref taker, OrderType type, List<String> trades) {
        while (taker.leaves > 0) {
            Ref best = best(taker.side.opposite());
            if (best == null || (type != OrderType.MARKET && !crosses(taker, best.price))) {
                return;
            }
            long qty = Math.min(taker.leaves, best.leaves);
            taker.leaves -= qty;
            taker.cum += qty;
            best.leaves -= qty;
            best.cum += qty;
            long buy = taker.side == Side.BUY ? taker.id : best.id;
            long sell = taker.side == Side.BUY ? best.id : taker.id;
            trades.add(best.price + " x " + qty + " buy=" + buy + " sell=" + sell);
            if (best.leaves == 0) {
                resting.remove(best);
            }
        }
    }

    private long available(Ref taker, OrderType type) {
        long total = 0;
        for (Ref r : resting) {
            if (r.side != taker.side && (type == OrderType.MARKET || crosses(taker, r.price))) {
                total += r.leaves;
            }
        }
        return total;
    }

    /** Best price on {@code side}, earliest arrival among equals. */
    private Ref best(Side side) {
        Ref best = null;
        for (Ref r : resting) {
            if (r.side != side) {
                continue;
            }
            if (best == null) {
                best = r;
                continue;
            }
            boolean better = side == Side.BUY ? r.price > best.price : r.price < best.price;
            if (better || (r.price == best.price && r.priority < best.priority)) {
                best = r;
            }
        }
        return best;
    }

    private static boolean crosses(Ref taker, long price) {
        return taker.side == Side.BUY ? taker.price >= price : taker.price <= price;
    }

    private void rest(Ref order) {
        order.priority = ++arrival;
        resting.add(order);
    }

    private Ref find(long id, String account) {
        for (Ref r : resting) {
            if (r.id == id) {
                return r.account.equals(account) ? r : null;
            }
        }
        return null;
    }
}
