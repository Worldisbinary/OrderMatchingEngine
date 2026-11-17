package com.ome.engine;

import com.ome.command.CancelOrder;
import com.ome.command.Command;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
import com.ome.model.ExecType;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.OrderStatus;
import com.ome.model.OrderType;
import com.ome.model.Prices;
import com.ome.model.Reason;
import com.ome.model.Side;
import com.ome.model.Trade;
import com.ome.sim.RandomOrderFlow;
import com.ome.support.Recorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MatchingEngine validation, ids and determinism")
class MatchingEngineTest {

    private static final Instrument ACME = new Instrument("ACME", 5);
    private static final Instrument BOLT = new Instrument("BOLT", 1);

    private final Recorder events = new Recorder();
    private final MatchingEngine engine = engine(events);

    private static MatchingEngine engine(ExecutionListener listener) {
        MatchingEngine engine = new MatchingEngine(List.of(ACME, BOLT));
        engine.addListener(listener);
        return engine;
    }

    @Test
    @DisplayName("Rejects an unknown symbol")
    void unknownSymbol() {
        engine.apply(NewOrder.limit("a", "NOPE", Side.BUY, px("10.00"), 1));
        assertRejected(Reason.UNKNOWN_SYMBOL);
    }

    @Test
    @DisplayName("Rejects zero, negative and oversized quantities")
    void badQuantity() {
        for (long qty : new long[] {0, -5, MatchingEngine.MAX_QUANTITY + 1}) {
            events.clear();
            engine.apply(NewOrder.limit("a", "ACME", Side.BUY, px("10.00"), qty));
            assertRejected(Reason.INVALID_QUANTITY);
        }
    }

    @Test
    @DisplayName("Rejects non-positive prices and prices off the tick")
    void badPrice() {
        engine.apply(NewOrder.limit("a", "ACME", Side.BUY, 0, 1));
        assertRejected(Reason.INVALID_PRICE);
        events.clear();
        engine.apply(NewOrder.limit("a", "ACME", Side.BUY, px("10.03"), 1));
        assertRejected(Reason.PRICE_NOT_ON_TICK);
        events.clear();
        engine.apply(NewOrder.limit("a", "BOLT", Side.BUY, px("10.03"), 1));
        assertEquals(ExecType.NEW, events.reports.get(0).execType(), "BOLT has a 0.01 tick");
    }

    @Test
    @DisplayName("MARKET orders need no price; any price sent is ignored")
    void marketIgnoresPrice() {
        engine.apply(new NewOrder("a", "ACME", Side.BUY, OrderType.MARKET, 12_345, 10));
        ExecutionReport accepted = events.reports.get(0);
        assertEquals(ExecType.NEW, accepted.execType());
        assertEquals(0, accepted.price());
    }

    @Test
    @DisplayName("Cancel or replace of an unknown, finished or foreign order is rejected with UNKNOWN_ORDER")
    void unknownOrder() {
        engine.apply(NewOrder.limit("owner", "ACME", Side.BUY, px("10.00"), 1));
        long id = events.reports.get(0).orderId();

        for (Command command : List.of(
                new CancelOrder("other", "ACME", id),
                new CancelOrder("owner", "ACME", id + 100),
                new ReplaceOrder("other", "ACME", id, px("10.00"), 1),
                new CancelOrder("owner", "BOLT", id))) {
            events.clear();
            engine.apply(command);
            assertRejected(Reason.UNKNOWN_ORDER);
        }
        assertEquals(1, engine.restingCount());
    }

    @Test
    @DisplayName("Replace validates the new price and quantity")
    void replaceValidation() {
        engine.apply(NewOrder.limit("a", "ACME", Side.BUY, px("10.00"), 5));
        long id = events.reports.get(0).orderId();

        events.clear();
        engine.apply(new ReplaceOrder("a", "ACME", id, px("10.02"), 5));
        assertRejected(Reason.PRICE_NOT_ON_TICK);
        events.clear();
        engine.apply(new ReplaceOrder("a", "ACME", id, px("10.00"), 0));
        assertRejected(Reason.INVALID_QUANTITY);
        events.clear();
        engine.apply(new ReplaceOrder("a", "ACME", id, px("10.05"), 3));
        assertEquals(ExecType.REPLACED, events.reports.get(0).execType());
    }

