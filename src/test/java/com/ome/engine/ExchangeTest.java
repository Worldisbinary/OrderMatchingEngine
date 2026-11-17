package com.ome.engine;

import com.ome.book.RestingOrder;
import com.ome.command.NewOrder;
import com.ome.marketdata.TradeStats;
import com.ome.model.ExecType;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.Prices;
import com.ome.model.Side;
import com.ome.sim.RandomOrderFlow;
import com.ome.support.Recorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Exchange: sequencer, concurrency, journal recovery")
@Timeout(60)
class ExchangeTest {

    private static final List<Instrument> INSTRUMENTS = List.of(new Instrument("ACME", 5), new Instrument("BOLT", 1));

    @TempDir
    Path dir;

    @Test
    @DisplayName("submit completes with the command's reports, counterparties' included")
    void submitReturnsReports() {
        try (Exchange exchange = Exchange.open(INSTRUMENTS)) {
            exchange.submit(NewOrder.limit("a", "ACME", Side.SELL, px("10.00"), 5)).join();
            List<ExecutionReport> reports = exchange.submit(NewOrder.limit("b", "ACME", Side.BUY, px("10.00"), 5)).join();

            assertEquals(List.of(ExecType.NEW, ExecType.TRADE, ExecType.TRADE),
                    reports.stream().map(ExecutionReport::execType).toList());
            assertEquals(List.of("b", "b", "a"), reports.stream().map(ExecutionReport::account).toList());
            assertEquals(1, exchange.marketData().stats("ACME").orElseThrow().trades());
        }
    }

    @Test
    @DisplayName("8 threads submitting at once: every command is applied exactly once, the books stay consistent")
    void concurrentProducers() throws InterruptedException {
        int threads = 8;
        int perThread = 2_000;
        AtomicLong newOrders = new AtomicLong();
        try (Exchange exchange = Exchange.open(INSTRUMENTS)) {
            Recorder all = new Recorder();
            exchange.addListener(all);
            runProducers(exchange, threads, perThread, newOrders);

            long distinctSeqs = exchange.query(v -> all.reports.stream().mapToLong(ExecutionReport::seq).distinct().count()).join();
            long lastSeq = exchange.query(EngineView::lastSeq).join();
            assertEquals(lastSeq, distinctSeqs, "every report seen once, no gaps");
            assertEquals(newOrders.get(), exchange.query(EngineView::lastOrderId).join());
            exchange.query(v -> {
                v.verifyInvariants();
                return null;
            }).join();
        }
    }

    @Test
    @DisplayName("Reopening from the journal rebuilds exactly the same books, ids and statistics")
    void recovery() throws IOException, InterruptedException {
        Path journal = dir.resolve("journal.log");
        State before;
        long commands = 4L * 3_000;
        try (Exchange exchange = Exchange.open(INSTRUMENTS, journal)) {
            runProducers(exchange, 4, 3_000, new AtomicLong());
            before = State.of(exchange);
        }
        assertTrue(before.trades > 100, "the flow should have traded");

        try (Exchange reopened = Exchange.open(INSTRUMENTS, journal)) {
            assertEquals(commands, reopened.recoveredCommands());
            assertEquals(before, State.of(reopened));

            List<ExecutionReport> next = reopened.submit(NewOrder.limit("z", "BOLT", Side.BUY, px("1.00"), 1)).join();
            assertEquals(before.lastOrderId + 1, next.get(0).orderId(), "ids continue after recovery");
        }

        try (Exchange again = Exchange.open(INSTRUMENTS, journal)) {
            assertEquals(commands + 1, again.recoveredCommands());
        }
    }

    @Test
    @DisplayName("Group commit: a command is in the journal file before its future completes")
    void acknowledgedMeansJournaled() throws IOException {
        Path journal = dir.resolve("ack.log");
        try (Exchange exchange = Exchange.open(INSTRUMENTS, journal)) {
            for (int i = 1; i <= 50; i++) {
                exchange.submit(NewOrder.limit("a", "BOLT", Side.BUY, px("1.00"), i)).join();
                assertEquals(i, CommandJournal.read(journal).size());
            }
        }
    }

    @Test
    @DisplayName("After close, submit fails instead of hanging")
    void closed() {
        Exchange exchange = Exchange.open(INSTRUMENTS);
        exchange.close();
        CompletionException error = assertThrows(CompletionException.class,
                () -> exchange.submit(NewOrder.market("a", "ACME", Side.BUY, 1)).join());
        assertInstanceOf(IllegalStateException.class, error.getCause());
        exchange.close(); // idempotent
    }

    @Test
    @DisplayName("Queries see a consistent snapshot")
    void query() {
        try (Exchange exchange = Exchange.open(INSTRUMENTS)) {
            exchange.submit(NewOrder.limit("a", "BOLT", Side.BUY, px("9.99"), 7)).join();
            assertEquals(px("9.99"), exchange.snapshot("BOLT", 1).bestBid().price());
            CompletionException error = assertThrows(CompletionException.class, () -> exchange.snapshot("NOPE", 1));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
    }

    private static void runProducers(Exchange exchange, int threads, int perThread, AtomicLong newOrders)
            throws InterruptedException {
        List<Thread> workers = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                RandomOrderFlow flow = new RandomOrderFlow(100 + id, INSTRUMENTS, 10_000, "T" + id);
                try {
                    for (int i = 0; i < perThread; i++) {
                        var command = flow.next();
                        if (command instanceof NewOrder) {
                            newOrders.incrementAndGet();
                        }
                        exchange.submit(command).join().forEach(flow::observe);
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private static long px(String price) {
        return Prices.parse(price);
    }

    /** Everything that must survive a restart. */
    private record State(Map<String, List<RestingOrder>> books, long lastOrderId, long lastTradeId, long lastSeq,
                         Map<String, TradeStats> stats, long trades) {

        static State of(Exchange exchange) {
            return exchange.query(v -> new State(
                    Map.of("ACME", v.restingOrders("ACME"), "BOLT", v.restingOrders("BOLT")),
                    v.lastOrderId(), v.lastTradeId(), v.lastSeq(),
                    exchange.marketData().all(), v.lastTradeId())).join();
        }
    }
}
