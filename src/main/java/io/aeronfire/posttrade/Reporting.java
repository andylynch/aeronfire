package io.aeronfire.posttrade;

import io.aeronfire.refdata.Identifiers;
import io.aeronfire.refdata.Model;
import io.aeronfire.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UK MiFIR transaction reporting (RTS 22 subset) and bond post-trade transparency.
 * <p>
 * A report is an ordered map of field name to value so hospital repairs can patch any field generically.
 * Two validation layers mirror reality: our own pre-checks before sending (cheap, catch missing and
 * malformed identifiers) and the ARM's checks on receipt (also venue/TVTIC consistency, duplicates).
 * Short-selling indicator is omitted: it applies to shares and sovereign debt, not these corporate bonds.
 */
public final class Reporting
{
    public static final String FIRM_LEI = "AF00L9R346P3YD25TS98";
    public static final String VENUE = "TREU";
    public static final String OFF_VENUE = "XOFF";

    public record Issue(boolean error, String code, String field, String message)
    {
        @Override
        public String toString()
        {
            return (error ? "ERROR " : "WARN ") + code + (field.isEmpty() ? "" : " [" + field + "]") + ": " + message;
        }
    }

    private Reporting()
    {
    }

    public static Map<String, String> build(
        final TradeEvent t, final Model.Client client, final Model.Instrument bond)
    {
        final String clientLei = null == client || null == client.lei() ? "" : client.lei();
        final Map<String, String> r = new LinkedHashMap<>();
        r.put("transactionRef", "AFTR" + t.tradeId());
        r.put("executingEntityLei", FIRM_LEI);
        r.put("submittingEntityLei", FIRM_LEI);
        r.put("buyerLei", t.clientBuys() ? clientLei : FIRM_LEI);
        r.put("sellerLei", t.clientBuys() ? FIRM_LEI : clientLei);
        r.put("tradingDateTime", Instant.ofEpochMilli(t.timestampMs()).toString());
        r.put("tradingCapacity", "DEAL");
        r.put("quantity", Long.toString(t.notional()));
        r.put("quantityType", "NOML");
        r.put("price", String.format("%.6f", t.price()));
        r.put("priceType", "PERC");
        r.put("currency", null == bond ? "" : bond.currency());
        r.put("instrumentIsin", null == bond ? "" : bond.isin());
        final boolean onVenue = null != t.venueTxnId() && !t.venueTxnId().isBlank();
        r.put("venue", onVenue ? VENUE : OFF_VENUE);
        r.put("tvtic", onVenue ? t.venueTxnId() : "");
        return r;
    }

    /**
     * Our checks before submission: present, well-formed identifiers.
     */
    public static List<Issue> precheck(final Map<String, String> r, final RefLookup ref)
    {
        final List<Issue> issues = new ArrayList<>();
        for (final String f : List.of("executingEntityLei", "submittingEntityLei", "buyerLei", "sellerLei"))
        {
            final String lei = r.getOrDefault(f, "");
            if (lei.isBlank())
            {
                issues.add(new Issue(true, "MISSING_LEI", f, "LEI is mandatory in a transaction report"));
            }
            else if (!Identifiers.isValidLei(lei))
            {
                issues.add(new Issue(true, "INVALID_LEI", f, lei + " fails the ISO 17442 checksum"));
            }
            else
            {
                final Model.LeiRecord rec = ref.lei(lei);
                if (null != rec && !"ISSUED".equals(rec.registrationStatus()))
                {
                    issues.add(new Issue(false, "LEI_NOT_ISSUED", f, lei + " registration is " + rec.registrationStatus() +
                        " (renewal due " + rec.nextRenewalDate() + ")"));
                }
            }
        }
        if (!Identifiers.isValidIsin(r.getOrDefault("instrumentIsin", "")))
        {
            issues.add(new Issue(true, "INVALID_ISIN", "instrumentIsin", "ISIN missing or fails the check digit"));
        }
        if (parseLong(r.get("quantity")) <= 0)
        {
            issues.add(new Issue(true, "INVALID_QUANTITY", "quantity", "must be positive"));
        }
        if (parseDouble(r.get("price")) <= 0)
        {
            issues.add(new Issue(true, "INVALID_PRICE", "price", "must be positive"));
        }
        if (r.getOrDefault("currency", "").length() != 3)
        {
            issues.add(new Issue(true, "INVALID_CURRENCY", "currency", "must be ISO 4217"));
        }
        return issues;
    }

