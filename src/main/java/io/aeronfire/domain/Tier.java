package io.aeronfire.domain;

/**
 * Client tier. Better tiers get tighter spreads, bigger maximum size and longer-lived quotes.
 * Values are illustrative.
 */
public enum Tier
{
    TIER_1(-5, 50_000_000L, 3_000),
    TIER_2(0, 25_000_000L, 2_000),
    TIER_3(10, 10_000_000L, 1_000);

    private static final Tier[] VALUES = values();

    private final int spreadAdjustBps;
    private final long maxNotional;
    private final long quoteTtlMs;

    Tier(final int spreadAdjustBps, final long maxNotional, final long quoteTtlMs)
    {
        this.spreadAdjustBps = spreadAdjustBps;
        this.maxNotional = maxNotional;
        this.quoteTtlMs = quoteTtlMs;
    }

    /**
     * Added to the quoted spread: negative improves the client's price on both sides.
     */
    public int spreadAdjustBps()
    {
        return spreadAdjustBps;
    }

    public long maxNotional()
    {
        return maxNotional;
    }

    public long quoteTtlMs()
    {
        return quoteTtlMs;
    }

    /**
     * Allocation-free lookup by ordinal; returns null for an out-of-range code.
     */
    public static Tier fromCode(final int code)
    {
        return code >= 0 && code < VALUES.length ? VALUES[code] : null;
    }
}
