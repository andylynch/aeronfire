package io.aeronfire.domain;

/**
 * Per-client trading eligibility from reference data: KYC in date, and not on a sanctions list.
 * Pushed into the cluster by the reference data service and checked pre-trade on RFQ and accept.
 * Clients are eligible until reference data says otherwise.
 */
public final class ClientEligibility
{
    private final boolean[] kycValid = new boolean[CreditBook.CAPACITY];
    private final boolean[] sanctioned = new boolean[CreditBook.CAPACITY];

    public ClientEligibility()
    {
        java.util.Arrays.fill(kycValid, true);
    }

    public void set(final int clientId, final boolean isKycValid, final boolean isSanctioned)
    {
        kycValid[clientId] = isKycValid;
        sanctioned[clientId] = isSanctioned;
    }

    public boolean kycValid(final int clientId)
    {
        return !CreditBook.isValidId(clientId) || kycValid[clientId];
    }

    public boolean sanctioned(final int clientId)
    {
        return CreditBook.isValidId(clientId) && sanctioned[clientId];
    }

    /**
     * True if any client differs from the default (eligible), so snapshots only carry what matters.
     */
    public boolean isDefault(final int clientId)
    {
        return kycValid[clientId] && !sanctioned[clientId];
    }
}
