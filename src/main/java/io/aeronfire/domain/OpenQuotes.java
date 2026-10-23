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
    private final int[] clientId;
    private final int[] instrumentId;
    private final boolean[] clientBuys;
    private final long[] notional;
    private final long[] priceE6;
    private final long[] validUntil;
    private final int[] spreadBps;
    // Exact index of live quotes by (rfq id, session), chained through the slots: lets a resent RFQ find its
    // quote. It holds precisely the live quotes, so it is identical on every member however the state was built.
    private final int[] bucketHead;
    private final int[] chainNext;

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
        clientId = new int[capacityPowerOfTwo];
        instrumentId = new int[capacityPowerOfTwo];
        clientBuys = new boolean[capacityPowerOfTwo];
        notional = new long[capacityPowerOfTwo];
        priceE6 = new long[capacityPowerOfTwo];
        validUntil = new long[capacityPowerOfTwo];
        spreadBps = new int[capacityPowerOfTwo];
        bucketHead = new int[capacityPowerOfTwo];
        chainNext = new int[capacityPowerOfTwo];
        java.util.Arrays.fill(bucketHead, -1);
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
        final int client,
        final int instrument,
        final boolean buys,
        final long size,
        final long price,
        final long expiry)
    {
        put(id, rfq, sessionId, client, instrument, buys, size, price, expiry, 0);
    }

    public void put(
        final long id,
        final long rfq,
        final long sessionId,
        final int client,
        final int instrument,
        final boolean buys,
        final long size,
        final long price,
        final long expiry,
        final int spread)
    {
        final int slot = (int)(id & mask);
        if (quoteId[slot] != NONE)
        {
            unlink(slot); // the id wrapped round onto a live quote: evict it
        }
        quoteId[slot] = id;
        spreadBps[slot] = spread;
        rfqId[slot] = rfq;
        clusterSessionId[slot] = sessionId;
        clientId[slot] = client;
        instrumentId[slot] = instrument;
        clientBuys[slot] = buys;
        notional[slot] = size;
        priceE6[slot] = price;
        validUntil[slot] = expiry;
        final int bucket = bucket(rfq, sessionId);
        chainNext[slot] = bucketHead[bucket];
        bucketHead[bucket] = slot;
    }

    private int bucket(final long rfq, final long sessionId)
    {
        final long h = (rfq * 0x9E3779B97F4A7C15L) ^ (sessionId * 0xC2B2AE3D27D4EB4FL);
        return (int)(h ^ (h >>> 32)) & mask;
    }

    private void unlink(final int slot)
    {
        final int bucket = bucket(rfqId[slot], clusterSessionId[slot]);
        int cursor = bucketHead[bucket];
        if (cursor == slot)
        {
            bucketHead[bucket] = chainNext[slot];
            return;
        }
        while (cursor >= 0)
        {
            if (chainNext[cursor] == slot)
            {
                chainNext[cursor] = chainNext[slot];
                return;
            }
            cursor = chainNext[cursor];
        }
    }

    /**
     * @return slot of the live quote issued for this RFQ on this session, or -1. Lets a client resend an RFQ
     * after a failover and get the same quote back rather than a second one.
     */
    public int findByRfq(final long rfq, final long sessionId)
    {
        for (int slot = bucketHead[bucket(rfq, sessionId)]; slot >= 0; slot = chainNext[slot])
        {
            if (rfqId[slot] == rfq && clusterSessionId[slot] == sessionId)
            {
                return slot;
            }
        }
        return -1;
    }

    public int spreadBpsAt(final int slot)
    {
        return spreadBps[slot];
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
        if (quoteId[slot] != NONE)
        {
            unlink(slot);
        }
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

    public int clientIdAt(final int slot)
    {
        return clientId[slot];
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
                remove(slot);
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
