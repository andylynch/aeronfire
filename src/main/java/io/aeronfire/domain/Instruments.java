package io.aeronfire.domain;

/**
 * Static reference data for a handful of illustrative private-credit loans.
 * Indexed by instrumentId; arrays rather than objects keep lookups allocation-free
 * and the layout friendly to the cache.
 */
public final class Instruments
{
    public static final int COUNT = 5;

    private static final String[] NAMES = {
        "ACME TL-B 2031", "NORTHWIND UNITRANCHE 2030", "GLOBEX 2L 2032", "INITECH TL-A 2029", "UMBRELLA DDTL 2031" };
    private static final double[] COUPON_PCT = { 7.25, 8.50, 10.75, 6.00, 7.75 };
    private static final int[] MATURITY_YEARS = { 5, 4, 6, 3, 5 };
    private static final int[] BASE_SPREAD_BPS = { 450, 575, 825, 325, 500 };

    private Instruments()
    {
    }

    public static boolean isValid(final int instrumentId)
    {
        return instrumentId >= 0 && instrumentId < COUNT;
    }

    public static String name(final int instrumentId)
    {
        return NAMES[instrumentId];
    }

    public static double couponPct(final int instrumentId)
    {
        return COUPON_PCT[instrumentId];
    }

    public static int maturityYears(final int instrumentId)
    {
        return MATURITY_YEARS[instrumentId];
    }

    public static int baseSpreadBps(final int instrumentId)
    {
        return BASE_SPREAD_BPS[instrumentId];
    }
}
