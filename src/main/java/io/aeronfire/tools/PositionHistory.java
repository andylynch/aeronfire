package io.aeronfire.tools;

import io.aeronfire.domain.EventJournal;
import io.aeronfire.domain.Instruments;

import java.util.ArrayList;
import java.util.List;

/**
 * Positions at any past point in the event journal, reconstructed from a snapshot: start from the snapshot's
 * positions and its journalled events, and undo the events after the point asked for. Pure, so it can be
 * tested without a cluster or archive.
 */
public final class PositionHistory
{
    /**
     * The journal events that move positions.
     */
    public record Move(long seq, long timestampMs, byte type, int instrumentId, boolean clientBuys, long notional)
    {
    }

    private final long[] snapshotNet = new long[Instruments.COUNT];
    private final List<Move> moves = new ArrayList<>();
    private long snapshotNextSeq;
    private long oldestSeq = Long.MAX_VALUE;

    public void setSnapshotPosition(final int instrumentId, final long net)
    {
        snapshotNet[instrumentId] = net;
    }

    /**
     * @param instrumentId -1 for events that don't concern an instrument.
     */
    public void addEvent(
        final long seq, final long timestampMs, final byte type, final int instrumentId, final int side,
        final long notional)
    {
        oldestSeq = Math.min(oldestSeq, seq);
        snapshotNextSeq = Math.max(snapshotNextSeq, seq + 1);
        if ((type == EventJournal.TRADE || type == EventJournal.POSITION_ADJUST) && Instruments.isValid(instrumentId))
        {
            // Side 0 = BUY (client buys, we sell).
            moves.add(new Move(seq, timestampMs, type, instrumentId, side == 0, notional));
        }
    }

    public long oldestSeq()
    {
        return oldestSeq == Long.MAX_VALUE ? 0 : oldestSeq;
    }

    public long nextSeq()
    {
        return snapshotNextSeq;
    }

    /**
     * First event at or after this time, or the snapshot's next sequence if none: "as of this time" means
     * before that event.
     */
    public long seqAtTime(final long timestampMs)
    {
        for (final Move m : moves)
        {
            if (m.timestampMs() >= timestampMs)
            {
                return m.seq();
            }
        }
        return snapshotNextSeq;
    }

    /**
     * Net positions as they stood immediately before event {@code asOfSeq} was applied.
     *
     * @throws IllegalArgumentException if that point is older than the journal the snapshot carried.
     */
    public long[] positionsBefore(final long asOfSeq)
    {
        if (asOfSeq < oldestSeq())
        {
            throw new IllegalArgumentException("sequence " + asOfSeq + " is older than the snapshot's journal (oldest " +
                oldestSeq() + "); use an earlier snapshot");
        }
        final long[] net = snapshotNet.clone();
        for (int i = moves.size() - 1; i >= 0; i--)
        {
            final Move m = moves.get(i);
            if (m.seq() < asOfSeq)
            {
                break;
            }
            // Undo: a trade added -notional when the client bought, +notional when the client sold; an
            // adjustment added its (signed) notional.
            final long applied = m.type() == EventJournal.TRADE ? (m.clientBuys() ? -m.notional() : m.notional()) : m.notional();
            net[m.instrumentId()] -= applied;
        }
        return net;
    }
}
