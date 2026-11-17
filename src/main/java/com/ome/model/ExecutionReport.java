package com.ome.model;

/**
 * Everything that happens to an order is published as an execution report, in the
 * style of a FIX ExecutionReport.
 *
 * @param seq       engine-wide sequence number; strictly increasing across all symbols
 * @param orderId   the order this report is about
 * @param side      null only on a rejected cancel or replace for an order the engine cannot see
 * @param orderType null only in the same case as {@code side}
 * @param price     the order's limit price (0 for MARKET)
 * @param leavesQty quantity still open after this event
 * @param cumQty    total quantity filled so far
 * @param lastQty   quantity of this fill (TRADE reports only, otherwise 0)
 * @param lastPrice price of this fill (TRADE reports only, otherwise 0)
 * @param tradeId   id of the trade (TRADE reports only, otherwise 0)
 */
public record ExecutionReport(long seq,
                              ExecType execType,
                              long orderId,
                              String account,
                              String symbol,
                              Side side,
                              OrderType orderType,
                              long price,
                              long leavesQty,
                              long cumQty,
                              long lastQty,
                              long lastPrice,
                              long tradeId,
                              OrderStatus status,
                              Reason reason) {

    /** Single-line {@code key=value} description, used on the wire and for determinism digests. */
    public String describe() {
        StringBuilder sb = new StringBuilder(160)
                .append("EXEC seq=").append(seq)
                .append(" exec=").append(execType)
                .append(" order=").append(orderId)
                .append(" account=").append(account)
                .append(" symbol=").append(symbol)
                .append(" side=").append(side == null ? "-" : side)
                .append(" type=").append(orderType == null ? "-" : orderType)
                .append(" price=").append(orderType == null ? "-"
                        : orderType == OrderType.MARKET ? "MKT" : Prices.format(price))
                .append(" leaves=").append(leavesQty)
                .append(" cum=").append(cumQty);
        if (execType == ExecType.TRADE) {
            sb.append(" lastQty=").append(lastQty)
              .append(" lastPrice=").append(Prices.format(lastPrice))
              .append(" trade=").append(tradeId);
        }
        sb.append(" status=").append(status);
        if (reason != Reason.NONE) {
            sb.append(" reason=").append(reason);
        }
        return sb.toString();
    }
}
