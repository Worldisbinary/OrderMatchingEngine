package com.ome.model;

/** What an execution report is about, modelled on the FIX ExecType field. */
public enum ExecType {
    /** The order was accepted. */
    NEW,
    /** The order traded; {@code lastQty} and {@code lastPrice} describe the fill. */
    TRADE,
    /** A resting order's price or quantity was amended. */
    REPLACED,
    /** The order (or its remainder) was cancelled; the reason says why. */
    CANCELLED,
    /** The request was refused and changed nothing; the reason says why. */
    REJECTED
}
