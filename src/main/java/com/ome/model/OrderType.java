package com.ome.model;

/**
 * Supported order types.
 *
 * <ul>
 *   <li>{@code LIMIT}: trades at the limit price or better; any remainder rests on the book.</li>
 *   <li>{@code MARKET}: trades at any price against the liquidity that is there; the remainder is cancelled.</li>
 *   <li>{@code IOC} (immediate-or-cancel): trades up to the limit price; the remainder is cancelled.</li>
 *   <li>{@code FOK} (fill-or-kill): trades the full quantity up to the limit price, or nothing at all.</li>
 * </ul>
 */
public enum OrderType {
    LIMIT,
    MARKET,
    IOC,
    FOK;

    /** True for every type except {@code MARKET}. */
    public boolean hasLimitPrice() {
        return this != MARKET;
    }
}
