package io.aeronfire.tools;

import io.aeronfire.domain.EventJournal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PositionHistoryTest
{
    private static PositionHistory history()
    {
        final PositionHistory h = new PositionHistory();
        // Final positions in the snapshot: instrument 0 net -300 (client bought 1000 then 500, sold 1200, adj +400... see below).
        h.addEvent(10, 1_000, EventJournal.TRADE, 0, 0, 1_000);          // client buys: net -1000
        h.addEvent(11, 2_000, EventJournal.TRADE, 0, 0, 500);            // client buys: net -1500
        h.addEvent(12, 3_000, EventJournal.TRADE, 0, 1, 1_200);          // client sells: net -300
        h.addEvent(13, 4_000, EventJournal.POSITION_ADJUST, 0, 0, 400);  // adjust +400: net 100
        h.addEvent(14, 5_000, EventJournal.QUOTE, -1, 0, 0);             // irrelevant
        h.setSnapshotPosition(0, 100);
        return h;
    }

    @Test
    void rollsBackToEachPoint()
    {
        final PositionHistory h = history();
        assertEquals(100, h.positionsBefore(15)[0]);
        assertEquals(-300, h.positionsBefore(13)[0]);
        assertEquals(-1_500, h.positionsBefore(12)[0]);
        assertEquals(-1_000, h.positionsBefore(11)[0]);
        assertEquals(0, h.positionsBefore(10)[0]);
    }

    @Test
    void timeMapsToFirstLaterEvent()
    {
        final PositionHistory h = history();
        assertEquals(11, h.seqAtTime(1_500));
        assertEquals(10, h.seqAtTime(0));
        assertEquals(15, h.seqAtTime(9_999));
    }

    @Test
    void refusesPointsOlderThanJournal()
    {
        assertThrows(IllegalArgumentException.class, () -> history().positionsBefore(9));
    }
}
