package io.aeronfire.refdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Golden-copy reference data records. Every record carries its provenance: which source, as of when, and
 * when we retrieved it. Record keeping needs to show what we knew when we acted on it.
 */
public final class Model
{
    private Model()
    {
    }

    public record Provenance(String source, String asOf, Instant retrievedAt)
    {
        public static Provenance snapshot(final String source)
        {
            return new Provenance(source + " (snapshot)", "checked-in extract", Instant.now());
        }
    }

    /**
     * GLEIF Level 1 data, plus the direct parent for screening.
     */
    public record LeiRecord(
        String lei,
        String legalName,
        String entityStatus,
        String registrationStatus,
        String country,
        String nextRenewalDate,
        String parentLei,
        Provenance provenance)
    {
    }

    /**
     * Security master record: FIRDS fields plus static we need for settlement and transparency.
     */
    public record Instrument(
        int instrumentId,
        String isin,
        String fullName,
        String cfi,
        String currency,
        String issuerLei,
        double couponPct,
        LocalDate maturity,
        String dayCount,
        int settlementDays,
        int transparencyCategory,
        List<String> venues,
        Provenance provenance)
    {
    }

    /**
     * In-house client master and KYC (simulated).
     */
    public record Client(
        int clientId,
        String name,
        String lei,
        String country,
        String mifidClass,
        String kycStatus,
        LocalDate kycReviewDue,
        String riskRating,
        String ssiBic,
        String ssiAccount,
        String parentName,
        Provenance provenance)
    {
        public boolean kycValid(final LocalDate today)
        {
            return "APPROVED".equals(kycStatus) && (null == kycReviewDue || !kycReviewDue.isBefore(today));
        }

        public boolean hasSsi()
        {
            return null != ssiBic && !ssiBic.isBlank() && null != ssiAccount && !ssiAccount.isBlank();
        }

        public Client with(final String field, final String value, final Provenance source)
        {
            return switch (field)
            {
                case "lei" -> new Client(clientId, name, value, country, mifidClass, kycStatus, kycReviewDue, riskRating,
                    ssiBic, ssiAccount, parentName, source);
                case "kycStatus" -> new Client(clientId, name, lei, country, mifidClass, value, kycReviewDue, riskRating,
                    ssiBic, ssiAccount, parentName, source);
                case "kycReviewDue" -> new Client(clientId, name, lei, country, mifidClass, kycStatus,
                    LocalDate.parse(value), riskRating, ssiBic, ssiAccount, parentName, source);
                case "ssiBic" -> new Client(clientId, name, lei, country, mifidClass, kycStatus, kycReviewDue, riskRating,
                    value, ssiAccount, parentName, source);
                case "ssiAccount" -> new Client(clientId, name, lei, country, mifidClass, kycStatus, kycReviewDue,
                    riskRating, ssiBic, value, parentName, source);
                default -> throw new IllegalArgumentException("not an editable client field: " + field);
            };
        }
    }

    /**
     * One sanctions designation (UK Sanctions List).
     */
    public record Designation(
        String uniqueId,
        String name,
        String type,
        String regime,
        String dateDesignated,
        Provenance provenance)
    {
    }

    /**
     * Screening outcome for a client. Hits block trading until compliance confirms or discounts them.
     */
    public record ScreeningHit(
        int clientId,
        String screenedName,
        String designationId,
        String designatedName,
        double score,
        String status)
    {
    }
}
