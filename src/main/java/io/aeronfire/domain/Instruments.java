package io.aeronfire.domain;

import java.time.LocalDate;

/**
 * Static reference data for a handful of illustrative high-yield corporate bonds.
 * <p>
 * Bonds rather than leveraged loans: loans aren't MiFIR financial instruments, so they wouldn't be
 * transaction-reported, and HY bonds are what an RFQ venue like Tradeweb actually trades. ISINs are made up
 * but carry valid check digits. Annual coupons, paid on the maturity date's anniversary.
 * <p>
 * Indexed by instrumentId; arrays rather than objects keep the cluster's lookups allocation-free.
 */
public final class Instruments
{
    public static final int COUNT = 5;

    private static final String[] NAMES = {
        "ACME 7.25% 2031", "NORTHWIND 8.5% 2030", "GLOBEX 10.75% 2032", "INITECH 6% 2029", "UMBRELLA 7.75% 2031" };
    private static final String[] ISINS = {
        "XS2847193054", "XS2910456727", "XS2633184598", "XS2776401288", "XS2999037414" };
    private static final String[] CURRENCIES = { "EUR", "EUR", "USD", "GBP", "EUR" };
    private static final double[] COUPON_PCT = { 7.25, 8.50, 10.75, 6.00, 7.75 };
    private static final int[] MATURITY_YEARS = { 5, 4, 6, 3, 5 };
    private static final LocalDate[] MATURITIES = {
        LocalDate.of(2031, 6, 15), LocalDate.of(2030, 3, 1), LocalDate.of(2032, 10, 15),
        LocalDate.of(2029, 2, 1), LocalDate.of(2031, 9, 15) };
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

    public static String isin(final int instrumentId)
    {
        return ISINS[instrumentId];
    }

    public static String currency(final int instrumentId)
    {
        return CURRENCIES[instrumentId];
    }

    public static double couponPct(final int instrumentId)
    {
        return COUPON_PCT[instrumentId];
    }

    /**
     * Whole years to maturity used by the pricer's simple annual-coupon model.
     */
    public static int maturityYears(final int instrumentId)
    {
        return MATURITY_YEARS[instrumentId];
    }

    public static LocalDate maturity(final int instrumentId)
    {
        return MATURITIES[instrumentId];
    }

    public static int baseSpreadBps(final int instrumentId)
    {
        return BASE_SPREAD_BPS[instrumentId];
    }
}
