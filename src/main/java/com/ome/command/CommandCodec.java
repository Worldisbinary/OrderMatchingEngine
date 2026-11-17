package com.ome.command;

import com.ome.model.OrderType;
import com.ome.model.Side;

/**
 * One-line text encoding of commands, used by the journal. Fields are separated by '|',
 * which cannot appear in a symbol or account name.
 *
 * <pre>
 * N|account|symbol|side|type|price|quantity
 * C|account|symbol|orderId
 * R|account|symbol|orderId|newPrice|newQuantity
 * </pre>
 */
public final class CommandCodec {

    private CommandCodec() {
    }

    public static String encode(Command command) {
        if (command instanceof NewOrder n) {
            return "N|" + n.account() + '|' + n.symbol() + '|' + n.side() + '|' + n.type()
                    + '|' + n.price() + '|' + n.quantity();
        }
        if (command instanceof CancelOrder c) {
            return "C|" + c.account() + '|' + c.symbol() + '|' + c.orderId();
        }
        ReplaceOrder r = (ReplaceOrder) command;
        return "R|" + r.account() + '|' + r.symbol() + '|' + r.orderId() + '|' + r.newPrice()
                + '|' + r.newQuantity();
    }

    /** @throws IllegalArgumentException if the line is not a valid encoded command */
    public static Command decode(String line) {
        String[] f = line.split("\\|", -1);
        try {
            switch (f[0]) {
                case "N":
                    expect(f, 7, line);
                    return new NewOrder(f[1], f[2], Side.valueOf(f[3]), OrderType.valueOf(f[4]),
                            Long.parseLong(f[5]), Long.parseLong(f[6]));
                case "C":
                    expect(f, 4, line);
                    return new CancelOrder(f[1], f[2], Long.parseLong(f[3]));
                case "R":
                    expect(f, 6, line);
                    return new ReplaceOrder(f[1], f[2], Long.parseLong(f[3]), Long.parseLong(f[4]),
                            Long.parseLong(f[5]));
                default:
                    throw new IllegalArgumentException("unknown command type in: " + line);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number in: " + line, e);
        }
    }

    private static void expect(String[] fields, int count, String line) {
        if (fields.length != count) {
            throw new IllegalArgumentException("expected " + count + " fields in: " + line);
        }
    }
}
