package com.ome.book;

import com.ome.model.Order;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Represents all resting orders at a single price level.
 *
 * Internally uses an ArrayDeque for O(1) head access and O(1) append,
 * giving us FIFO (time-priority) matching within a price level.
 *
 * Tracks aggregated quantity so the order book display is O(1).
 */
public class PriceLevel {

    private final double       price;
    private final Deque<Order> orders;
    private       int          totalQuantity;

    public PriceLevel(double price) {
        this.price         = price;
        this.orders        = new ArrayDeque<>();
        this.totalQuantity = 0;
    }

    /**
     * Add a new resting order to the back of the queue (time priority).
     */
    public void enqueue(Order order) {
        orders.addLast(order);
        totalQuantity += order.getRemainingQuantity();
    }

    /**
     * Peek at the oldest (highest priority) order without removing it.
     */
    public Order peek() {
        return orders.peekFirst();
    }

    /**
     * Remove the oldest (fully filled) order from the front.
     */
    public Order dequeue() {
        Order o = orders.pollFirst();
        if (o != null) totalQuantity -= o.getRemainingQuantity();
        return o;
    }

    /**
     * Notify this level that some quantity was filled on the head order.
     */
    public void onFill(int qty) {
        totalQuantity -= qty;
    }

    /**
     * Remove a specific order by id (for cancellations).
     */
    public boolean remove(long orderId) {
        Iterator<Order> it = orders.iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (o.getOrderId() == orderId) {
                totalQuantity -= o.getRemainingQuantity();
                it.remove();
                return true;
            }
        }
        return false;
    }

    public boolean   isEmpty()       { return orders.isEmpty(); }
    public int       getOrderCount() { return orders.size(); }
    public int       getTotalQty()   { return totalQuantity; }
    public double    getPrice()      { return price; }

    @Override
    public String toString() {
        return String.format("PriceLevel[%.2f | qty=%d | orders=%d]",
                price, totalQuantity, orders.size());
    }
}
