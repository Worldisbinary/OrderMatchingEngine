package com.ome.model;

/**
 * A tradable symbol and its tick size: the smallest allowed price step, in price units.
 * A tick size of 5 means prices must be multiples of 0.05.
 */
public record Instrument(String symbol, long tickSize) {

    public Instrument {
        Names.symbol(symbol);
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tick size must be positive: " + tickSize);
        }
    }

    public boolean isOnTick(long price) {
        return price % tickSize == 0;
    }
}