    /**
     * The ARM's checks on receipt: everything above plus venue consistency.
     */
    public static List<Issue> armCheck(final Map<String, String> r, final RefLookup ref, final Model.Instrument bond)
    {
        final List<Issue> issues = precheck(r, ref);
        final String venue = r.getOrDefault("venue", "");
        final String tvtic = r.getOrDefault("tvtic", "");
        if (!VENUE.equals(venue) && !OFF_VENUE.equals(venue))
        {
            issues.add(new Issue(true, "INVALID_VENUE", "venue", "expected a MIC (TREU) or XOFF, got '" + venue + "'"));
        }
        else if (VENUE.equals(venue))
        {
            if (tvtic.isBlank() || tvtic.length() > 52)
            {
                issues.add(new Issue(true, "TVTIC_REQUIRED", "tvtic", "on-venue trades need a TVTIC of up to 52 characters"));
            }
            if (null != bond && !bond.venues().contains(VENUE))
            {
                issues.add(new Issue(true, "INSTRUMENT_NOT_ADMITTED", "venue",
                    bond.isin() + " is not admitted to trading on " + VENUE + " per FIRDS"));
            }
        }
        else if (!tvtic.isBlank())
        {
            issues.add(new Issue(true, "TVTIC_NOT_ALLOWED", "tvtic", "off-venue trades must not carry a TVTIC"));
        }
        if (r.getOrDefault("buyerLei", "").equals(r.getOrDefault("sellerLei", "?")))
        {
            issues.add(new Issue(true, "BUYER_EQUALS_SELLER", "buyerLei", "buyer and seller are the same entity"));
        }
        return issues;
    }

    public static boolean hasErrors(final List<Issue> issues)
    {
        return issues.stream().anyMatch(Issue::error);
    }

    public static String json(final Map<String, String> report)
    {
        final StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (final Map.Entry<String, String> e : report.entrySet())
        {
            sb.append(first ? "" : ",").append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> fromJson(final Object o)
    {
        final Map<String, String> r = new LinkedHashMap<>();
        ((Map<String, Object>)o).forEach((k, v) -> r.put(k, null == v ? "" : v.toString()));
        return r;
    }

    // ------------------------------------------------------------------ bond post-trade transparency (APA)

    /**
     * Off-venue trades in Category 1 bonds are published by our APA. Simplified UK regime (PS24/14): trades
     * at or above the size threshold may be deferred to the end of the next business day; smaller trades are
     * published as close to real time as possible. Category 2 bonds have longer deferrals, up to 3 months.
     */
    public record Publication(String mode, long deferredUntilMs, String detail)
    {
    }

    public static final long LARGE_NOTIONAL = 5_000_000;

    public static Publication transparency(final TradeEvent t, final Model.Instrument bond)
    {
        final boolean large = t.notional() >= LARGE_NOTIONAL;
        if (bond.transparencyCategory() == 2)
        {
            return new Publication("DEFERRED", t.timestampMs() + 7L * 24 * 3600_000,
                "Category 2 bond: deferral up to 3 months permitted, applying 1 week in this model");
        }
        return large
            ? new Publication("DEFERRED", t.timestampMs() + 24L * 3600_000,
                "Category 1, size at or above " + LARGE_NOTIONAL + ": deferred one business day")
            : new Publication("IMMEDIATE", t.timestampMs(), "Category 1, below size threshold: publish now");
    }

    private static long parseLong(final String s)
    {
        try
        {
            return Long.parseLong(s);
        }
        catch (final RuntimeException ex)
        {
            return 0;
        }
    }

    private static double parseDouble(final String s)
    {
        try
        {
            return Double.parseDouble(s);
        }
        catch (final RuntimeException ex)
        {
            return 0;
        }
    }
}
