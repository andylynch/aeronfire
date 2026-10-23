package io.aeronfire.domain;

/**
 * Pre-trade credit per client: tier, notional limit and utilisation.
 * <p>
 * Utilisation is gross notional traded since the last reset. A request is checked when the RFQ arrives
 * (so we don't quote what the client can't trade) and again on accept (other trades may have used the
 * headroom in between). Client ids index straight into arrays: no hashing, no allocation, deterministic.
 */
public final class CreditBook
{
    public static final int CAPACITY = 1024;

    public enum Check
    {
        OK,
        UNKNOWN_CLIENT,
        ABOVE_TIER_MAX,
        CREDIT_LIMIT
    }

    private final boolean[] known = new boolean[CAPACITY];
    private final Tier[] tier = new Tier[CAPACITY];
    private final long[] limit = new long[CAPACITY];
    private final long[] utilised = new long[CAPACITY];

    /**
     * Demo clients 0..9, mixed tiers. Identical on every member, so seeding is deterministic.
     */
    public static CreditBook withDemoClients()
    {
        final CreditBook book = new CreditBook();
        for (int clientId = 0; clientId < 10; clientId++)
        {
            final Tier t = clientId < 2 ? Tier.TIER_1 : clientId < 6 ? Tier.TIER_2 : Tier.TIER_3;
            final long clientLimit = t == Tier.TIER_1 ? 500_000_000L : t == Tier.TIER_2 ? 200_000_000L : 50_000_000L;
            book.upsert(clientId, t, clientLimit, 0);
        }
        return book;
    }

    public static boolean isValidId(final long clientId)
    {
        return clientId >= 0 && clientId < CAPACITY;
    }

    public void upsert(final int clientId, final Tier clientTier, final long clientLimit, final long clientUtilised)
    {
        known[clientId] = true;
        tier[clientId] = clientTier;
        limit[clientId] = clientLimit;
        utilised[clientId] = clientUtilised;
    }

    public void clear()
    {
        java.util.Arrays.fill(known, false);
        java.util.Arrays.fill(tier, null);
        java.util.Arrays.fill(limit, 0);
        java.util.Arrays.fill(utilised, 0);
    }

    public Check check(final long clientId, final long notional)
    {
        if (!isValidId(clientId) || !known[(int)clientId])
        {
            return Check.UNKNOWN_CLIENT;
        }

        final int id = (int)clientId;
        if (notional > tier[id].maxNotional())
        {
            return Check.ABOVE_TIER_MAX;
        }
        if (utilised[id] + notional > limit[id])
        {
            return Check.CREDIT_LIMIT;
        }
        return Check.OK;
    }

    public void consume(final int clientId, final long notional)
    {
        utilised[clientId] += notional;
    }

    public boolean isKnown(final int clientId)
    {
        return known[clientId];
    }

    public Tier tier(final int clientId)
    {
        return tier[clientId];
    }

    public long limit(final int clientId)
    {
        return limit[clientId];
    }

    public long utilised(final int clientId)
    {
        return utilised[clientId];
    }
}
