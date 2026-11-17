package com.ome.cli;

import com.ome.command.Command;
import com.ome.engine.Exchange;
import com.ome.engine.MatchingEngine;
import com.ome.model.ExecType;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.Prices;
import com.ome.model.Trade;
import com.ome.sim.RandomOrderFlow;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

/**
 * Measures throughput and per-command latency in two settings:
 *
 * <ol>
 *   <li><b>Engine only</b>: a recorded random flow replayed straight into a
 *       {@link MatchingEngine} on one thread. This is the cost of matching itself.</li>
 *   <li><b>Full pipeline</b>: several producer threads each submit through {@link Exchange} and
 *       wait for the reports (closed loop), so every command pays for the queue hand-off, the
 *       sequencer thread, the optional journal write and the wake-up of the caller.</li>
 * </ol>
 */
public final class LoadTest {

    private static final List<Instrument> INSTRUMENTS = SampleInstruments.ALL;
    private static final long START_MID = Prices.parse("100.00");

    private LoadTest() {
    }

    public static void run(int commands, int threads, boolean journal, PrintStream out)
            throws IOException, InterruptedException {
        if (commands < threads || threads < 1) {
            throw new IllegalArgumentException("need at least one command per thread and one thread");
        }
        out.printf("JVM %s %s, %d CPUs available%n", System.getProperty("java.vm.name"),
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        out.println();
        engineOnly(commands, out);
        out.println();
        pipeline(commands, threads, journal, false, out);
        out.println();
        pipeline(commands, threads, journal, true, out);
    }

    // ---------------------------------------------------------------- engine only

    private static void engineOnly(int commands, PrintStream out) {
        List<Command> flow = record(commands); // recording also warms up the JIT
        replay(flow, null);                    // and so does a first replay

        long start = System.nanoTime();
        Counts counts = replay(flow, null);
        long elapsed = System.nanoTime() - start;

        long[] latencies = new long[flow.size()];
        replay(flow, latencies);

        out.println("Engine only: one thread applying a recorded flow to MatchingEngine");
        summary(out, flow.size(), elapsed, latencies, counts);
    }

    /**
     * Generates {@code count} commands (seed 42, sample instruments) against a live engine so
     * cancels and replaces target real order ids. Replaying the result into a fresh engine
     * reproduces the same run.
     */
    public static List<Command> record(int count) {
        RandomOrderFlow flow = new RandomOrderFlow(42, INSTRUMENTS, START_MID, "A1", "A2", "A3", "A4");
        MatchingEngine engine = new MatchingEngine(INSTRUMENTS);
        engine.addListener(new ExecutionListener() {
            @Override
            public void onReport(ExecutionReport report) {
                flow.observe(report);
            }
        });
        List<Command> commands = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Command command = flow.next();
            commands.add(command);
            engine.apply(command);
        }
        return commands;
    }

