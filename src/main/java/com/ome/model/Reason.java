package com.ome.model;

/** Why an order was cancelled or a request was rejected. */
public enum Reason {
    NONE,

    // Cancellations
    /** The owner asked for the cancel. */
    CANCEL_REQUESTED,
    /** IOC remainder after matching. */
    IOC_REMAINDER,
    /** FOK order could not be filled in full, so nothing traded. */
    FOK_NOT_FILLABLE,
    /** MARKET order remainder: the other side of the book ran out. */
    NO_LIQUIDITY,

    // Rejections
    UNKNOWN_SYMBOL,
    INVALID_QUANTITY,
    INVALID_PRICE,
    PRICE_NOT_ON_TICK,
    /** Not resting, already done, or owned by another account. */
    UNKNOWN_ORDER
}
