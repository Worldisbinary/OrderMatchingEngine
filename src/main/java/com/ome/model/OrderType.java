package com.ome.model;

/**
 * Supported order types.
 *
 * LIMIT  — Execute at the specified price or better; rest on book if unmatched.
 * MARKET — Execute immediately at the best available price; never rests on book.
 * IOC    — Immediate-Or-Cancel: fill as much as possible, cancel the remainder.
 * FOC    — Fill-Or-Cancel: fill the entire quantity or cancel the whole order.
 */
public enum OrderType {
    LIMIT,
    MARKET,
    IOC,
    FOC
}
