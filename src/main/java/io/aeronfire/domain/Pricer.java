package io.aeronfire.domain;

/**
 * Spread-to-benchmark pricer for the illustrative loans.
 * <p>
 * Quoted spread = base spread, minus half the bid/offer if the client buys (we offer, tighter
 * spread, higher price), plus it if the client sells, widened for size, then skewed for our
 * inventory so we lean towards flattening risk. Price is the PV per 100 of annual coupons and
 * principal discounted at benchmark + spread.
 * <p>
 * Pure and deterministic: the clustered service calls it on the hot path, so it must give the same
 * answer on every node and must not allocate.
 */
public final class Pricer
{
    public static final long PRICE_SCALE = 1_000_000L;

    private final double benchmarkRatePct;
    private final int halfBidOfferBps;
    private final long sizeStepNotional;
    private final int sizeSkewBpsPerStep;
    private final long inventoryStepNotional;
    private final int inventorySkewBpsPerStep;

    public Pricer(
        final double benchmarkRatePct,
        final int halfBidOfferBps,
        final long sizeStepNotional,
        final int sizeSkewBpsPerStep,
        final long inventoryStepNotional,
        final int inventorySkewBpsPerStep)
    {
        this.benchmarkRatePct = benchmarkRatePct;
        this.halfBidOfferBps = halfBidOfferBps;
        this.sizeStepNotional = sizeStepNotional;
        this.sizeSkewBpsPerStep = sizeSkewBpsPerStep;
        this.inventoryStepNotional = inventoryStepNotional;
        this.inventorySkewBpsPerStep = inventorySkewBpsPerStep;
    }

    public static Pricer defaults()
    {
        return new Pricer(4.0, 12, 10_000_000L, 5, 25_000_000L, 10);
    }

    /**
     * @param instrumentId  loan being quoted.
     * @param clientBuys    true if the client is buying from us.
     * @param notional      requested size, positive.
     * @param netPosition   our current net position in this loan (long positive).
     * @return quoted spread over benchmark in bps.
     */
    public int quotedSpreadBps(
        final int instrumentId, final boolean clientBuys, final long notional, final long netPosition)
    {
        final int sizeSteps = (int)Math.min(20, notional / sizeStepNotional);
        final int widening = halfBidOfferBps + sizeSteps * sizeSkewBpsPerStep;

        // Long inventory: cheapen both sides (higher spread) so we sell more easily and buy less.
        final long inventorySteps = Math.max(-20, Math.min(20, netPosition / inventoryStepNotional));
        final int skew = (int)(inventorySteps * inventorySkewBpsPerStep);

        final int base = Instruments.baseSpreadBps(instrumentId);
        return clientBuys ? base - widening + skew : base + widening + skew;
    }

    public long priceE6(final int instrumentId, final int spreadBps)
    {
        return Math.round(price(instrumentId, spreadBps) * PRICE_SCALE);
    }

    /**
     * Clean price per 100 nominal, annual coupons, whole-year maturity.
     */
    public double price(final int instrumentId, final int spreadBps)
    {
        final double yield = (benchmarkRatePct + spreadBps / 100.0) / 100.0;
        final double coupon = Instruments.couponPct(instrumentId);
        final int years = Instruments.maturityYears(instrumentId);

        double pv = 0.0;
        double discount = 1.0;
        for (int t = 1; t <= years; t++)
        {
            discount /= (1.0 + yield);
            pv += coupon * discount;
        }

        return pv + 100.0 * discount;
    }

    /**
     * Credit spread sensitivity: P&amp;L for a 1bp widening on the given position, in currency.
     * Long positions lose when spreads widen, so the result is negative for a long.
     */
    public double cs01(final int instrumentId, final int spreadBps, final long netPosition)
    {
        final double dPrice = price(instrumentId, spreadBps + 1) - price(instrumentId, spreadBps);
        return dPrice / 100.0 * netPosition;
    }
}
