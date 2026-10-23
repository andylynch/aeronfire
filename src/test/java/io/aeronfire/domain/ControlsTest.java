package io.aeronfire.domain;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlsTest
{
    @Test
    void inventoryLimitsApplyToThePositionAfterTheTrade()
    {
        final InventoryBook book = new InventoryBook();
        book.set(0, 10_000_000L, 5_000_000L, false);

        assertEquals(InventoryBook.Check.OK, book.check(0, 0, true, 5_000_000L));
        assertEquals(InventoryBook.Check.LIMIT, book.check(0, 0, true, 6_000_000L), "client buys, we go short");
        assertEquals(InventoryBook.Check.LIMIT, book.check(0, 8_000_000L, false, 3_000_000L), "we go long");

        book.set(0, 10_000_000L, 5_000_000L, true);
        assertEquals(InventoryBook.Check.HALTED, book.check(0, 0, true, 1));
    }

    @Test
    void throttleCountsPerClientPerSecond()
    {
        final AlgoControls algo = new AlgoControls();
        algo.set(false, 500, 2);

        assertEquals(AlgoControls.Check.OK, algo.onRfq(1, 1_000));
        assertEquals(AlgoControls.Check.OK, algo.onRfq(1, 1_500));
        assertEquals(AlgoControls.Check.THROTTLED, algo.onRfq(1, 1_900));
        assertEquals(AlgoControls.Check.OK, algo.onRfq(2, 1_900), "per client");
        assertEquals(AlgoControls.Check.OK, algo.onRfq(1, 2_000), "new second");
    }

    @Test
    void throttleReplayRebuildsIdenticalState()
    {
        final AlgoControls live = new AlgoControls();
        final AlgoControls replayed = new AlgoControls();
        live.set(false, 500, 2);
        replayed.set(false, 500, 2);

        live.onRfq(1, 1_000);
        live.onRfq(1, 1_100);
        replayed.replayRfq(1, 1_000);
        replayed.replayRfq(1, 1_100);

        assertEquals(live.onRfq(1, 1_200), replayed.onRfq(1, 1_200));
    }

    @Test
    void killSwitchAndCollar()
    {
        final AlgoControls algo = new AlgoControls();
        algo.set(true, 100, 0);
        assertEquals(AlgoControls.Check.KILL_SWITCH, algo.onRfq(1, 0));
        assertTrue(algo.withinCollar(100_500_000L, 100_000_000L));
        assertFalse(algo.withinCollar(102_000_000L, 100_000_000L));
    }

    @Test
    void journalKeepsLatestEventsInSequence()
    {
        final EventJournal journal = new EventJournal(4);
        for (int i = 0; i < 6; i++)
        {
            final int slot = journal.append(EventJournal.RFQ, i);
            journal.ids(slot, i, -1, -1);
        }
        assertEquals(2, journal.oldest());
        assertEquals(6, journal.next());
        assertEquals(-1, journal.slotOf(1), "evicted");
        assertEquals(5, journal.rfqIdAt(journal.slotOf(5)));
        assertEquals(-1, journal.userIdAt(journal.slotOf(5)));
    }

    @Test
    void pendingChangesKeepTheCommandVerbatim()
    {
        final PendingChanges pending = new PendingChanges();
        final UnsafeBuffer command = new UnsafeBuffer(new byte[] { 1, 2, 3, 4 });
        final int slot = pending.add(7, 5, 3, 1_000, command, 1, 2);

        final UnsafeBuffer out = new UnsafeBuffer(new byte[8]);
        assertEquals(2, pending.copyCommand(slot, out));
        assertEquals(2, out.getByte(0));
        assertEquals(3, out.getByte(1));
        assertEquals(slot, pending.find(7));
        pending.remove(slot);
        assertEquals(-1, pending.find(7));
    }

    @Test
    void eligibilityDefaultsToEligible()
    {
        final ClientEligibility eligibility = new ClientEligibility();
        assertTrue(eligibility.kycValid(3));
        assertFalse(eligibility.sanctioned(3));
        eligibility.set(3, false, true);
        assertFalse(eligibility.kycValid(3));
        assertTrue(eligibility.sanctioned(3));
        assertFalse(eligibility.isDefault(3));
    }
}
