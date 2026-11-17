package com.ome.model;

import java.math.BigDecimal;

/**
 * Fixed-point prices. Every price in the engine is a {@code long} count of 0.01 units,
 * so 101.25 is stored as 10125. Integer prices compare and hash exactly, which matters
 * because they are the keys of the order book.
 */
public final class Prices {

    /** Decimal places carried by a price. */
    public static final int DECIMALS = 2;

    /** Number of price units in 1.00. */
    public static final long ONE = 100;

    private Prices() {
    }

    /**
     * Parses a decimal string such as {@code "101.25"} into price units.
     *
     * @throws IllegalArgumentException if the text is not a number, has more than two
     *                                  decimal places or does not fit in a long
     */
    public static long parse(String text) {
        BigDecimal value;
        try {
            value = new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a price: " + text, e);
        }
        if (value.stripTrailingZeros().scale() > DECIMALS) {
            throw new IllegalArgumentException("at most " + DECIMALS + " decimal places: " + text);
        }
        try {
            return value.movePointRight(DECIMALS).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("price out of range: " + text, e);
        }
    }

    /** Formats price units as a decimal string, e.g. 10125 becomes {@code "101.25"}. */
    public static String format(long price) {
        return BigDecimal.valueOf(price, DECIMALS).toPlainString();
    }
}
