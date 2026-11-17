package com.ome.book;

import com.ome.model.OrderStatus;
import com.ome.model.OrderType;
import com.ome.model.Side;

/**
 * Mutable order state, private to the book. While an order rests it is also a node in its
 * price level's doubly linked FIFO queue ({@code prev}, {@code next}, {@code level}), so a
 * cancel can unlink it in O(1) without searching the queue.
 */
final class Order {

    final long id;
    final String account;
    final Side side;
    final OrderType type;
    long price;
    long leaves;
    long cum;
    OrderStatus status = OrderStatus.OPEN;

    // Intrusive queue links, set only while resting.
    Order prev;
    Order next;
    PriceLevel level;

    Order(long id, String account, Side side, OrderType type, long price, long quantity) {
        this.id = id;
        this.account = account;
        this.side = side;
        this.type = type;
        this.price = price;
        this.leaves = quantity;
    }

    void fill(long qty) {
        leaves -= qty;
        cum += qty;
        status = leaves == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** Status of a live order: OPEN until something fills. */
    OrderStatus liveStatus() {
        return cum == 0 ? OrderStatus.OPEN : OrderStatus.PARTIALLY_FILLED;
    }
}
