package com.ome.engine;

import com.ome.book.BookSnapshot;
import com.ome.command.Command;
import com.ome.marketdata.MarketDataService;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Thread-safe front end to the {@link MatchingEngine}.
 *
 * <p>Any number of threads can call {@link #submit}. Commands go into one bounded queue and a
 * single sequencer thread takes them in order, writes each to the journal first (write-ahead),
 * then applies it to the engine. The engine is only ever touched by that thread, so matching
 * needs no locks, and the journal order is exactly the order the engine saw. A full queue
 * blocks callers (back-pressure); nothing is dropped.
 *
 * <p>Group commit: the journal is flushed to the operating system whenever the queue runs empty
 * (or every {@value #MAX_UNFLUSHED} commands under sustained load), and only then are those
 * commands' futures completed. So a burst shares one write, and a command that has been
 * acknowledged is always in the journal. The journal is not fsynced. If a journal write fails,
 * the affected futures fail and every later command is refused.
 */
public final class Exchange implements AutoCloseable {

    public static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    /** How long the idle sequencer polls before parking. */
    static final long SPIN_NANOS = 50_000;

    /** Most commands applied but not yet flushed and acknowledged. */
    static final int MAX_UNFLUSHED = 1_024;

    private sealed interface Task {
    }

    private record Submit(Command command, CompletableFuture<List<ExecutionReport>> result) implements Task {
    }

    private record Query<T>(Function<? super EngineView, ? extends T> function, CompletableFuture<T> result)
            implements Task {
    }

    private enum Stop implements Task {
        INSTANCE
    }

    private final MatchingEngine engine;
    private final MarketDataService marketData = new MarketDataService();
    private final CommandJournal journal;
    private final long recoveredCommands;
    private final BlockingQueue<Task> inbox;
    private final Thread sequencer;
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
    private boolean closed; // guarded by gate

    // Sequencer-thread state.
    private final List<ExecutionReport> batch = new ArrayList<>();
    private final List<Submit> unflushed = new ArrayList<>();
    private final List<List<ExecutionReport>> unflushedReports = new ArrayList<>();
    private IOException journalFailure;

    private Exchange(Collection<Instrument> instruments, Path journalFile, int queueCapacity) throws IOException {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive");
        }
        engine = new MatchingEngine(instruments);
        engine.addListener(marketData);
        engine.addListener(new ExecutionListener() {
            @Override
            public void onReport(ExecutionReport report) {
                batch.add(report);
            }
        });

        long replayed = 0;
        if (journalFile != null) {
            if (Files.exists(journalFile)) {
                for (Command command : CommandJournal.read(journalFile)) {
                    engine.apply(command);
                    batch.clear();
                    replayed++;
                }
            }
            journal = CommandJournal.openForAppend(journalFile);
        } else {
            journal = null;
        }
        recoveredCommands = replayed;

        inbox = new ArrayBlockingQueue<>(queueCapacity);
        sequencer = new Thread(this::run, "ome-sequencer");
        sequencer.start();
    }

    /** An in-memory exchange with no journal. */
    public static Exchange open(Collection<Instrument> instruments) {
        try {
            return new Exchange(instruments, null, DEFAULT_QUEUE_CAPACITY);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // unreachable: no file is touched
        }
    }

    /**
     * An exchange journaled to {@code journalFile}. If the file exists, its commands are
     * replayed first, restoring the exact state from before a shutdown or crash.
     */
    public static Exchange open(Collection<Instrument> instruments, Path journalFile) throws IOException {
        return open(instruments, journalFile, DEFAULT_QUEUE_CAPACITY);
    }

    public static Exchange open(Collection<Instrument> instruments, Path journalFile, int queueCapacity)
            throws IOException {
        return new Exchange(instruments, Objects.requireNonNull(journalFile, "journalFile"), queueCapacity);
    }

    /**
     * Queues a command. The future completes, after the command is journaled and applied, with
     * every report it produced (including reports for counterparties' orders) in sequence order.
     */
    public CompletableFuture<List<ExecutionReport>> submit(Command command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<List<ExecutionReport>> result = new CompletableFuture<>();
        enqueue(new Submit(command, result), result);
        return result;
    }

    /** Runs a read-only query on the sequencer thread, so it sees a consistent state. */
    public <T> CompletableFuture<T> query(Function<? super EngineView, ? extends T> function) {
        Objects.requireNonNull(function, "function");
        CompletableFuture<T> result = new CompletableFuture<>();
        enqueue(new Query<>(function, result), result);
        return result;
    }

    /** Convenience for {@code query(v -> v.snapshot(symbol, depth)).join()}. */
    public BookSnapshot snapshot(String symbol, int depth) {
        return query(view -> view.snapshot(symbol, depth)).join();
    }

    /**
     * Adds a listener for all future reports and trades. It runs on the sequencer thread, so it
     * must be fast, must not block and must not call back into this exchange and wait.
     */
    public void addListener(ExecutionListener listener) {
        Objects.requireNonNull(listener, "listener");
        query(view -> {
            engine.addListener(listener);
            return null;
        }).join();
    }

    public MarketDataService marketData() {
        return marketData;
    }

    /** Number of commands replayed from the journal when this exchange was opened. */
    public long recoveredCommands() {
        return recoveredCommands;
    }

    private void enqueue(Task task, CompletableFuture<?> result) {
        gate.readLock().lock();
        try {
            if (closed) {
                result.completeExceptionally(new IllegalStateException("exchange is closed"));
                return;
            }
            inbox.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(e);
        } finally {
            gate.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- sequencer thread

    /**
     * Takes the next task. Spins briefly before blocking: parking and waking a thread costs
     * tens of microseconds, far more than matching a command, so under load the sequencer
     * stays awake. An idle exchange parks after {@link #SPIN_NANOS} and uses no CPU.
     */
    private Task nextTask() throws InterruptedException {
        Task task = inbox.poll();
        if (task != null) {
            return task;
        }
        long deadline = System.nanoTime() + SPIN_NANOS;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
            task = inbox.poll();
            if (task != null) {
                return task;
            }
        }
        return inbox.take();
    }

    private void run() {
        try {
            while (true) {
                Task task = nextTask();
                if (task == Stop.INSTANCE) {
                    break;
                }
                execute(task);
                if (!unflushed.isEmpty() && (inbox.isEmpty() || unflushed.size() >= MAX_UNFLUSHED)) {
                    commit();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            commit();
            Task left;
            while ((left = inbox.poll()) != null) {
                fail(left, new IllegalStateException("exchange stopped"));
            }
            if (journal != null) {
                try {
                    journal.close();
                } catch (IOException e) {
                    System.err.println("ome: could not close the journal: " + e);
                }
            }
        }
    }

    private void execute(Task task) {
        if (task instanceof Submit submit) {
            if (journal != null) {
                if (journalFailure == null) {
                    try {
                        journal.append(submit.command());
                    } catch (IOException e) {
                        journalFailure = e;
                    }
                }
                if (journalFailure != null) {
                    submit.result().completeExceptionally(new UncheckedIOException(
                            "journal unavailable; command not applied", journalFailure));
                    return;
                }
            }
            batch.clear();
            List<ExecutionReport> reports;
            try {
                engine.apply(submit.command());
                reports = List.copyOf(batch);
            } catch (RuntimeException e) {
                submit.result().completeExceptionally(e);
                return;
            }
            if (journal == null) {
                submit.result().complete(reports);
            } else {
                unflushed.add(submit);
                unflushedReports.add(reports);
            }
        } else if (task instanceof Query<?> query) {
            answer(query);
        }
    }

    /** Flushes the journal, then acknowledges every command it now holds. */
    private void commit() {
        if (unflushed.isEmpty()) {
            return;
        }
        if (journalFailure == null) {
            try {
                journal.flush();
            } catch (IOException e) {
                journalFailure = e;
            }
        }
        for (int i = 0; i < unflushed.size(); i++) {
            CompletableFuture<List<ExecutionReport>> result = unflushed.get(i).result();
            if (journalFailure == null) {
                result.complete(unflushedReports.get(i));
            } else {
                result.completeExceptionally(new UncheckedIOException(
                        "journal write failed; the command may not survive a restart", journalFailure));
            }
        }
        unflushed.clear();
        unflushedReports.clear();
    }

    private <T> void answer(Query<T> query) {
        try {
            query.result().complete(query.function().apply(engine));
        } catch (RuntimeException e) {
            query.result().completeExceptionally(e);
        }
    }

    private static void fail(Task task, RuntimeException error) {
        if (task instanceof Submit submit) {
            submit.result().completeExceptionally(error);
        } else if (task instanceof Query<?> query) {
            query.result().completeExceptionally(error);
        }
    }

    // ---------------------------------------------------------------- shutdown

    /** Stops accepting commands, finishes the queued ones, flushes and closes the journal. */
    @Override
    public void close() {
        gate.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            inbox.put(Stop.INSTANCE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            gate.writeLock().unlock();
        }
        try {
            sequencer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