    private static Counts replay(List<Command> flow, long[] latencies) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENTS);
        Counts counts = new Counts();
        engine.addListener(counts);
        if (latencies == null) {
            for (Command command : flow) {
                engine.apply(command);
            }
        } else {
            for (int i = 0; i < latencies.length; i++) {
                long t0 = System.nanoTime();
                engine.apply(flow.get(i));
                latencies[i] = System.nanoTime() - t0;
            }
        }
        engine.verifyInvariants();
        counts.resting = engine.restingCount();
        return counts;
    }

    // ---------------------------------------------------------------- full pipeline

    /**
     * @param spin if true, producers busy-wait on the reply (as a latency-sensitive client
     *             would) instead of blocking; that removes the cost of waking a parked thread
     */
    private static void pipeline(int commands, int threads, boolean journal, boolean spin, PrintStream out)
            throws IOException, InterruptedException {
        Path file = journal ? Files.createTempFile("ome-loadtest-", ".journal") : null;
        int perThread = commands / threads;
        int warmUp = Math.min(perThread, 50_000);
        long[][] latencies = new long[threads][perThread];
        CyclicBarrier measuring = new CyclicBarrier(threads + 1);
        Throwable[] failure = new Throwable[1];

        try (Exchange exchange = file == null ? Exchange.open(INSTRUMENTS) : Exchange.open(INSTRUMENTS, file)) {
            Counts counts = new Counts();
            exchange.addListener(counts);

            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                int id = t;
                workers[t] = new Thread(() -> {
                    try {
                        RandomOrderFlow flow = new RandomOrderFlow(1_000 + id, INSTRUMENTS, START_MID, "P" + id);
                        for (int i = 0; i < warmUp; i++) {
                            exchange.submit(flow.next()).join().forEach(flow::observe);
                        }
                        measuring.await();
                        long[] mine = latencies[id];
                        for (int i = 0; i < mine.length; i++) {
                            Command command = flow.next();
                            long t0 = System.nanoTime();
                            CompletableFuture<List<ExecutionReport>> reply = exchange.submit(command);
                            if (spin) {
                                while (!reply.isDone()) {
                                    Thread.onSpinWait();
                                }
                            }
                            List<ExecutionReport> reports = reply.join();
                            mine[i] = System.nanoTime() - t0;
                            reports.forEach(flow::observe);
                        }
                    } catch (InterruptedException | BrokenBarrierException | RuntimeException e) {
                        synchronized (failure) {
                            failure[0] = e;
                        }
                        measuring.reset();
                    }
                }, "loadtest-producer-" + t);
                workers[t].start();
            }

            Counts before;
            try {
                measuring.await();
                before = exchange.query(view -> counts.copy()).join();
            } catch (BrokenBarrierException e) {
                for (Thread worker : workers) {
                    worker.join();
                }
                throw new IllegalStateException("a producer failed during warm-up", failure[0]);
            }
            long start = System.nanoTime();
            for (Thread worker : workers) {
                worker.join();
            }
            long elapsed = System.nanoTime() - start;
            synchronized (failure) {
                if (failure[0] != null) {
                    throw new IllegalStateException("a producer failed", failure[0]);
                }
            }

            Counts after = exchange.query(view -> {
                view.verifyInvariants();
                Counts copy = counts.copy();
                copy.resting = view.restingCount();
                return copy;
            }).join();

            out.printf("Full pipeline: %d producer threads -> queue -> sequencer thread%s -> reply; producers %s%n",
                    threads, journal ? " -> journal" : "", spin ? "spin-wait for the reply" : "block on the reply");
            long measured = (long) threads * perThread;
            summary(out, measured, elapsed, flatten(latencies), after.minus(before));
        } finally {
            if (file != null) {
                out.printf("  journal: %,d bytes for %,d commands (warm-up included)%n",
                        Files.size(file), (long) threads * (perThread + warmUp));
                Files.deleteIfExists(file);
            }
        }
    }

    // ---------------------------------------------------------------- reporting

    private static void summary(PrintStream out, long commands, long elapsedNanos, long[] latencies, Counts counts) {
        Arrays.sort(latencies);
        double seconds = elapsedNanos / 1e9;
        out.printf("  %,d commands in %.2f s = %,.0f commands/s%n", commands, seconds, commands / seconds);
        out.printf("  latency per command: p50 %s, p90 %s, p99 %s, p99.9 %s, max %s%n",
                micros(percentile(latencies, 0.50)), micros(percentile(latencies, 0.90)),
                micros(percentile(latencies, 0.99)), micros(percentile(latencies, 0.999)),
                micros(latencies[latencies.length - 1]));
        out.printf("  %,d trades, %,d rejects, %,d orders resting at the end, invariants OK%n",
                counts.trades, counts.rejects, counts.resting);
    }

    private static long percentile(long[] sorted, double q) {
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String micros(long nanos) {
        return String.format("%.2f us", nanos / 1_000.0);
    }

    private static long[] flatten(long[][] perThread) {
        int total = 0;
        for (long[] a : perThread) {
            total += a.length;
        }
        long[] all = new long[total];
        int at = 0;
        for (long[] a : perThread) {
            System.arraycopy(a, 0, all, at, a.length);
            at += a.length;
        }
        return all;
    }

    /** Counts trades and rejects; runs on the engine thread. */
    private static final class Counts implements ExecutionListener {
        long trades;
        long rejects;
        int resting;

        @Override
        public void onTrade(Trade trade) {
            trades++;
        }

        @Override
        public void onReport(ExecutionReport report) {
            if (report.execType() == ExecType.REJECTED) {
                rejects++;
            }
        }

        Counts copy() {
            Counts c = new Counts();
            c.trades = trades;
            c.rejects = rejects;
            c.resting = resting;
            return c;
        }

        Counts minus(Counts earlier) {
            Counts c = new Counts();
            c.trades = trades - earlier.trades;
            c.rejects = rejects - earlier.rejects;
            c.resting = resting;
            return c;
        }
    }
}
