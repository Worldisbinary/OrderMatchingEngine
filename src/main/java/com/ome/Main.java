package com.ome;

import com.ome.exchange.Exchange;
import com.ome.model.Order;
import com.ome.model.OrderType;
import com.ome.model.Side;
import com.ome.model.Trade;

import java.util.List;

/**
 * End-to-end demonstration of the Order Matching Engine.
 *
 * Covers:
 *   1. Building a realistic order book (AAPL)
 *   2. LIMIT order that crosses the spread → partial fill → rest
 *   3. MARKET order that sweeps multiple price levels
 *   4. IOC order (fill what you can, cancel the rest)
 *   5. FOC order (all-or-nothing)
 *   6. Order cancellation
 *   7. Multi-symbol support (TSLA)
 *   8. Live market data snapshots
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {

        banner("Real-Time Order Matching Engine",
               "Price-Time Priority | LIMIT | MARKET | IOC | FOC",
               "Java 17  ·  Simulating Exchange Microstructure");

        Exchange exchange = new Exchange("SIM-EXCHANGE");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 1: Seed the AAPL order book
        // ═══════════════════════════════════════════════════════════════════
        section("1", "Seeding AAPL Order Book with Resting LIMIT Orders");

        // Bids — highest is best
        submit(exchange, "AAPL", Side.BUY,  OrderType.LIMIT, 149.50, 200);
        submit(exchange, "AAPL", Side.BUY,  OrderType.LIMIT, 149.75, 300);
        submit(exchange, "AAPL", Side.BUY,  OrderType.LIMIT, 149.75, 100); // same price → time priority
        submit(exchange, "AAPL", Side.BUY,  OrderType.LIMIT, 149.90, 150);

        // Asks — lowest is best
        submit(exchange, "AAPL", Side.SELL, OrderType.LIMIT, 150.00, 250);
        submit(exchange, "AAPL", Side.SELL, OrderType.LIMIT, 150.25, 200);
        submit(exchange, "AAPL", Side.SELL, OrderType.LIMIT, 150.50, 400);

        exchange.printBook("AAPL");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 2: LIMIT BUY crosses the spread → matches + rests remainder
        // ═══════════════════════════════════════════════════════════════════
        section("2", "LIMIT BUY @ 150.25 — crosses spread, partial match, remainder rests");

        // BUY 400 @ 150.25 — matches 250 @ 150.00 and 150 @ 150.25 (400 total → full fill)
        // Actually we'll buy 350 so 100 rests on the bid
        List<Trade> trades = submit(exchange, "AAPL", Side.BUY, OrderType.LIMIT, 150.25, 350);
        System.out.printf("  → %d trade(s) generated%n", trades.size());

        exchange.printBook("AAPL");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 3: MARKET SELL — sweeps bid side aggressively
        // ═══════════════════════════════════════════════════════════════════
        section("3", "MARKET SELL 400 — sweeps all available bids");

        trades = submit(exchange, "AAPL", Side.SELL, OrderType.MARKET, 0, 400);
        System.out.printf("  → %d trade(s) generated%n", trades.size());

        exchange.printBook("AAPL");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 4: IOC — fill what's available, cancel the rest
        // ═══════════════════════════════════════════════════════════════════
        section("4", "IOC BUY 500 @ 150.50 — partial fill, remainder cancelled");

        // Seed a sell to partially fill against
        submit(exchange, "AAPL", Side.SELL, OrderType.LIMIT, 150.50, 100);

        // IOC BUY 500 @ 150.50 → only 100 available → fill 100, cancel 400
        trades = submit(exchange, "AAPL", Side.BUY, OrderType.IOC, 150.50, 500);
        System.out.printf("  → %d trade(s) generated%n", trades.size());

        exchange.printBook("AAPL");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 5: FOC — all-or-nothing
        // ═══════════════════════════════════════════════════════════════════
        section("5", "FOC — all-or-nothing fill");

        // Seed a sell with only 50 shares
        Order seedSell = new Order("AAPL", Side.SELL, OrderType.LIMIT, 150.00, 50);
        submit(exchange, seedSell);

        // FOC BUY 200 @ 150.00 — only 50 available → CANCELLED entirely
        System.out.println("  FOC BUY 200 @ 150.00 (only 50 available → should CANCEL):");
        trades = submit(exchange, "AAPL", Side.BUY, OrderType.FOC, 150.00, 200);
        System.out.printf("  → %d trade(s) generated (expected 0)%n", trades.size());

        // FOC BUY 50 @ 150.00 — exactly 50 available → FILLED entirely
        System.out.println("  FOC BUY 50 @ 150.00 (exactly 50 available → should FILL):");
        trades = submit(exchange, "AAPL", Side.BUY, OrderType.FOC, 150.00, 50);
        System.out.printf("  → %d trade(s) generated (expected 1)%n", trades.size());

        exchange.printBook("AAPL");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 6: Cancellation
        // ═══════════════════════════════════════════════════════════════════
        section("6", "Order Cancellation");

        Order cancelTarget = new Order("AAPL", Side.BUY, OrderType.LIMIT, 149.00, 1000);
        submit(exchange, cancelTarget);

        exchange.printBook("AAPL");

        System.out.printf("  Cancelling Order #%04d...%n", cancelTarget.getOrderId());
        exchange.cancel("AAPL", cancelTarget.getOrderId());

        exchange.printBook("AAPL");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 7: Multi-symbol — TSLA
        // ═══════════════════════════════════════════════════════════════════
        section("7", "TSLA — Multi-Symbol Support");

        submit(exchange, "TSLA", Side.BUY,  OrderType.LIMIT, 244.50, 500);
        submit(exchange, "TSLA", Side.BUY,  OrderType.LIMIT, 244.00, 300);
        submit(exchange, "TSLA", Side.SELL, OrderType.LIMIT, 245.50, 400);
        submit(exchange, "TSLA", Side.SELL, OrderType.LIMIT, 246.00, 600);

        // Aggressive MARKET BUY sweeps the offer
        submit(exchange, "TSLA", Side.BUY, OrderType.MARKET, 0, 350);

        exchange.printBook("TSLA");

        // ═══════════════════════════════════════════════════════════════════
        // Final: Market Data & Stats
        // ═══════════════════════════════════════════════════════════════════
        Thread.sleep(50); // let EventBus dispatcher flush remaining events
        exchange.shutdown();

        exchange.printAllSnapshots();
        exchange.printStats();

        banner("Simulation Complete");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<Trade> submit(Exchange exchange, String symbol,
                                       Side side, OrderType type,
                                       double price, int qty) {
        Order order = new Order(symbol, side, type, price, qty);
        return submit(exchange, order);
    }

    private static List<Trade> submit(Exchange exchange, Order order) {
        System.out.printf("%n  ➤ %s%n", order);
        List<Trade> trades = exchange.submit(order);
        trades.forEach(System.out::println);
        return trades;
    }

    private static void section(String num, String title) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.printf ("║  SCENARIO %-2s: %-44s║%n", num, title);
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    private static void banner(String... lines) {
        int width = 60;
        System.out.println("╔" + "═".repeat(width) + "╗");
        for (String line : lines) {
            int padding = (width - line.length()) / 2;
            String padded = " ".repeat(Math.max(0, padding)) + line;
            System.out.printf("║ %-" + (width - 1) + "s║%n", padded);
        }
        System.out.println("╚" + "═".repeat(width) + "╝");
        System.out.println();
    }
}
