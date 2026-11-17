package com.ome.marketdata;

import com.ome.model.Prices;
import com.ome.model.Trade;

import java.util.Locale;

/**
 * Session statistics for one symbol, built from the trade tape.
 *
 * @param turnover sum of price times quantity, in price units
 */
public record TradeStats(String symbol, long trades, long volume, double turnover,
                         long open, long high, long low, long last) {

    static TradeStats first(Trade t) {
        return new TradeStats(t.symbol(), 1, t.quantity(), (double) t.price() * t.quantity(),
                t.price(), t.price(), t.price(), t.price());
    }

    TradeStats plus(Trade t) {
        return new TradeStats(symbol, trades + 1, volume + t.quantity(),
                turnover + (double) t.price() * t.quantity(),
                open, Math.max(high, t.price()), Math.min(low, t.price()), t.price());
    }

    /** Volume-weighted average price, in price units. */
    public double vwap() {
        return volume == 0 ? 0 : turnover / volume;
    }

    public String describe() {
        return "STATS symbol=" + symbol + " trades=" + trades + " volume=" + volume
                + " open=" + Prices.format(open) + " high=" + Prices.format(high)
                + " low=" + Prices.format(low) + " last=" + Prices.format(last)
                + " vwap=" + String.format(Locale.ROOT, "%.4f", vwap() / Prices.ONE);
    }
}
