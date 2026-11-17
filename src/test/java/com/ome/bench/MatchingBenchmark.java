package com.ome.bench;

import com.ome.cli.LoadTest;
import com.ome.cli.SampleInstruments;
import com.ome.command.Command;
import com.ome.engine.MatchingEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Average cost of one command in the matching engine: a recorded random flow (55% passive
 * limits, 25% aggressive orders, 20% cancels and amends) is applied to a fresh engine per
 * invocation and the time is divided by the number of commands.
 *
 * <p>Run: {@code ./mvnw test-compile exec:exec -Djmh.args="MatchingBenchmark"}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
public class MatchingBenchmark {

    static final int COMMANDS = 200_000;

    private Command[] commands;
    private MatchingEngine engine;

    @Setup(Level.Trial)
    public void record() {
        commands = LoadTest.record(COMMANDS).toArray(new Command[0]);
    }

    @Setup(Level.Invocation)
    public void freshEngine() {
        engine = new MatchingEngine(SampleInstruments.ALL);
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS)
    public long applyRecordedFlow() {
        for (Command command : commands) {
            engine.apply(command);
        }
        return engine.lastSeq();
    }
}
