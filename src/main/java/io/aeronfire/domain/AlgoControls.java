package io.aeronfire.domain;

/**
 * MiFID II RTS 6 controls for the auto-quoter (algorithmic trading by an investment firm).
 * <ul>
 *   <li><b>Kill switch</b>: when engaged, every RFQ and accept is rejected.</li>
 *   <li><b>Price collar</b>: a quote further than {@code priceCollarBps} from the reference price is not sent.</li>
 *   <li><b>Message throttle</b>: at most {@code maxRfqsPerSecond} RFQs per client per second of cluster time.</li>
 * </ul>
 * Maximum ticket size (tier), credit and inventory limits are the other pre-trade controls RTS 6 expects;
 * they live in {@link Tier}, {@link CreditBook} and {@link InventoryBook}.
 * <p>
 * The throttle uses cluster time and fixed per-client counters, so it is deterministic on every member.
 */
public final class AlgoControls
{
    public static final int DEFAULT_PRICE_COLLAR_BPS = 500;
    public static final int DEFAULT_MAX_RFQS_PER_SECOND = 50;

    public enum Check
    {
        OK,
        KILL_SWITCH,
        THROTTLED
    }

    private boolean killSwitch;
    private int priceCollarBps = DEFAULT_PRICE_COLLAR_BPS;
    private int maxRfqsPerSecond = DEFAULT_MAX_RFQS_PER_SECOND;
    private final long[] throttleSecond = new long[CreditBook.CAPACITY];
    private final int[] throttleCount = new int[CreditBook.CAPACITY];

    public void set(final boolean engaged, final int collarBps, final int rfqsPerSecond)
    {
        killSwitch = engaged;
        priceCollarBps = collarBps;
        maxRfqsPerSecond = rfqsPerSecond;
    }

    public boolean killSwitch()
    {
        return killSwitch;
    }

    public int priceCollarBps()
    {
        return priceCollarBps;
    }

    public int maxRfqsPerSecond()
    {
        return maxRfqsPerSecond;
    }

    /**
     * Count an RFQ for a client at cluster time {@code nowMs}, then check the kill switch and throttle.
     * Every RFQ is counted, so the counters can be rebuilt from the journal's RFQ events after a snapshot load.
     */
    public Check onRfq(final int clientId, final long nowMs)
    {
        final boolean overRate = count(clientId, nowMs);
        if (killSwitch)
        {
            return Check.KILL_SWITCH;
        }
        return overRate ? Check.THROTTLED : Check.OK;
    }

    /**
     * Rebuild throttle state from a journalled RFQ without checking anything.
     */
    public void replayRfq(final int clientId, final long nowMs)
    {
        count(clientId, nowMs);
    }

    private boolean count(final int clientId, final long nowMs)
    {
        if (!CreditBook.isValidId(clientId))
        {
            return false; // unknown clients are rejected by the credit check
        }
        final long second = nowMs / 1000;
        if (throttleSecond[clientId] != second)
        {
            throttleSecond[clientId] = second;
            throttleCount[clientId] = 0;
        }
        // Always count, independent of the current limit, so a replay rebuilds identical counters.
        return ++throttleCount[clientId] > maxRfqsPerSecond && maxRfqsPerSecond > 0;
    }

    /**
     * @return true if the quoted price is within the collar of the reference price.
     */
    public boolean withinCollar(final long quotedPriceE6, final long referencePriceE6)
    {
        if (referencePriceE6 <= 0)
        {
            return false;
        }
        final long deviationBps = Math.abs(quotedPriceE6 - referencePriceE6) * 10_000 / referencePriceE6;
        return deviationBps <= priceCollarBps;
    }
}
