package io.aeronfire.control;

import io.aeron.cluster.ClusterTool;
import io.aeronfire.sbe.AdjustReason;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * JMX operations and attributes for the control plane. With the Jolokia agent attached they are reachable
 * as JSON, for example:
 * <pre>
 *   curl -u risk:risk localhost:8778/jolokia/exec/aeronfire:type=Credit/setCreditLine/risk/7/3/40
 * </pre>
 * Every operation takes the acting user first. The admin web page binds it to the authenticated login; over
 * Jolokia, pair basic or JAAS auth with a Jolokia access policy so callers can only act as themselves.
 * Amounts are in millions. Results read "APPLIED", "PENDING APPROVAL #n" or "REJECTED: reason".
 */
public final class Mbeans implements
    ControlMBeans.CreditMBean,
    ControlMBeans.InventoryMBean,
    ControlMBeans.AlgoControlsMBean,
    ControlMBeans.EntitlementsMBean,
    ControlMBeans.ApprovalsMBean,
    ControlMBeans.EligibilityMBean,
    ControlMBeans.ClusterMBean
{
    private final ControlPlane control;
    private final File[] clusterDirs;

    Mbeans(final ControlPlane control, final String clusterDirs)
    {
        this.control = control;
        this.clusterDirs = ControlPlane.dirs(clusterDirs);
    }

    // ------------------------------------------------------------------ credit

    @Override
    public String setCreditLine(final String user, final int clientId, final int tier, final double limitMm)
    {
        return control.submit(Commands.creditUpdate(Users.idOf(user), clientId, tier, ControlPlane.mmToUnits(limitMm),
            false), false);
    }

    @Override
    public String resetUtilisation(final String user, final int clientId)
    {
        return control.submit(Commands.creditReset(Users.idOf(user), clientId), false);
    }

    @Override
    public String[] getCreditLines()
    {
        return control.view().creditLines();
    }

    // ------------------------------------------------------------------ inventory

    @Override
    public String setLimits(final String user, final int instrumentId, final double maxLongMm, final double maxShortMm)
    {
        final ControlView.Inventory current = current(instrumentId);
        return control.submit(Commands.inventoryLimit(Users.idOf(user), instrumentId,
            ControlPlane.mmToUnits(maxLongMm), ControlPlane.mmToUnits(maxShortMm),
            null != current && current.halted()), false);
    }

    @Override
    public String halt(final String user, final int instrumentId)
    {
        return setHalt(user, instrumentId, true);
    }

    @Override
    public String resume(final String user, final int instrumentId)
    {
        return setHalt(user, instrumentId, false);
    }

    private String setHalt(final String user, final int instrumentId, final boolean halted)
    {
        final ControlView.Inventory current = current(instrumentId);
        if (null == current)
        {
            return "REJECTED: unknown instrument";
        }
        return control.submit(Commands.inventoryLimit(
            Users.idOf(user), instrumentId, current.maxLong(), current.maxShort(), halted), false);
    }

    @Override
    public String adjustPosition(final String user, final int instrumentId, final double deltaMm, final String reason)
    {
        final AdjustReason r = AdjustReason.valueOf(reason.trim().toUpperCase(Locale.ROOT));
        return control.submit(
            Commands.positionAdjust(Users.idOf(user), instrumentId, ControlPlane.mmToUnits(deltaMm), r), false);
    }

    @Override
    public String[] getInventory()
    {
        return control.view().inventoryLines();
    }

    private ControlView.Inventory current(final int instrumentId)
    {
        return control.view().inventory(instrumentId);
    }

    // ------------------------------------------------------------------ RTS 6

    @Override
    public String engageKillSwitch(final String user)
    {
        final ControlView v = control.view();
        return control.submit(Commands.algoControls(Users.idOf(user), true, v.priceCollarBps(), v.maxRfqsPerSecond()),
            false);
    }

    @Override
    public String releaseKillSwitch(final String user)
    {
        final ControlView v = control.view();
        return control.submit(Commands.algoControls(Users.idOf(user), false, v.priceCollarBps(), v.maxRfqsPerSecond()),
            false);
    }

    @Override
    public String setControls(final String user, final int priceCollarBps, final int maxRfqsPerSecond)
    {
        return control.submit(Commands.algoControls(
            Users.idOf(user), control.view().killSwitch(), priceCollarBps, maxRfqsPerSecond), false);
    }

    @Override
    public boolean isKillSwitchEngaged()
    {
        return control.view().killSwitch();
    }

    @Override
    public int getPriceCollarBps()
    {
        return control.view().priceCollarBps();
    }

    @Override
    public int getMaxRfqsPerSecond()
    {
        return control.view().maxRfqsPerSecond();
    }

    // ------------------------------------------------------------------ entitlements and approvals

    @Override
    public String grant(
        final String user,
        final String targetUser,
        final String permissions,
        final String clients,
        final String instruments,
        final double maxCreditMm,
        final double maxAdjustMm,
        final double maxInventoryMm)
    {
        final boolean allClients = clients.trim().equalsIgnoreCase("ALL");
        final int[] clientIds = allClients || clients.isBlank() ? new int[0] :
            Arrays.stream(clients.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        int instrumentScope = 0;
        if (instruments.trim().equalsIgnoreCase("ALL"))
        {
            instrumentScope = io.aeronfire.domain.Entitlements.ALL_INSTRUMENTS;
        }
        else if (!instruments.isBlank())
        {
            for (final String id : instruments.split(","))
            {
                instrumentScope |= 1 << Integer.parseInt(id.trim());
            }
        }
        return control.submit(Commands.setEntitlement(Users.idOf(user), Users.idOf(targetUser),
            Permissions.parse(permissions), allClients, clientIds, instrumentScope, ControlPlane.mmToUnits(maxCreditMm),
            ControlPlane.mmToUnits(maxAdjustMm), ControlPlane.mmToUnits(maxInventoryMm)), false);
    }

    @Override
    public String[] getUsers()
    {
        return control.view().userLines();
    }

    @Override
    public String approve(final String user, final long pendingId)
    {
        return control.submit(Commands.approval(Users.idOf(user), pendingId, true), true);
    }

    @Override
    public String reject(final String user, final long pendingId)
    {
        return control.submit(Commands.approval(Users.idOf(user), pendingId, false), true);
    }

    @Override
    public String[] getPending()
    {
        return control.view().pendingLines();
    }

    // ------------------------------------------------------------------ eligibility and cluster ops

    @Override
    public String setEligibility(final String user, final int clientId, final boolean kycValid, final boolean sanctioned)
    {
        return control.submit(Commands.eligibility(Users.idOf(user), clientId, kycValid, sanctioned), false);
    }

    @Override
    public int getLeaderMemberId()
    {
        return control.view().leader();
    }

    @Override
    public long getLeadershipTermId()
    {
        return control.view().term();
    }

    /**
     * Ask the cluster to take a snapshot (Aeron's ClusterTool). Needs local access to a member's cluster
     * directory, so it works when the control plane runs alongside a member ({@code CLUSTER_DIRS}).
     */
    @Override
    public String takeSnapshot()
    {
        for (final File dir : clusterDirs)
        {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8))
            {
                if (dir.isDirectory() && ClusterTool.snapshot(dir, ps))
                {
                    return "APPLIED: snapshot requested via " + dir;
                }
            }
        }
        return "REJECTED: no reachable cluster directory (set CLUSTER_DIRS on a host that runs a member)";
    }
}
