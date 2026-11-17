package com.ome.model;

/**
 * One match between an incoming (aggressor) order and a resting order. The price is
 * always the resting order's price.
 */
public record Trade(long tradeId,
                    String symbol,
                    long price,
                    long quantity,
                    long buyOrderId,
                    long sellOrderId,
                    Side aggressorSide) {

    /** Single-line description, also used for determinism digests. */
    public String describe() {
        return "TRADE id=" + tradeId + " symbol=" + symbol + " qty=" + quantity
                + " price=" + Prices.format(price) + " buy=" + buyOrderId + " sell=" + sellOrderId
                + " aggressor=" + aggressorSide;
    }
}
