package com.ome.cli;

import com.ome.book.BookSnapshot;
import com.ome.model.Prices;

import java.io.PrintStream;

/** Prints a book snapshot as a price ladder: asks on top, bids below, best prices in the middle. */
final class ConsoleLadder {

    private ConsoleLadder() {
    }

    static void print(BookSnapshot book, PrintStream out) {
        out.println("  +----------------------------------------+");
        out.printf("  | %-8s %10s %10s %8s |%n", book.symbol(), "PRICE", "QTY", "ORDERS");
        out.println("  +----------------------------------------+");
        for (int i = book.asks().size() - 1; i >= 0; i--) {
            row(out, "ask", book.asks().get(i));
        }
        BookSnapshot.Level bid = book.bestBid();
        BookSnapshot.Level ask = book.bestAsk();
        String spread = bid == null || ask == null ? "-" : Prices.format(ask.price() - bid.price());
        out.printf("  | %-8s %10s %10s %8s |%n", "spread", spread, "", "");
        for (BookSnapshot.Level level : book.bids()) {
            row(out, "bid", level);
        }
        out.println("  +----------------------------------------+");
    }

    private static void row(PrintStream out, String side, BookSnapshot.Level level) {
        out.printf("  | %-8s %10s %10d %8d |%n", side, Prices.format(level.price()), level.quantity(), level.orders());
    }
}
