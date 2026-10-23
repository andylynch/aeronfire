package io.aeronfire.control;

/**
 * JMX management interfaces for the control plane, registered under {@code aeronfire:type=*}.
 */
public final class ControlMBeans
{
    private ControlMBeans()
    {
    }

    public interface CreditMBean
    {
        String setCreditLine(String user, int clientId, int tier, double limitMm);

        String resetUtilisation(String user, int clientId);

        String[] getCreditLines();
    }

    public interface InventoryMBean
    {
        String setLimits(String user, int instrumentId, double maxLongMm, double maxShortMm);

        String halt(String user, int instrumentId);

        String resume(String user, int instrumentId);

        String adjustPosition(String user, int instrumentId, double deltaMm, String reason);

        String[] getInventory();
    }

    public interface AlgoControlsMBean
    {
        String engageKillSwitch(String user);

        String releaseKillSwitch(String user);

        String setControls(String user, int priceCollarBps, int maxRfqsPerSecond);

        boolean isKillSwitchEngaged();

        int getPriceCollarBps();

        int getMaxRfqsPerSecond();
    }

    public interface EntitlementsMBean
    {
        String grant(String user, String targetUser, String permissions, String clients, String instruments,
            double maxCreditMm, double maxAdjustMm, double maxInventoryMm);

        String[] getUsers();
    }

    public interface ApprovalsMBean
    {
        String approve(String user, long pendingId);

        String reject(String user, long pendingId);

        String[] getPending();
    }

    public interface EligibilityMBean
    {
        String setEligibility(String user, int clientId, boolean kycValid, boolean sanctioned);
    }

    public interface ClusterMBean
    {
        int getLeaderMemberId();

        long getLeadershipTermId();

        String takeSnapshot();
    }
}
