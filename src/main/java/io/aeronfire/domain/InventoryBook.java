package io.aeronfire.domain;

/**
 * Per-instrument inventory controls: maximum long and short position, and a quoting halt.
 * <p>
 * Checked on the RFQ against the position the trade would leave us with, and again on accept.
 * Lives in the cluster, so every gateway sees the same limits and the same position.
 */
public final class InventoryBook
{
    public static final long DEFAULT_LIMIT = 100_000_000L;

    public enum Check
    {
        OK,
        HALTED,
        LIMIT
    }

    private final long[] maxLong = new long[Instruments.COUNT];
    private final long[] maxShort = new long[Instruments.COUNT];
    private final boolean[] halted = new boolean[Instruments.COUNT];

    public InventoryBook()
    {
        java.util.Arrays.fill(maxLong, DEFAULT_LIMIT);
        java.util.Arrays.fill(maxShort, DEFAULT_LIMIT);
    }

    public void set(final int instrumentId, final long longLimit, final long shortLimit, final boolean isHalted)
    {
        maxLong[instrumentId] = longLimit;
        maxShort[instrumentId] = shortLimit;
        halted[instrumentId] = isHalted;
    }

    /**
     * @param currentNet  our net position now (long positive).
     * @param clientBuys  true if the client buys from us, so we'd go shorter.
     */
    public Check check(final int instrumentId, final long currentNet, final boolean clientBuys, final long notional)
    {
        if (halted[instrumentId])
        {
            return Check.HALTED;
        }
        final long after = clientBuys ? currentNet - notional : currentNet + notional;
        if (after > maxLong[instrumentId] || -after > maxShort[instrumentId])
        {
            return Check.LIMIT;
        }
        return Check.OK;
    }

    public long maxLong(final int instrumentId)
    {
        return maxLong[instrumentId];
    }

    public long maxShort(final int instrumentId)
    {
        return maxShort[instrumentId];
    }

    public boolean isHalted(final int instrumentId)
    {
        return halted[instrumentId];
    }
}