    @Test
    @DisplayName("Order ids are sequential, rejected orders included; sequence numbers rise across symbols")
    void idsAndSequence() {
        engine.apply(NewOrder.limit("a", "ACME", Side.BUY, px("10.00"), 1));
        engine.apply(NewOrder.limit("a", "NOPE", Side.BUY, px("10.00"), 1));
        engine.apply(NewOrder.limit("a", "BOLT", Side.SELL, px("11.00"), 1));
        engine.apply(NewOrder.limit("b", "BOLT", Side.BUY, px("11.00"), 1));

        assertEquals(List.of(1L, 2L, 3L, 4L), events.reports.stream()
                .filter(r -> r.execType() == ExecType.NEW || r.execType() == ExecType.REJECTED)
                .map(ExecutionReport::orderId).toList());
        for (int i = 1; i < events.reports.size(); i++) {
            assertTrue(events.reports.get(i).seq() > events.reports.get(i - 1).seq());
        }
        assertEquals(4, engine.lastOrderId());
        assertEquals(1, engine.lastTradeId());
    }

    @Test
    @DisplayName("Each symbol has its own book")
    void booksAreSeparate() {
        engine.apply(NewOrder.limit("a", "ACME", Side.SELL, px("10.00"), 5));
        engine.apply(NewOrder.limit("b", "BOLT", Side.BUY, px("10.00"), 5));

        assertTrue(events.trades.isEmpty());
        assertEquals(1, engine.restingOrders("ACME").size());
        assertEquals(1, engine.restingOrders("BOLT").size());
        assertThrows(IllegalArgumentException.class, () -> engine.snapshot("NOPE", 5));
    }

    @Test
    @DisplayName("Same commands in the same order give byte-identical output and state")
    void deterministic() throws NoSuchAlgorithmException {
        List<Command> commands = new ArrayList<>();
        RandomOrderFlow flow = new RandomOrderFlow(7, List.of(ACME, BOLT), 10_000, "A", "B", "C", "D");
        Digest first = new Digest();
        MatchingEngine original = engine(first);
        original.addListener(new ExecutionListener() {
            @Override
            public void onReport(ExecutionReport report) {
                flow.observe(report);
            }
        });
        for (int i = 0; i < 20_000; i++) {
            Command command = flow.next();
            commands.add(command);
            original.apply(command);
        }

        Digest second = new Digest();
        MatchingEngine replayed = engine(second);
        commands.forEach(replayed::apply);

        assertTrue(original.lastTradeId() > 1_000, "the flow should trade a lot");
        assertEquals(first.hex(), second.hex());
        assertEquals(original.restingOrders("ACME"), replayed.restingOrders("ACME"));
        assertEquals(original.restingOrders("BOLT"), replayed.restingOrders("BOLT"));
        assertNotEquals(0, original.restingCount());
        replayed.verifyInvariants();
    }

    private void assertRejected(Reason reason) {
        assertEquals(1, events.reports.size(), events.reports.toString());
        ExecutionReport report = events.reports.get(0);
        assertEquals(ExecType.REJECTED, report.execType());
        assertEquals(OrderStatus.REJECTED, report.status());
        assertEquals(reason, report.reason());
    }

    private static long px(String price) {
        return Prices.parse(price);
    }

    /** SHA-256 over every report and trade, in order. */
    static final class Digest implements ExecutionListener {
        private final MessageDigest sha;

        Digest() throws NoSuchAlgorithmException {
            sha = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public void onReport(ExecutionReport report) {
            sha.update((report.describe() + '\n').getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onTrade(Trade trade) {
            sha.update((trade.describe() + '\n').getBytes(StandardCharsets.UTF_8));
        }

        String hex() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }
}
