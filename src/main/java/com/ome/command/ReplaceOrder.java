package com.ome.command;

import com.ome.model.Names;

/**
 * Amend a resting order's price and open quantity.
 *
 * <p>Priority rules, as on most exchanges: keeping the price and lowering the quantity keeps
 * the order's place in the queue. Any price change, or a quantity increase, sends it to the
 * back of the queue at its (new) price, and it can trade immediately if the new price crosses.
 *
 * @param newPrice    new limit price in price units
 * @param newQuantity new open (leaves) quantity; quantity already filled is not affected
 */
public record ReplaceOrder(String account, String symbol, long orderId, long newPrice, long newQuantity)
        implements Command {

    public ReplaceOrder {
        Names.account(account);
        Names.symbol(symbol);
    }
}
