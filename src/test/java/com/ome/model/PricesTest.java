package com.ome.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Prices, names and instruments")
class PricesTest {

    @Test
    @DisplayName("Parses and formats fixed-point prices exactly")
    void parseAndFormat() {
        assertEquals(10_125, Prices.parse("101.25"));
        assertEquals(10_000, Prices.parse("100"));
        assertEquals(10_050, Prices.parse(" 100.5 "));
        assertEquals(10_050, Prices.parse("100.500"));
        assertEquals("101.25", Prices.format(10_125));
        assertEquals("100.00", Prices.format(10_000));
        assertEquals("0.05", Prices.format(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.001", "abc", "", "1e30"})
    @DisplayName("Refuses more than two decimals, non-numbers and overflow")
    void rejectsBadPrices(String text) {
        assertThrows(IllegalArgumentException.class, () -> Prices.parse(text));
    }

    @Test
    @DisplayName("Symbols and accounts are restricted to safe characters")
    void names() {
        assertEquals("ACME.NS", Names.symbol("ACME.NS"));
        assertEquals("alice_01", Names.account("alice_01"));
        for (String bad : new String[] {"acme", "A|B", "", "TOOLONGSYMBOL12345", "A B"}) {
            assertThrows(IllegalArgumentException.class, () -> Names.symbol(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Names.account("a|b"));
        assertThrows(IllegalArgumentException.class, () -> Names.account(null));
    }

    @Test
    @DisplayName("Tick size check")
    void tick() {
        Instrument acme = new Instrument("ACME", 5);
        assertEquals(true, acme.isOnTick(10_005));
        assertEquals(false, acme.isOnTick(10_003));
        assertThrows(IllegalArgumentException.class, () -> new Instrument("ACME", 0));
    }
}
