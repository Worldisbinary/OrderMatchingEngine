package com.ome.marketdata;

import com.ome.model.ExecutionListener;
import com.ome.model.Trade;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds per-symbol trade statistics from the trade tape. Updates run on the engine thread;
 * each update publishes a new immutable {@link TradeStats}, so any thread can read them.
 */
public final class MarketDataService implements ExecutionListener {

    private final ConcurrentHashMap<String, TradeStats> stats = new ConcurrentHashMap<>();

    @Override
    public void onTrade(Trade trade) {
        stats.compute(trade.symbol(), (symbol, previous) ->
                previous == null ? TradeStats.first(trade) : previous.plus(trade));
    }

    public Optional<TradeStats> stats(String symbol) {
        return Optional.ofNullable(stats.get(symbol));
    }

    public Map<String, TradeStats> all() {
        return Map.copyOf(stats);
    }
}
