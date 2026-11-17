package com.ome.command;

/**
 * An instruction to the engine. Commands are the engine's only input: replaying the same
 * commands in the same order always produces the same reports, trades and book.
 */
public sealed interface Command permits NewOrder, CancelOrder, ReplaceOrder {

    /** The account sending the command. Only the owning account can cancel or amend an order. */
    String account();

    String symbol();
}
