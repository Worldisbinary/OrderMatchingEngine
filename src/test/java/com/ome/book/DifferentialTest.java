package com.ome.book;

import com.ome.command.CancelOrder;
import com.ome.command.Command;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.Trade;
import com.ome.sim.RandomOrderFlow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential test: random order flow is applied to the real {@link OrderBook} and to the
 * naive {@link ReferenceBook}. After every command the trades it produced and the full book
 * (every resting order, in queue order) must be identical, and the real book's structural
 * invariants must hold. 50 seeds x 4,000 commands = 200,000 commands.
 */
@DisplayName("OrderBook vs reference implementation on random order flow")
class DifferentialTest {

    static final int COMMANDS_PER_SEED = 4_000;

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 50);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void matchesReference(long seed) {
        Instrument instrument = new Instrument("TEST", 5);
        RandomOrderFlow flow = new RandomOrderFlow(seed, List.of(instrument), 10_000, "A", "B", "C");
        List<String> trades = new ArrayList<>();
        OrderBook book = new OrderBook(instrument, new SequentialIds(), new ExecutionListener() {
            @Override
            public void onReport(ExecutionReport report) {
                flow.observe(report);
            }

            @Override
            public void onTrade(Trade t) {
                trades.add(t.price() + " x " + t.quantity() + " buy=" + t.buyOrderId() + " sell=" + t.sellOrderId());
            }
        });
        ReferenceBook reference = new ReferenceBook();
        long nextId = 0;

        for (int step = 1; step <= COMMANDS_PER_SEED; step++) {
            Command command = flow.next();
            trades.clear();
            List<String> expected;
            if (command instanceof NewOrder n) {
                long id = ++nextId;
                expected = reference.submit(id, n.account(), n.side(), n.type(), n.price(), n.quantity());
                book.submit(id, n.account(), n.side(), n.type(), n.price(), n.quantity());
            } else if (command instanceof CancelOrder c) {
                expected = List.of();
                assertEquals(reference.cancel(c.orderId(), c.account()), book.cancel(c.orderId(), c.account()),
                        context(seed, step, command));
            } else {
                ReplaceOrder r = (ReplaceOrder) command;
                boolean[] accepted = new boolean[1];
                expected = reference.replace(r.orderId(), r.account(), r.newPrice(), r.newQuantity(), accepted);
                assertEquals(accepted[0], book.replace(r.orderId(), r.account(), r.newPrice(), r.newQuantity()),
                        context(seed, step, command));
            }

            assertEquals(expected, trades, context(seed, step, command));
            book.verifyInvariants();
            assertEquals(reference.state(), state(book), context(seed, step, command));
        }
    }

    private static List<String> state(OrderBook book) {
        return book.restingOrders().stream()
                .map(o -> ReferenceBook.describe(o.orderId(), o.side(), o.price(), o.leavesQty(), o.cumQty()))
                .toList();
    }

    private static String context(long seed, int step, Command command) {
        return "seed " + seed + ", step " + step + ", command " + command;
    }
}
