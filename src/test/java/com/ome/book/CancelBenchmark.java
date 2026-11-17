package com.ome.book;

import com.ome.model.OrderType;
import com.ome.model.Side;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Cost of cancelling a random order out of one price level's queue and queueing it again,
 * for different queue lengths:
 *
 * <ul>
 *   <li>{@code intrusiveList}: v2's {@link PriceLevel}, where the order unlinks itself in O(1).</li>
 *   <li>{@code arrayDequeScan}: v1's approach, an {@code ArrayDeque} searched by id, O(n).</li>
 * </ul>
 *
 * <p>Run: {@code ./mvnw test-compile exec:exec -Djmh.args="CancelBenchmark"}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class CancelBenchmark {

    @Param({"10", "100", "1000", "10000"})
    public int queueLength;

    private Order[] orders;
    private PriceLevel level;
    private ArrayDeque<Order> deque;
    private SplittableRandom random;

    @Setup(Level.Trial)
    public void fill() {
        orders = new Order[queueLength];
        level = new PriceLevel(10_000);
        deque = new ArrayDeque<>(queueLength);
        for (int i = 0; i < queueLength; i++) {
            Order order = new Order(i + 1, "A", Side.BUY, OrderType.LIMIT, 10_000, 100);
            orders[i] = order;
            level.append(order);
            deque.addLast(order);
        }
        random = new SplittableRandom(7);
    }

    @Benchmark
    public long intrusiveList() {
        Order order = orders[random.nextInt(queueLength)];
        level.remove(order);
        level.append(order);
        return level.totalQty();
    }

    @Benchmark
    public long arrayDequeScan() {
        long id = orders[random.nextInt(queueLength)].id;
        Iterator<Order> it = deque.iterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (order.id == id) {
                it.remove();
                deque.addLast(order);
                break;
            }
        }
        return deque.size();
    }
}
