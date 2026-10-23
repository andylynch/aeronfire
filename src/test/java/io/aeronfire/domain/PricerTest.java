package io.aeronfire.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricerTest
{
    private final Pricer pricer = Pricer.defaults();

    @Test
    void priceIsParWhenYieldEqualsCoupon()
    {
        // Instrument 3 pays 6.00%; benchmark 4.00% + 200bp = 6.00%.
        assertEquals(100.0, pricer.price(3, 200), 1e-9);
    }

    @Test
    void wideningSpreadLowersPrice()
    {
        assertTrue(pricer.price(0, 451) < pricer.price(0, 450));
    }

    @Test
    void clientBuyIsQuotedTighterThanClientSell()
    {
        final int offer = pricer.quotedSpreadBps(0, true, 5_000_000L, 0);
        final int bid = pricer.quotedSpreadBps(0, false, 5_000_000L, 0);
        assertTrue(offer < bid, "offer spread " + offer + " should be tighter than bid spread " + bid);
        assertTrue(pricer.priceE6(0, offer) > pricer.priceE6(0, bid));
    }

    @Test
    void sizeWidensTheMarket()
    {
        final int small = pricer.quotedSpreadBps(0, false, 1_000_000L, 0);
        final int large = pricer.quotedSpreadBps(0, false, 50_000_000L, 0);
        assertTrue(large > small);
    }

    @Test
    void longInventoryCheapensBothSides()
    {
        final long longPosition = 100_000_000L;
        assertTrue(pricer.quotedSpreadBps(0, true, 5_000_000L, longPosition)
            > pricer.quotedSpreadBps(0, true, 5_000_000L, 0));
        assertTrue(pricer.quotedSpreadBps(0, false, 5_000_000L, longPosition)
            > pricer.quotedSpreadBps(0, false, 5_000_000L, 0));
    }

    @Test
    void cs01IsNegativeForLongPosition()
    {
        assertTrue(pricer.cs01(0, 450, 10_000_000L) < 0);
        assertTrue(pricer.cs01(0, 450, -10_000_000L) > 0);
    }
}
