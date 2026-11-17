package com.ome.support;

import com.ome.model.ExecType;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;
import com.ome.model.Trade;

import java.util.ArrayList;
import java.util.List;

/** Test listener that keeps every report and trade. */
public final class Recorder implements ExecutionListener {

    public final List<ExecutionReport> reports = new ArrayList<>();
    public final List<Trade> trades = new ArrayList<>();

    @Override
    public void onReport(ExecutionReport report) {
        reports.add(report);
    }

    @Override
    public void onTrade(Trade trade) {
        trades.add(trade);
    }

    public void clear() {
        reports.clear();
        trades.clear();
    }

    public List<ExecutionReport> reportsFor(long orderId) {
        return reports.stream().filter(r -> r.orderId() == orderId).toList();
    }

    /** The most recent report about {@code orderId}. */
    public ExecutionReport last(long orderId) {
        List<ExecutionReport> mine = reportsFor(orderId);
        if (mine.isEmpty()) {
            throw new AssertionError("no reports for order " + orderId);
        }
        return mine.get(mine.size() - 1);
    }

    public List<ExecutionReport> ofType(ExecType type) {
        return reports.stream().filter(r -> r.execType() == type).toList();
    }
}
