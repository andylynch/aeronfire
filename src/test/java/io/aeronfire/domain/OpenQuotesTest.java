package io.aeronfire.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenQuotesTest
{
    @Test
    void findsPutQuoteAndForgetsRemovedOne()
    {
        final OpenQuotes quotes = new OpenQuotes(8);
        quotes.put(5, 100, 42, 7, 1, true, 1_000_000L, 99_500_000L, 2_000);

        final int slot = quotes.find(5);
        assertEquals(100, quotes.rfqIdAt(slot));
        assertEquals(42, quotes.clusterSessionIdAt(slot));
        assertEquals(7, quotes.clientIdAt(slot));

        quotes.remove(slot);
        assertEquals(-1, quotes.find(5));
    }

    @Test
    void wrappedIdEvictsOlderQuote()
    {
        final OpenQuotes quotes = new OpenQuotes(8);
        quotes.put(1, 1, 1, 0, 0, true, 1, 1, 1);
        quotes.put(9, 2, 1, 0, 0, true, 1, 1, 1);

        assertEquals(-1, quotes.find(1));
        assertEquals(1, quotes.find(9));
    }

    @Test
    void expireDropsOnlyStaleQuotes()
    {
        final OpenQuotes quotes = new OpenQuotes(8);
        quotes.put(1, 1, 1, 0, 0, true, 1, 1, 100);
        quotes.put(2, 2, 1, 0, 0, true, 1, 1, 300);

        quotes.expire(200);

        assertEquals(1, quotes.liveCount());
        assertEquals(-1, quotes.find(1));
    }

    @Test
    void rejectsNonPowerOfTwoCapacity()
    {
        assertThrows(IllegalArgumentException.class, () -> new OpenQuotes(10));
    }

    @Test
    void findsTheQuoteIssuedForAnRfqAndSession()
    {
        final OpenQuotes quotes = new OpenQuotes(8);
        quotes.put(1, 100, 7, 0, 0, true, 1, 1, 1_000, 42);
        quotes.put(2, 100, 8, 0, 0, true, 1, 1, 1_000, 43); // same rfq id, different session
        quotes.put(3, 101, 7, 0, 0, true, 1, 1, 1_000, 44);

        assertEquals(1, quotes.quoteIdAt(quotes.findByRfq(100, 7)));
        assertEquals(2, quotes.quoteIdAt(quotes.findByRfq(100, 8)));
        assertEquals(44, quotes.spreadBpsAt(quotes.findByRfq(101, 7)));
        assertEquals(-1, quotes.findByRfq(102, 7));
    }

    @Test
    void indexTracksRemovalExpiryAndEviction()
    {
        final OpenQuotes quotes = new OpenQuotes(8);
        quotes.put(1, 100, 7, 0, 0, true, 1, 1, 50);
        quotes.put(2, 101, 7, 0, 0, true, 1, 1, 500);
        quotes.put(3, 102, 7, 0, 0, true, 1, 1, 500);

        quotes.remove(quotes.find(2));
        assertEquals(-1, quotes.findByRfq(101, 7));

        quotes.expire(100);
        assertEquals(-1, quotes.findByRfq(100, 7));
        assertEquals(3, quotes.quoteIdAt(quotes.findByRfq(102, 7)));

        quotes.put(11, 103, 7, 0, 0, true, 1, 1, 500); // 11 wraps onto slot 3 and evicts quote 3
        assertEquals(-1, quotes.findByRfq(102, 7));
        assertEquals(11, quotes.quoteIdAt(quotes.findByRfq(103, 7)));
    }
}
