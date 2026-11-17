package com.ome.engine;

import com.ome.book.BookSnapshot;
import com.ome.book.OrderBook;
import com.ome.book.RestingOrder;
import com.ome.book.SequentialIds;
import com.ome.command.CancelOrder;
import com.ome.command.Command;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The deterministic core: validates commands, assigns ids, routes them to the right
 * {@link OrderBook} and publishes the results to listeners.
 *
 * <p>Single-threaded by design. Given the same instruments and the same commands in the same
 * order, it always produces the same ids, reports and trades; that is what makes journal
 * replay exact. {@link Exchange} wraps it for safe use from many threads.
 */
public final class MatchingEngine implements EngineView {

    /** Largest accepted order quantity. */
    public static final long MAX_QUANTITY = 1_000_000_000L;
    /** Largest accepted price, in price units. */
    public static final long MAX_PRICE = 1_000_000_000_000L;

    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final List<ExecutionListener> listeners = new ArrayList<>();
    private final SequentialIds ids = new SequentialIds();

    private final ExecutionListener fanOut = new ExecutionListener() {
        @Override
        public void onReport(ExecutionReport report) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).onReport(report);
            }
        }

        @Override
        public void onTrade(Trade trade) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).onTrade(trade);
            }
        }
    };

    public MatchingEngine(Collection<Instrument> instruments) {
        for (Instrument instrument : instruments) {
            if (books.putIfAbsent(instrument.symbol(), new OrderBook(instrument, ids, fanOut)) != null) {
                throw new IllegalArgumentException("duplicate instrument: " + instrument.symbol());
            }
        }
    }

    public void addListener(ExecutionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Processes one command; results go to the listeners before this returns. */
    public void apply(Command command) {
        if (command instanceof NewOrder n) {
            newOrder(n);
        } else if (command instanceof CancelOrder c) {
            cancel(c);
        } else {
            replace((ReplaceOrder) command);
        }
    }

    private void newOrder(NewOrder n) {
        long orderId = ids.nextOrderId();
        OrderBook book = books.get(n.symbol());
        boolean hasPrice = n.type().hasLimitPrice();
        Reason problem = book == null
                ? Reason.UNKNOWN_SYMBOL
                : check(book.instrument(), hasPrice, n.price(), n.quantity());
        if (problem != Reason.NONE) {
            reject(orderId, n.account(), n.symbol(), n.side(), n.type(), hasPrice ? n.price() : 0, problem);
            return;
        }
        book.submit(orderId, n.account(), n.side(), n.type(), n.price(), n.quantity());
    }

    private void cancel(CancelOrder c) {
        OrderBook book = books.get(c.symbol());
        if (book == null) {
            reject(c.orderId(), c.account(), c.symbol(), null, null, 0, Reason.UNKNOWN_SYMBOL);
        } else if (!book.cancel(c.orderId(), c.account())) {
            reject(c.orderId(), c.account(), c.symbol(), null, null, 0, Reason.UNKNOWN_ORDER);
        }
    }

    private void replace(ReplaceOrder r) {
        OrderBook book = books.get(r.symbol());
        Reason problem = book == null
                ? Reason.UNKNOWN_SYMBOL
                : check(book.instrument(), true, r.newPrice(), r.newQuantity());
        if (problem == Reason.NONE && !book.replace(r.orderId(), r.account(), r.newPrice(), r.newQuantity())) {
            problem = Reason.UNKNOWN_ORDER;
        }
        if (problem != Reason.NONE) {
            reject(r.orderId(), r.account(), r.symbol(), null, null, r.newPrice(), problem);
        }
    }

    private static Reason check(Instrument instrument, boolean hasPrice, long price, long quantity) {
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            return Reason.INVALID_QUANTITY;
        }
        if (hasPrice) {
            if (price <= 0 || price > MAX_PRICE) {
                return Reason.INVALID_PRICE;
            }
            if (!instrument.isOnTick(price)) {
                return Reason.PRICE_NOT_ON_TICK;
            }
        }
        return Reason.NONE;
    }

    private void reject(long orderId, String account, String symbol, Side side, OrderType type, long price,
                        Reason reason) {
        fanOut.onReport(new ExecutionReport(ids.nextSeq(), ExecType.REJECTED, orderId, account, symbol, side,
                type, price, 0, 0, 0, 0, 0, OrderStatus.REJECTED, reason));
    }

    // ---------------------------------------------------------------- queries

    @Override
    public Collection<Instrument> instruments() {
        return books.values().stream().map(OrderBook::instrument).toList();
    }

    @Override
    public BookSnapshot snapshot(String symbol, int depth) {
        return book(symbol).snapshot(depth);
    }

    @Override
    public List<RestingOrder> restingOrders(String symbol) {
        return book(symbol).restingOrders();
    }

    @Override
    public int restingCount() {
        int count = 0;
        for (OrderBook book : books.values()) {
            count += book.restingCount();
        }
        return count;
    }

    @Override
    public long lastOrderId() {
        return ids.lastOrderId();
    }

    @Override
    public long lastTradeId() {
        return ids.lastTradeId();
    }

    @Override
    public long lastSeq() {
        return ids.lastSeq();
    }

    @Override
    public void verifyInvariants() {
        for (OrderBook book : books.values()) {
            book.verifyInvariants();
        }
    }

    private OrderBook book(String symbol) {
        OrderBook book = books.get(symbol);
        if (book == null) {
            throw new IllegalArgumentException("unknown symbol: " + symbol);
        }
        return book;
    }
}
