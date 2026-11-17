package com.ome.model;

import java.util.regex.Pattern;

/**
 * Validation for symbols and account names. Both appear in the journal and on the wire,
 * so they are restricted to characters that can never collide with a separator.
 */
public final class Names {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9._-]{0,15}");
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    private Names() {
    }

    /** Returns the symbol if it is 1 to 16 characters: upper-case letters, digits, '.', '_' or '-'. */
    public static String symbol(String symbol) {
        if (symbol == null || !SYMBOL.matcher(symbol).matches()) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return symbol;
    }

    /** Returns the account if it is 1 to 16 characters: letters, digits, '_' or '-'. */
    public static String account(String account) {
        if (account == null || !ACCOUNT.matcher(account).matches()) {
            throw new IllegalArgumentException("invalid account: " + account);
        }
        return account;
    }
}
