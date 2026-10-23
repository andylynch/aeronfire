package io.aeronfire.domain;

/**
 * Fixed-capacity store of live quotes, structure-of-arrays and preallocated so the hot path never allocates.
 * <p>
 * Quote ids are issued sequentially, so {@code quoteId & mask} gives the slot. A slot is overwritten
 * when the id wraps round; storing the full id in the slot detects that and treats the old quote as unknown.
 */
public final class OpenQuotes
{
    public static final long NONE = -1;

    private final int mask;
    private final long[] quoteId;
    private final long[] rfqId;
    private final long[] clusterSessionId;
    private final int[] instrumentId;
    private final boolean[] clientBuys;
    private final long[] notional;
    private final long[] priceE6;
    private final long[] validUntil;

    public OpenQuotes(final int capacityPowerOfTwo)
    {
        if (Integer.bitCount(capacityPowerOfTwo) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two: " + capacityPowerOfTwo);
        }

        mask = capacityPowerOfTwo - 1;
        quoteId = new long[capacityPowerOfTwo];
        rfqId = new long[capacityPowerOfTwo];
        clusterSessionId = new long[capacityPowerOfTwo];
        instrumentId = new int[capacityPowerOfTwo];
        clientBuys = new boolean[capacityPowerOfTwo];
        notional = new long[capacityPowerOfTwo];
        priceE6 = new long[capacityPowerOfTwo];
        validUntil = new long[capacityPowerOfTwo];
        java.util.Arrays.fill(quoteId, NONE);
    }

    public int capacity()
    {
        return mask + 1;
    }

    public void put(
        final long id,
        final long rfq,
        final long sessionId,
        final int instrument,
        final boolean buys,
        final long size,
        final long price,
        final long expiry)
    {
        final int slot = (int)(id & mask);
        quoteId[slot] = id;
        rfqId[slot] = rfq;
        clusterSessionId[slot] = sessionId;
        instrumentId[slot] = instrument;
        clientBuys[slot] = buys;
        notional[slot] = size;
        priceE6[slot] = price;
        validUntil[slot] = expiry;
    }

    /**
     * @return slot index for a live quote with this id, or -1 if unknown or already consumed.
     */
    public int find(final long id)
    {
        if (id < 0)
        {
            return -1;
        }
        final int slot = (int)(id & mask);
        return quoteId[slot] == id ? slot : -1;
    }

    public void remove(final int slot)
    {
        quoteId[slot] = NONE;
    }

    public long quoteIdAt(final int slot)
    {
        return quoteId[slot];
    }

    public long rfqIdAt(final int slot)
    {
        return rfqId[slot];
    }

    public long clusterSessionIdAt(final int slot)
    {
        return clusterSessionId[slot];
    }

    public int instrumentIdAt(final int slot)
    {
        return instrumentId[slot];
    }

    public boolean clientBuysAt(final int slot)
    {
        return clientBuys[slot];
    }

    public long notionalAt(final int slot)
    {
        return notional[slot];
    }

    public long priceE6At(final int slot)
    {
        return priceE6[slot];
    }

    public long validUntilAt(final int slot)
    {
        return validUntil[slot];
    }

    /**
     * Drop every quote that has expired by {@code now}, so snapshots carry only live quotes.
     */
    public void expire(final long now)
    {
        for (int slot = 0; slot <= mask; slot++)
        {
            if (quoteId[slot] != NONE && validUntil[slot] < now)
            {
                quoteId[slot] = NONE;
            }
        }
    }

    public int liveCount()
    {
        int count = 0;
        for (final long id : quoteId)
        {
            if (id != NONE)
            {
                count++;
            }
        }
        return count;
    }
}
