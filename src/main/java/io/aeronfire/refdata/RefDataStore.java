package io.aeronfire.refdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Golden copy: merges the sources, keeps provenance, and records every manual override or screening
 * disposition in an append-only audit trail (who, what, before, after, why).
 * Thread-safe via coarse synchronisation; this is a low-rate service.
 */
public final class RefDataStore
{
    public record Audit(Instant at, String user, String action, String subject, String before, String after, String reason)
    {
    }

    /**
     * Eligibility verdict for a client with the reasons behind it.
     */
    public record Verdict(int clientId, boolean kycValid, boolean sanctioned, List<String> reasons, List<String> warnings)
    {
    }

    private final Map<Integer, Model.Client> clients = new TreeMap<>();
    private final Map<String, Model.LeiRecord> leis = new HashMap<>();
    private final List<Model.Instrument> instruments = new ArrayList<>();
    private final List<Sources.TimedDesignation> designations = new ArrayList<>();
    private final Map<String, String> discounted = new HashMap<>(); // "clientId|designationId" -> user
    private final List<Audit> audit = new ArrayList<>();

    public synchronized void load(
        final Collection<Model.Client> c, final Collection<Model.LeiRecord> l, final Collection<Model.Instrument> i,
        final Collection<Sources.TimedDesignation> d)
    {
        c.forEach((x) -> clients.put(x.clientId(), x));
        l.forEach((x) -> leis.put(x.lei(), x));
        instruments.clear();
        instruments.addAll(i);
        designations.clear();
        designations.addAll(d);
    }

    /**
     * Replace LEI records with fresher ones (live GLEIF).
     */
    public synchronized void updateLeis(final Collection<Model.LeiRecord> fresh)
    {
        fresh.forEach((x) -> leis.put(x.lei(), x));
    }

    public synchronized List<Model.Client> clients()
    {
        return new ArrayList<>(clients.values());
    }

    public synchronized Model.Client client(final int id)
    {
        return clients.get(id);
    }

    public synchronized Model.LeiRecord lei(final String lei)
    {
        return leis.get(lei);
    }

    public synchronized List<Model.Instrument> instruments()
    {
        return new ArrayList<>(instruments);
    }

    public synchronized Model.Instrument instrumentById(final int id)
    {
        return id >= 0 && id < instruments.size() ? instruments.get(id) : null;
    }

    public synchronized List<Audit> audit()
    {
        return new ArrayList<>(audit);
    }

    /**
     * Designations in force after {@code elapsedSeconds} of the session.
     */
    public synchronized List<Model.Designation> activeDesignations(final long elapsedSeconds)
    {
        final List<Model.Designation> active = new ArrayList<>();
        for (final Sources.TimedDesignation t : designations)
        {
            if (t.effectiveAfterSeconds() <= elapsedSeconds)
            {
                active.add(t.designation());
            }
        }
        return active;
    }

    public synchronized List<Model.ScreeningHit> hits(final long elapsedSeconds)
    {
        final List<Model.Designation> active = activeDesignations(elapsedSeconds);
        final List<Model.ScreeningHit> hits = new ArrayList<>();
        for (final Model.Client c : clients.values())
        {
            final Set<String> names = new HashSet<>();
            names.add(c.name());
            final Model.LeiRecord rec = null == c.lei() ? null : leis.get(c.lei());
            if (null != rec)
            {
                names.add(rec.legalName());
            }
            if (null != c.parentName())
            {
                names.add(c.parentName());
            }
            for (final String n : names)
            {
                for (final Model.ScreeningHit h : Screening.screen(c.clientId(), n, active))
                {
                    final String dispo = discounted.get(c.clientId() + "|" + h.designationId());
                    final Model.ScreeningHit shown = null == dispo ? h :
                        new Model.ScreeningHit(h.clientId(), h.screenedName(), h.designationId(), h.designatedName(),
                            h.score(), "DISCOUNTED by " + dispo);
                    if (hits.stream().noneMatch((x) -> x.clientId() == shown.clientId() &&
                        x.designationId().equals(shown.designationId())))
                    {
                        hits.add(shown);
                    }
                }
            }
        }
        return hits;
    }

