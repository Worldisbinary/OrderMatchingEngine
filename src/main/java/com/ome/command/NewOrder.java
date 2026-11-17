package com.ome.command;

import com.ome.model.Names;
import com.ome.model.OrderType;
import com.ome.model.Side;

import java.util.Objects;

/**
 * Submit a new order. Price and quantity are not range-checked here: the engine checks
 * them and answers with a REJECTED report, so even bad requests are journaled and replayed
 * the same way.
 *
 * @param price limit price in price units (see {@link com.ome.model.Prices}); ignored for MARKET
 */
public record NewOrder(String account, String symbol, Side side, OrderType type, long price, long quantity)
        implements Command {

    public NewOrder {
        Names.account(account);
        Names.symbol(symbol);
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
    }

    public static NewOrder limit(String account, String symbol, Side side, long price, long quantity) {
        return new NewOrder(account, symbol, side, OrderType.LIMIT, price, quantity);
    }

    public static NewOrder market(String account, String symbol, Side side, long quantity) {
        return new NewOrder(account, symbol, side, OrderType.MARKET, 0, quantity);
    }

    public static NewOrder ioc(String account, String symbol, Side side, long price, long quantity) {
        return new NewOrder(account, symbol, side, OrderType.IOC, price, quantity);
    }

    public static NewOrder fok(String account, String symbol, Side side, long price, long quantity) {
        return new NewOrder(account, symbol, side, OrderType.FOK, price, quantity);
    }
}
