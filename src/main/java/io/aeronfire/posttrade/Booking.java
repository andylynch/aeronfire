package io.aeronfire.posttrade;

import io.aeronfire.refdata.Model;
import io.aeronfire.util.Json;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Trade booking: settlement date, accrued interest, consideration and the clearing/settlement instruction.
 * <p>
 * Simplifications: weekends only (no holiday calendar), annual coupon on the maturity anniversary,
 * 30/360 or ACT/ACT as the instrument's day count says. Real systems carry coupon schedules and calendars.
 */
public final class Booking
{
    public static final String FIRM_BIC = "AFCAGB2L";
    public static final String FIRM_ACCOUNT = "EC-AF-0001";

    public record Booked(
        long tradeId,
        LocalDate tradeDate,
        LocalDate settlementDate,
        double accruedPer100,
        long accruedAmount,
        long principal,
        long consideration,
        String currency)
    {
    }

    /**
     * Securities movement versus payment, from the firm's point of view.
     */
    public record Instruction(
        String ref,
        String type,
        String isin,
        LocalDate settlementDate,
        long quantity,
        long cashAmount,
        String currency,
        String deliveringBic,
        String deliveringAccount,
        String receivingBic,
        String receivingAccount)
    {
        public String toJson()
        {
            return "{\"ref\":" + Json.quote(ref) + ",\"type\":" + Json.quote(type) + ",\"isin\":" + Json.quote(isin) +
                ",\"settle\":" + Json.quote(settlementDate.toString()) + ",\"qty\":" + quantity +
                ",\"cash\":" + cashAmount + ",\"ccy\":" + Json.quote(currency) +
                ",\"from\":" + Json.quote(deliveringBic + "/" + deliveringAccount) +
                ",\"to\":" + Json.quote(receivingBic + "/" + receivingAccount) + "}";
        }
    }

    private Booking()
    {
    }

    public static LocalDate tradeDate(final long timestampMs)
    {
        return Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    public static LocalDate addBusinessDays(final LocalDate from, final int days)
    {
        LocalDate d = from;
        int left = days;
        while (left > 0)
        {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
            {
                left--;
            }
        }
        return d;
    }

    /**
     * Accrued interest per 100 nominal from the last coupon date up to (excluding) settlement.
     */
    public static double accruedPer100(final Model.Instrument bond, final LocalDate settlement)
    {
        LocalDate last = bond.maturity().withYear(settlement.getYear());
        if (last.isAfter(settlement))
        {
            last = last.minusYears(1);
        }
        final LocalDate next = last.plusYears(1);
        final double fraction;
        if ("ACT/ACT".equals(bond.dayCount()))
        {
            fraction = (double)ChronoUnit.DAYS.between(last, settlement) / ChronoUnit.DAYS.between(last, next);
        }
        else
        {
            final int days = (settlement.getYear() - last.getYear()) * 360 +
                (settlement.getMonthValue() - last.getMonthValue()) * 30 +
                (Math.min(settlement.getDayOfMonth(), 30) - Math.min(last.getDayOfMonth(), 30));
            fraction = days / 360.0;
        }
        return bond.couponPct() * fraction;
    }

    public static Booked book(final TradeEvent t, final Model.Instrument bond)
    {
        final LocalDate tradeDate = tradeDate(t.timestampMs());
        final LocalDate settle = addBusinessDays(tradeDate, bond.settlementDays());
        final double accrued = accruedPer100(bond, settle);
        final long principal = Math.round(t.notional() * t.price() / 100.0);
        final long accruedAmount = Math.round(t.notional() * accrued / 100.0);
        return new Booked(t.tradeId(), tradeDate, settle, accrued, accruedAmount, principal,
            principal + accruedAmount, bond.currency());
    }

    /**
     * Missing standing settlement instructions block booking; they are repaired in reference data.
     */
    public static List<String> bookingIssues(final Model.Client client, final Model.Instrument bond)
    {
        final List<String> issues = new ArrayList<>();
        if (null == client)
        {
            issues.add("UNKNOWN_CLIENT");
        }
        else if (!client.hasSsi())
        {
            issues.add("MISSING_SSI: client " + client.clientId() + " " + client.name());
        }
        if (null == bond)
        {
            issues.add("UNKNOWN_INSTRUMENT");
        }
        return issues;
    }

    public static Instruction instruction(
        final TradeEvent t, final Booked b, final Model.Client client, final Model.Instrument bond)
    {
        // Client buys: we deliver securities against payment (DVP). Client sells: we receive against payment (RVP).
        final boolean deliver = t.clientBuys();
        return new Instruction("AFSI" + t.tradeId(), deliver ? "DVP" : "RVP", bond.isin(), b.settlementDate(),
            t.notional(), b.consideration(), b.currency(),
            deliver ? FIRM_BIC : client.ssiBic(), deliver ? FIRM_ACCOUNT : client.ssiAccount(),
            deliver ? client.ssiBic() : FIRM_BIC, deliver ? client.ssiAccount() : FIRM_ACCOUNT);
    }
}
