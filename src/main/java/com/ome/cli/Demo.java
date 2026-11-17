package com.ome.cli;

import com.ome.command.CancelOrder;
import com.ome.command.Command;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
import com.ome.engine.Exchange;
import com.ome.model.ExecutionReport;
import com.ome.model.Prices;
import com.ome.model.Side;

import java.io.PrintStream;
import java.util.List;

/** A guided walk through every order type, amend rule and reject path, printing each report. */
public final class Demo {

    private static final String SYMBOL = SampleInstruments.ACME.symbol();

    private final Exchange exchange;
    private final PrintStream out;

    private Demo(Exchange exchange, PrintStream out) {
        this.exchange = exchange;
        this.out = out;
    }

    public static void run(PrintStream out) {
        try (Exchange exchange = Exchange.open(SampleInstruments.ALL)) {
            new Demo(exchange, out).script();
        }
    }

    private void script() {
        step("1. Build a book. ACME trades in 0.05 ticks.");
        send(NewOrder.limit("alice", SYMBOL, Side.SELL, px("100.10"), 100));
        send(NewOrder.limit("alice", SYMBOL, Side.SELL, px("100.20"), 200));
        send(NewOrder.limit("bob", SYMBOL, Side.SELL, px("100.20"), 150));
        send(NewOrder.limit("carol", SYMBOL, Side.BUY, px("100.00"), 50));
        send(NewOrder.limit("carol", SYMBOL, Side.BUY, px("99.90"), 100));
        book();

        step("2. Price-time priority: a buy for 250 at 100.20 takes 100.10 first, then alice "
                + "(earlier) before bob at 100.20. Each fill is at the resting order's price.");
        send(NewOrder.limit("dave", SYMBOL, Side.BUY, px("100.20"), 250));
        book();

        step("3. Market sell for 120: takes the bids best first; nothing rests.");
        send(NewOrder.market("erin", SYMBOL, Side.SELL, 120));

        step("4. IOC buy for 300 at 100.20: fills what is there, cancels the rest.");
        send(NewOrder.ioc("dave", SYMBOL, Side.BUY, px("100.20"), 300));

        step("5. FOK buy for 500: not enough liquidity, so nothing trades.");
        send(NewOrder.limit("alice", SYMBOL, Side.SELL, px("100.50"), 200));
        send(NewOrder.fok("dave", SYMBOL, Side.BUY, px("100.50"), 500));

        step("6. Amend rules. Lowering quantity keeps queue priority; changing price loses it.");
        long first = acceptedId(send(NewOrder.limit("frank", SYMBOL, Side.BUY, px("99.50"), 100)));
        send(NewOrder.limit("grace", SYMBOL, Side.BUY, px("99.50"), 100));
        send(new ReplaceOrder("frank", SYMBOL, first, px("99.50"), 60));
        send(NewOrder.market("erin", SYMBOL, Side.SELL, 50));
        send(new ReplaceOrder("frank", SYMBOL, first, px("100.45"), 10));

        step("7. Cancel, and the reject paths.");
        send(new CancelOrder("grace", SYMBOL, first));
        send(new CancelOrder("frank", SYMBOL, first + 1));
        send(new CancelOrder("grace", SYMBOL, first + 1));
        send(NewOrder.limit("heidi", SYMBOL, Side.BUY, px("100.03"), 10));
        send(NewOrder.limit("heidi", "NOPE", Side.BUY, px("100.00"), 10));
        send(NewOrder.limit("heidi", SYMBOL, Side.BUY, px("100.00"), 0));
        book();

        out.println();
        out.println(exchange.marketData().stats(SYMBOL).orElseThrow().describe());
    }

    private List<ExecutionReport> send(Command command) {
        out.println();
        out.println("> " + describe(command));
        List<ExecutionReport> reports = exchange.submit(command).join();
        for (ExecutionReport report : reports) {
            out.println("  " + report.describe());
        }
        return reports;
    }

    static String describe(Command command) {
        if (command instanceof NewOrder n) {
            String price = n.type().hasLimitPrice() ? " @ " + Prices.format(n.price()) : "";
            return n.account() + ": " + n.side() + " " + n.quantity() + " " + n.symbol() + " " + n.type() + price;
        }
        if (command instanceof CancelOrder c) {
            return c.account() + ": CANCEL " + c.symbol() + " order " + c.orderId();
        }
        ReplaceOrder r = (ReplaceOrder) command;
        return r.account() + ": REPLACE " + r.symbol() + " order " + r.orderId() + " -> " + r.newQuantity()
                + " @ " + Prices.format(r.newPrice());
    }

    private static long acceptedId(List<ExecutionReport> reports) {
        return reports.get(0).orderId();
    }

    private void book() {
        out.println();
        ConsoleLadder.print(exchange.snapshot(SYMBOL, 5), out);
    }

    private void step(String title) {
        out.println();
        out.println("=== " + title);
    }

    private static long px(String price) {
        return Prices.parse(price);
    }
}
