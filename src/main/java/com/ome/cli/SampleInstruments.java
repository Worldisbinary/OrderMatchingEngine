package com.ome.cli;

import com.ome.model.Instrument;

import java.util.List;

/** The two instruments the demo, server and load test list: one with a 0.05 tick, one with 0.01. */
public final class SampleInstruments {

    public static final Instrument ACME = new Instrument("ACME", 5);
    public static final Instrument BOLT = new Instrument("BOLT", 1);
    public static final List<Instrument> ALL = List.of(ACME, BOLT);

    private SampleInstruments() {
    }
}
