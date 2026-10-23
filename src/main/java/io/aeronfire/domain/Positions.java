package io.aeronfire.domain;

/**
 * Net notional per instrument. Long is positive. Array-backed: instruments are a small, dense id range.
 */
public final class Positions
{
    private final long[] netNotional = new long[Instruments.COUNT];

    public long net(final int instrumentId)
    {
        return netNotional[instrumentId];
    }

    /**
     * Apply a fill from our perspective.
     *
     * @param clientBuys true if the client bought, i.e. we sold.
     */
    public void applyFill(final int instrumentId, final boolean clientBuys, final long notional)
    {
        netNotional[instrumentId] += clientBuys ? -notional : notional;
    }

    /**
     * Manual adjustment outside the RFQ flow (opening position, book transfer, error correction).
     *
     * @return the new net position.
     */
    public long adjust(final int instrumentId, final long delta)
    {
        netNotional[instrumentId] += delta;
        return netNotional[instrumentId];
    }

    public void set(final int instrumentId, final long net)
    {
        netNotional[instrumentId] = net;
    }

    public int nonZeroCount()
    {
        int count = 0;
        for (final long n : netNotional)
        {
            if (n != 0)
            {
                count++;
            }
        }
        return count;
    }
}
