package com.ome.marketdata;

import java.time.Instant;

/**
 * Immutable snapshot of market data for a symbol at a specific point in time.
 *
 * In a real system this would be serialized and disseminated over FAST/ITCH/SBE
 * to downstream consumers: trading terminals, risk engines, algo strategies.
 */
public final class MarketDataSnapshot {

    private final String  symbol;
    private final double  bestBid;
    private final double  bestAsk;
    private final double  spread;
    private final double  midPrice;
    private final double  lastTradePrice;
    private final double  vwap;
    private final long    totalVolume;
    private final int     bidDepth;     // total number of orders on the bid side
    private final int     askDepth;     // total number of orders on the ask side
    private final Instant capturedAt;

    private MarketDataSnapshot(Builder b) {
        this.symbol         = b.symbol;
        this.bestBid        = b.bestBid;
        this.bestAsk        = b.bestAsk;
        this.spread         = b.spread;
        this.midPrice       = b.midPrice;
        this.lastTradePrice = b.lastTradePrice;
        this.vwap           = b.vwap;
        this.totalVolume    = b.totalVolume;
        this.bidDepth       = b.bidDepth;
        this.askDepth       = b.askDepth;
        this.capturedAt     = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getSymbol()         { return symbol; }
    public double  getBestBid()        { return bestBid; }
    public double  getBestAsk()        { return bestAsk; }
    public double  getSpread()         { return spread; }
    public double  getMidPrice()       { return midPrice; }
    public double  getLastTradePrice() { return lastTradePrice; }
    public double  getVwap()           { return vwap; }
    public long    getTotalVolume()    { return totalVolume; }
    public int     getBidDepth()       { return bidDepth; }
    public int     getAskDepth()       { return askDepth; }
    public Instant getCapturedAt()     { return capturedAt; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static class Builder {
        private String symbol;
        private double bestBid, bestAsk, spread, midPrice;
        private double lastTradePrice, vwap;
        private long   totalVolume;
        private int    bidDepth, askDepth;

        public Builder symbol(String v)         { this.symbol = v;         return this; }
        public Builder bestBid(double v)        { this.bestBid = v;        return this; }
        public Builder bestAsk(double v)        { this.bestAsk = v;        return this; }
        public Builder spread(double v)         { this.spread = v;         return this; }
        public Builder midPrice(double v)       { this.midPrice = v;       return this; }
        public Builder lastTradePrice(double v) { this.lastTradePrice = v; return this; }
        public Builder vwap(double v)           { this.vwap = v;           return this; }
        public Builder totalVolume(long v)      { this.totalVolume = v;    return this; }
        public Builder bidDepth(int v)          { this.bidDepth = v;       return this; }
        public Builder askDepth(int v)          { this.askDepth = v;       return this; }

        public MarketDataSnapshot build()       { return new MarketDataSnapshot(this); }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format(
            "📊 %-5s | Bid: %7.2f | Ask: %7.2f | Spread: %5.2f | Mid: %7.2f | LTP: %7.2f | VWAP: %7.2f | Vol: %,d | @%s",
            symbol,
            bestBid, bestAsk,
            Double.isNaN(spread) ? 0 : spread,
            Double.isNaN(midPrice) ? 0 : midPrice,
            lastTradePrice, vwap, totalVolume,
            capturedAt
        );
    }
}
