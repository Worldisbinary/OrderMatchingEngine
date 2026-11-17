package com.ome.command;

import com.ome.model.Names;

/** Cancel a resting order. Rejected with UNKNOWN_ORDER if it is not resting or belongs to another account. */
public record CancelOrder(String account, String symbol, long orderId) implements Command {

    public CancelOrder {
        Names.account(account);
        Names.symbol(symbol);
    }
}
