package io.aeronfire.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementsTest
{
    private final Entitlements e = Entitlements.withDemoUsers();

    @Test
    void rootHasEverything()
    {
        assertTrue(e.has(Entitlements.ROOT, Entitlements.ALL));
        assertTrue(e.clientInScope(Entitlements.ROOT, -1));
        assertEquals(Long.MAX_VALUE, e.maxCreditLimit(Entitlements.ROOT));
    }

    @Test
    void salesIsScopedToItsClients()
    {
        assertTrue(e.has(5, Entitlements.CREDIT));
        assertFalse(e.has(5, Entitlements.APPROVE));
        assertTrue(e.clientInScope(5, 7));
        assertFalse(e.clientInScope(5, 2));
        assertFalse(e.clientInScope(5, -1), "a reset of every client needs an all-clients scope");
        assertEquals(4, e.clientCount(5));
        assertEquals(6, e.clientAt(5, 0));
    }

    @Test
    void deskHeadIsScopedToItsBonds()
    {
        assertTrue(e.instrumentInScope(3, 0));
        assertFalse(e.instrumentInScope(3, 2));
        assertFalse(e.instrumentInScope(3, 99));
    }

    @Test
    void unknownUsersHaveNothing()
    {
        assertFalse(e.has(40, Entitlements.VIEW));
        assertFalse(e.has(-1, Entitlements.VIEW));
        assertFalse(e.clientInScope(40, 1));
    }
}
