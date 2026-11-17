package com.ome.sim;

import com.ome.command.CancelOrder;
import com.ome.command.Command;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
import com.ome.model.ExecType;
import com.ome.model.ExecutionReport;
import com.ome.model.Instrument;
import com.ome.model.OrderStatus;
import com.ome.model.Side;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Seeded generator of realistic order flow, used by the tests, the load test and the
 * benchmarks. Each instrument's mid price drifts in a random walk; the mix per command is:
 *
 * <ul>
 *   <li>55% passive limit orders, 1 to 20 ticks behind the mid</li>
 *   <li>12% aggressive limits, 5% market, 5% IOC and 3% FOK orders priced up to 4 ticks through the mid</li>
 *   <li>13% cancels and 7% replaces of orders this flow placed earlier</li>
 * </ul>
 *
 * Every price is on the instrument's tick, and every quantity is 1 to 100, so all NEW orders
 * pass validation. Cancels and replaces target ids learned through {@link #observe}; some of
 * those orders will have filled already, which exercises the engine's reject path just like
 * real cancel races.
 */
public final class RandomOrderFlow {

    private static final int REMEMBERED = 1024;

    private final SplittableRandom random;
    private final List<Instrument> instruments;
    private final String[] accounts;
    private final long[] mids;

    // Ring buffer of recently accepted orders from this flow's accounts.
    private final long[] liveIds = new long[REMEMBERED];
    private final String[] liveAccounts = new String[REMEMBERED];
    private final String[] liveSymbols = new String[REMEMBERED];
    private final Side[] liveSides = new Side[REMEMBERED];
    private int remembered;
    private int next;

    public RandomOrderFlow(long seed, List<Instrument> instruments, long startMid, String... accounts) {
        if (instruments.isEmpty() || accounts.length == 0) {
            throw new IllegalArgumentException("need at least one instrument and one account");
        }
        this.random = new SplittableRandom(seed);
        this.instruments = List.copyOf(instruments);
        this.accounts = accounts.clone();
        this.mids = new long[instruments.size()];
        for (int i = 0; i < mids.length; i++) {
            long tick = instruments.get(i).tickSize();
            mids[i] = Math.max(startMid - startMid % tick, 200 * tick);
        }
    }

    public Command next() {
        int i = random.nextInt(instruments.size());
        Instrument instrument = instruments.get(i);
        long tick = instrument.tickSize();
        if (random.nextInt(20) == 0) {
            mids[i] = Math.max(100 * tick, mids[i] + (random.nextBoolean() ? tick : -tick));
        }
        long mid = mids[i];
        String symbol = instrument.symbol();
        String account = accounts[random.nextInt(accounts.length)];
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = 1 + random.nextInt(100);
        int roll = random.nextInt(100);

        if (roll < 55 || (roll >= 80 && remembered == 0)) {
            return NewOrder.limit(account, symbol, side, behind(side, mid, tick * (1 + random.nextInt(20))), quantity);
        }
        if (roll < 80) {
            long price = behind(side, mid, -tick * random.nextInt(5));
            if (roll < 67) {
                return NewOrder.limit(account, symbol, side, price, quantity);
            }
            if (roll < 72) {
                return NewOrder.market(account, symbol, side, quantity);
            }
            if (roll < 77) {
                return NewOrder.ioc(account, symbol, side, price, quantity);
            }
            return NewOrder.fok(account, symbol, side, price, quantity);
        }

        int k = random.nextInt(remembered);
        if (roll < 93) {
            return new CancelOrder(liveAccounts[k], liveSymbols[k], liveIds[k]);
        }
        long targetMid = mids[instruments.indexOf(find(liveSymbols[k]))];
        long newPrice = behind(liveSides[k], targetMid, tick(liveSymbols[k]) * (random.nextInt(14) - 3));
        return new ReplaceOrder(liveAccounts[k], liveSymbols[k], liveIds[k], newPrice, quantity);
    }

    /** Feed the engine's reports back in so cancels and replaces can target real orders. */
    public void observe(ExecutionReport report) {
        if (report.execType() != ExecType.NEW || report.status() == OrderStatus.REJECTED || !owns(report.account())) {
            return;
        }
        liveIds[next] = report.orderId();
        liveAccounts[next] = report.account();
        liveSymbols[next] = report.symbol();
        liveSides[next] = report.side();
        next = (next + 1) % REMEMBERED;
        remembered = Math.min(remembered + 1, REMEMBERED);
    }

    /** A price {@code distance} away from the mid on the passive side (negative distance crosses it). */
    private static long behind(Side side, long mid, long distance) {
        return side == Side.BUY ? mid - distance : mid + distance;
    }

    private boolean owns(String account) {
        for (String a : accounts) {
            if (a.equals(account)) {
                return true;
            }
        }
        return false;
    }

    private Instrument find(String symbol) {
        for (Instrument instrument : instruments) {
            if (instrument.symbol().equals(symbol)) {
                return instrument;
            }
        }
        throw new IllegalStateException("unknown symbol " + symbol);
    }

    private long tick(String symbol) {
        return find(symbol).tickSize();
    }
}
