package com.ome.marketdata;

import com.ome.model.Side;
import com.ome.model.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarketDataService")
class MarketDataServiceTest {

    @Test
    @DisplayName("Tracks open, high, low, last, volume and VWAP per symbol")
    void stats() {
        MarketDataService md = new MarketDataService();
        md.onTrade(new Trade(1, "ACME", 10_000, 100, 1, 2, Side.BUY));
        md.onTrade(new Trade(2, "ACME", 10_200, 300, 3, 4, Side.BUY));
        md.onTrade(new Trade(3, "ACME", 9_900, 100, 5, 6, Side.SELL));
        md.onTrade(new Trade(4, "BOLT", 500, 1, 7, 8, Side.SELL));

        TradeStats acme = md.stats("ACME").orElseThrow();
        assertEquals(3, acme.trades());
        assertEquals(500, acme.volume());
        assertEquals(10_000, acme.open());
        assertEquals(10_200, acme.high());
        assertEquals(9_900, acme.low());
        assertEquals(9_900, acme.last());
        // (100*100.00 + 300*102.00 + 100*99.00) / 500 = 101.00
        assertEquals(10_100.0, acme.vwap(), 1e-9);
        assertTrue(acme.describe().contains("vwap=101.0000"), acme.describe());
        assertEquals(1, md.stats("BOLT").orElseThrow().trades());
        assertTrue(md.stats("NONE").isEmpty());
    }
}
