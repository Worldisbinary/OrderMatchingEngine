package com.ome.gateway;

import com.ome.book.BookSnapshot;
import com.ome.command.CancelOrder;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
import com.ome.model.OrderType;
import com.ome.model.Prices;
import com.ome.model.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The gateway's line protocol. Requests are one line of space-separated words; each request
 * is answered by zero or more lines and then {@code OK} or {@code ERR <message>}. Execution
 * reports ({@code EXEC ...}) are pushed to the account that owns the order, including fills
 * caused by other accounts' orders.
 */
public final class Protocol {

    public static final List<String> HELP = List.of(
            "LOGIN <account>",
            "NEW <BUY|SELL> <SYMBOL> <LIMIT|IOC|FOK> <QTY> <PRICE>",
            "NEW <BUY|SELL> <SYMBOL> MARKET <QTY>",
            "CANCEL <SYMBOL> <ORDER_ID>",
            "REPLACE <SYMBOL> <ORDER_ID> <QTY> <PRICE>",
            "BOOK <SYMBOL> [DEPTH]",
            "STATS <SYMBOL>",
            "HELP",
            "QUIT");

    private Protocol() {
    }

    /** {@code NEW <BUY|SELL> <SYMBOL> <TYPE> <QTY> [PRICE]}. */
    static NewOrder parseNew(String account, String[] words) {
        if (words.length < 5) {
            throw new IllegalArgumentException("usage: NEW <BUY|SELL> <SYMBOL> <LIMIT|IOC|FOK|MARKET> <QTY> [PRICE]");
        }
        Side side = parseEnum(Side.class, words[1], "side");
        String symbol = words[2].toUpperCase(Locale.ROOT);
        OrderType type = parseEnum(OrderType.class, words[3], "order type");
        long quantity = parseLong(words[4], "quantity");
        if (type == OrderType.MARKET) {
            if (words.length != 5) {
                throw new IllegalArgumentException("a MARKET order takes no price");
            }
            return NewOrder.market(account, symbol, side, quantity);
        }
        if (words.length != 6) {
            throw new IllegalArgumentException("a " + type + " order needs a price");
        }
        return new NewOrder(account, symbol, side, type, Prices.parse(words[5]), quantity);
    }

    /** {@code CANCEL <SYMBOL> <ORDER_ID>}. */
    static CancelOrder parseCancel(String account, String[] words) {
        if (words.length != 3) {
            throw new IllegalArgumentException("usage: CANCEL <SYMBOL> <ORDER_ID>");
        }
        return new CancelOrder(account, words[1].toUpperCase(Locale.ROOT), parseLong(words[2], "order id"));
    }

    /** {@code REPLACE <SYMBOL> <ORDER_ID> <QTY> <PRICE>}. */
    static ReplaceOrder parseReplace(String account, String[] words) {
        if (words.length != 5) {
            throw new IllegalArgumentException("usage: REPLACE <SYMBOL> <ORDER_ID> <QTY> <PRICE>");
        }
        return new ReplaceOrder(account, words[1].toUpperCase(Locale.ROOT), parseLong(words[2], "order id"),
                Prices.parse(words[4]), parseLong(words[3], "quantity"));
    }

    /** Asks from highest to lowest, then bids from highest to lowest, like a price ladder. */
    static List<String> formatBook(BookSnapshot book) {
        List<String> lines = new ArrayList<>();
        lines.add("BOOK " + book.symbol());
        for (int i = book.asks().size() - 1; i >= 0; i--) {
            lines.add(level("ASK", book.asks().get(i)));
        }
        for (BookSnapshot.Level bid : book.bids()) {
            lines.add(level("BID", bid));
        }
        return lines;
    }

    private static String level(String side, BookSnapshot.Level level) {
        return side + " " + Prices.format(level.price()) + " " + level.quantity() + " " + level.orders();
    }

    static long parseLong(String text, String what) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number: " + text, e);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text, String what) {
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + what + ": " + text, e);
        }
    }
}