    public synchronized List<Verdict> verdicts(final LocalDate today, final long elapsedSeconds)
    {
        final List<Model.ScreeningHit> hits = hits(elapsedSeconds);
        final List<Verdict> out = new ArrayList<>();
        for (final Model.Client c : clients.values())
        {
            final List<String> reasons = new ArrayList<>();
            final List<String> warnings = new ArrayList<>();
            final boolean kyc = c.kycValid(today);
            if (!kyc)
            {
                reasons.add("KYC " + c.kycStatus() + ", review due " + c.kycReviewDue());
            }
            boolean sanctioned = false;
            for (final Model.ScreeningHit h : hits)
            {
                if (h.clientId() == c.clientId() && "POTENTIAL_MATCH".equals(h.status()))
                {
                    sanctioned = true;
                    reasons.add("sanctions potential match " + h.designationId() + " " + h.designatedName() +
                        " (" + Math.round(h.score() * 100) + "%)");
                }
            }
            if (null == c.lei())
            {
                warnings.add("no LEI: transaction reports will be rejected");
            }
            else
            {
                final Model.LeiRecord rec = leis.get(c.lei());
                if (!Identifiers.isValidLei(c.lei()))
                {
                    warnings.add("LEI fails checksum");
                }
                else if (null == rec)
                {
                    warnings.add("LEI not found in GLEIF");
                }
                else if (!"ISSUED".equals(rec.registrationStatus()))
                {
                    warnings.add("LEI registration " + rec.registrationStatus());
                }
            }
            if (!c.hasSsi())
            {
                warnings.add("no SSI: settlement will go to the hospital");
            }
            out.add(new Verdict(c.clientId(), kyc, sanctioned, reasons, warnings));
        }
        return out;
    }

    /**
     * Manual repair of a client field, audited. Caller has already checked the user's entitlement.
     */
    public synchronized String override(
        final String user, final int clientId, final String field, final String value, final String reason)
    {
        final Model.Client before = clients.get(clientId);
        if (null == before)
        {
            return "REJECTED: unknown client " + clientId;
        }
        if (null == reason || reason.isBlank())
        {
            return "REJECTED: a reason is required";
        }
        if ("lei".equals(field) && !Identifiers.isValidLei(value))
        {
            return "REJECTED: LEI fails ISO 17442 checksum";
        }
        final String old = switch (field)
        {
            case "lei" -> before.lei();
            case "kycStatus" -> before.kycStatus();
            case "kycReviewDue" -> String.valueOf(before.kycReviewDue());
            case "ssiBic" -> before.ssiBic();
            case "ssiAccount" -> before.ssiAccount();
            default -> null;
        };
        final Model.Client after;
        try
        {
            after = before.with(field, value, new Model.Provenance("manual override by " + user, "", Instant.now()));
        }
        catch (final RuntimeException ex)
        {
            return "REJECTED: " + ex.getMessage();
        }
        clients.put(clientId, after);
        audit.add(new Audit(Instant.now(), user, "OVERRIDE", "client " + clientId + "." + field, old, value, reason));
        return "APPLIED: client " + clientId + " " + field + " " + old + " -> " + value;
    }

    /**
     * Compliance decides a potential match is a false positive.
     */
    public synchronized String discount(final String user, final int clientId, final String designationId, final String reason)
    {
        if (null == reason || reason.isBlank())
        {
            return "REJECTED: a reason is required";
        }
        discounted.put(clientId + "|" + designationId, user);
        audit.add(new Audit(Instant.now(), user, "DISCOUNT_MATCH", "client " + clientId + " vs " + designationId,
            "POTENTIAL_MATCH", "DISCOUNTED", reason));
        return "APPLIED: match discounted";
    }
}
