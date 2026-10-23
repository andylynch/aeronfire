package io.aeronfire.posttrade;

import io.aeronfire.refdata.Model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-house stand-ins for the external parties post-trade talks to: the clearer/CSD, the ARM that receives
 * transaction reports, and our APA that publishes bond trades. They apply the rules that matter for the demo.
 */
public final class Simulators
{
    private Simulators()
    {
    }

    public record Reply(boolean accepted, String status, List<String> detail)
    {
    }

    /**
     * Clearer / settlement agent: matches an instruction if both parties' BICs are well-formed.
     * Idempotent on the instruction reference.
     */
    public static final class Clearer
    {
        private final Map<String, Booking.Instruction> received = new LinkedHashMap<>();

        public synchronized Reply submit(final Booking.Instruction i)
        {
            final boolean duplicate = received.containsKey(i.ref());
            final List<String> problems = new ArrayList<>();
            for (final String bic : List.of(i.deliveringBic(), i.receivingBic()))
            {
                if (null == bic || !bic.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?"))
                {
                    problems.add("INVALID_BIC " + bic);
                }
            }
            if (!problems.isEmpty())
            {
                return new Reply(false, "NACK", problems);
            }
            received.put(i.ref(), i);
            return new Reply(true, duplicate ? "ALREADY_MATCHED" : "MATCHED", List.of());
        }

        public synchronized int count()
        {
            return received.size();
        }
    }

    /**
     * Approved Reporting Mechanism: validates, then accepts, treats an identical resend as a duplicate,
     * and treats a changed resend of the same reference as an amendment.
     */
    public static final class Arm
    {
        private final Map<String, String> accepted = new LinkedHashMap<>();

        public synchronized Reply receive(final Map<String, String> report, final RefLookup ref, final Model.Instrument bond)
        {
            final List<Reporting.Issue> issues = Reporting.armCheck(report, ref, bond);
            if (Reporting.hasErrors(issues))
            {
                return new Reply(false, "NACK", issues.stream().map(Reporting.Issue::toString).toList());
            }
            final String json = Reporting.json(report);
            final String previous = accepted.put(report.get("transactionRef"), json);
            final String status = null == previous ? "ACCEPTED" : previous.equals(json) ? "DUPLICATE_IGNORED" : "AMENDED";
            return new Reply(true, status, issues.stream().map(Reporting.Issue::toString).toList());
        }

        public synchronized int count()
        {
            return accepted.size();
        }
    }

    /**
     * Approved Publication Arrangement for off-venue bond trades.
     */
    public static final class Apa
    {
        private final Map<Long, Reporting.Publication> published = new LinkedHashMap<>();

        public synchronized Reporting.Publication publish(final long tradeId, final Reporting.Publication p)
        {
            published.putIfAbsent(tradeId, p);
            return published.get(tradeId);
        }

        public synchronized int count()
        {
            return published.size();
        }
    }
}
