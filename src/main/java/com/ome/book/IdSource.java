package com.ome.book;

/** Supplies report sequence numbers and trade ids to order books. */
public interface IdSource {

    long nextSeq();

    long nextTradeId();
}
