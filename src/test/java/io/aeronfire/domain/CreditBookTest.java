package io.aeronfire.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditBookTest
{
    @Test
    void unknownAndOutOfRangeClientsAreRejected()
    {
        final CreditBook book = new CreditBook();
        assertEquals(CreditBook.Check.UNKNOWN_CLIENT, book.check(3, 1));
        assertEquals(CreditBook.Check.UNKNOWN_CLIENT, book.check(-1, 1));
        assertEquals(CreditBook.Check.UNKNOWN_CLIENT, book.check(CreditBook.CAPACITY, 1));
    }

    @Test
    void tierCapsTicketSize()
    {
        final CreditBook book = new CreditBook();
        book.upsert(1, Tier.TIER_3, 1_000_000_000L, 0);
        assertEquals(CreditBook.Check.OK, book.check(1, Tier.TIER_3.maxNotional()));
        assertEquals(CreditBook.Check.ABOVE_TIER_MAX, book.check(1, Tier.TIER_3.maxNotional() + 1));
    }

    @Test
    void utilisationCountsAgainstLimit()
    {
        final CreditBook book = new CreditBook();
        book.upsert(1, Tier.TIER_2, 30_000_000L, 0);

        assertEquals(CreditBook.Check.OK, book.check(1, 20_000_000L));
        book.consume(1, 20_000_000L);

        assertEquals(CreditBook.Check.OK, book.check(1, 10_000_000L));
        assertEquals(CreditBook.Check.CREDIT_LIMIT, book.check(1, 10_000_001L));
        assertEquals(20_000_000L, book.utilised(1));
    }

    @Test
    void resetClearsUtilisationForOneOrAll()
    {
        final CreditBook book = CreditBook.withDemoClients();
        book.consume(0, 10);
        book.consume(1, 20);

        assertEquals(1, book.resetUtilisation(0));
        assertEquals(0, book.utilised(0));
        assertEquals(20, book.utilised(1));

        assertEquals(10, book.resetUtilisation(-1));
        assertEquals(0, book.utilised(1));
        assertEquals(0, book.resetUtilisation(500));
    }

    @Test
    void demoClientsCoverAllTiers()
    {
        final CreditBook book = CreditBook.withDemoClients();
        assertEquals(Tier.TIER_1, book.tier(0));
        assertEquals(Tier.TIER_2, book.tier(2));
        assertEquals(Tier.TIER_3, book.tier(9));
    }

    @Test
    void betterTiersGetBetterTerms()
    {
        assertTrue(Tier.TIER_1.spreadAdjustBps() < Tier.TIER_2.spreadAdjustBps());
        assertTrue(Tier.TIER_2.spreadAdjustBps() < Tier.TIER_3.spreadAdjustBps());
        assertTrue(Tier.TIER_1.maxNotional() > Tier.TIER_3.maxNotional());
        assertTrue(Tier.TIER_1.quoteTtlMs() > Tier.TIER_3.quoteTtlMs());
        assertNull(Tier.fromCode(3));
    }
}
