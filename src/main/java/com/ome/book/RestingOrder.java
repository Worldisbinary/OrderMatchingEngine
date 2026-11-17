package com.ome.book;

import com.ome.model.Side;

/** Read-only view of an order resting on the book. */
public record RestingOrder(long orderId, String account, Side side, long price, long leavesQty, long cumQty) {
}
