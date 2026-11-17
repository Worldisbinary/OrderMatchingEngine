package com.ome.book;

/**
 * All resting orders at one price, oldest first. The queue is an intrusive doubly linked
 * list threaded through the orders themselves:
 *
 * <ul>
 *   <li>append at the tail: O(1)</li>
 *   <li>match from the head: O(1)</li>
 *   <li>remove any order (cancel, amend): O(1), because the order knows its neighbours</li>
 * </ul>
 *
 * The level also keeps its total open quantity so depth and fill-or-kill checks never walk the queue.
 */
final class PriceLevel {

    final long price;
    private Order head;
    private Order tail;
    private long totalQty;
    private int size;

    PriceLevel(long price) {
        this.price = price;
    }

    void append(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQty += order.leaves;
        size++;
    }

    void remove(Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        totalQty -= order.leaves;
        size--;
        order.prev = null;
        order.next = null;
        order.level = null;
    }

    /** Records that {@code qty} of a queued order was filled or cancelled down. */
    void reduce(long qty) {
        totalQty -= qty;
    }

    Order head() {
        return head;
    }

    boolean isEmpty() {
        return head == null;
    }

    long totalQty() {
        return totalQty;
    }

    int size() {
        return size;
    }

    /** Checks the links, the count and the quantity total. */
    void verify() {
        long qty = 0;
        int count = 0;
        Order previous = null;
        for (Order o = head; o != null; o = o.next) {
            check(o.prev == previous, "broken prev link at order " + o.id);
            check(o.level == this, "order " + o.id + " points at the wrong level");
            check(o.price == price, "order " + o.id + " has price " + o.price + " in level " + price);
            check(o.leaves > 0, "order " + o.id + " rests with nothing open");
            qty += o.leaves;
            count++;
            previous = o;
        }
        check(previous == tail, "tail does not match the last order at " + price);
        check(count == size, "size " + size + " but " + count + " orders at " + price);
        check(qty == totalQty, "total " + totalQty + " but orders sum to " + qty + " at " + price);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
