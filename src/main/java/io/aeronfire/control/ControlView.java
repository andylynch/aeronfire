package io.aeronfire.control;

import io.aeronfire.domain.Instruments;
import io.aeronfire.sbe.PendingState;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The control plane's read model of cluster control state, built from the state dump and drop copy.
 * Updated on the poll thread and read by JMX and HTTP threads, hence synchronized.
 */
public final class ControlView
{
    record Credit(int tier, long limit, long utilised)
    {
    }

    record Inventory(long maxLong, long maxShort, boolean halted, long net)
    {
    }

    record Entitlement(int permissions, boolean allClients, int instrumentScope, long maxCreditLimit,
        long maxAdjust, long maxInventoryLimit, int clientCount)
    {
    }

    record Pending(long pendingId, int requestedBy, int templateId)
    {
    }

    record Eligibility(boolean kycValid, boolean sanctioned)
    {
    }

    private final Map<Integer, Credit> credit = new TreeMap<>();
    private final Map<Integer, Inventory> inventory = new TreeMap<>();
    private final Map<Integer, Entitlement> entitlements = new TreeMap<>();
    private final Map<Long, Pending> pending = new TreeMap<>();
    private final Map<Integer, Eligibility> eligibility = new TreeMap<>();
    private boolean killSwitch;
    private int priceCollarBps;
    private int maxRfqsPerSecond;
    private int leader = -1;
    private long term = -1;

    synchronized void credit(final int clientId, final int tier, final long limit, final long utilised)
    {
        credit.put(clientId, new Credit(tier, limit, utilised));
    }

    synchronized void inventory(final int instrumentId, final long maxLong, final long maxShort, final boolean halted,
        final long net)
    {
        inventory.put(instrumentId, new Inventory(maxLong, maxShort, halted, net));
    }

    synchronized void algo(final boolean engaged, final int collarBps, final int rfqsPerSecond)
    {
        killSwitch = engaged;
        priceCollarBps = collarBps;
        maxRfqsPerSecond = rfqsPerSecond;
    }

    synchronized void entitlement(final int user, final Entitlement e)
    {
        entitlements.put(user, e);
    }

    synchronized void pending(final long pendingId, final int requestedBy, final int templateId, final PendingState state)
    {
        if (state == PendingState.PENDING)
        {
            pending.put(pendingId, new Pending(pendingId, requestedBy, templateId));
        }
        else
        {
            pending.remove(pendingId);
        }
    }

    synchronized void eligibility(final int clientId, final boolean kycValid, final boolean sanctioned)
    {
        eligibility.put(clientId, new Eligibility(kycValid, sanctioned));
    }

    synchronized void leader(final int leaderMemberId, final long leadershipTermId)
    {
        leader = leaderMemberId;
        term = leadershipTermId;
    }

    synchronized boolean killSwitch()
    {
        return killSwitch;
    }

    synchronized int priceCollarBps()
    {
        return priceCollarBps;
    }

    synchronized int maxRfqsPerSecond()
    {
        return maxRfqsPerSecond;
    }

    synchronized int leader()
    {
        return leader;
    }

    synchronized long term()
    {
        return term;
    }

    synchronized Inventory inventory(final int instrumentId)
    {
        return inventory.get(instrumentId);
    }

    synchronized Credit credit(final int clientId)
    {
        return credit.get(clientId);
    }

    synchronized String[] creditLines()
    {
        return credit.entrySet().stream().map((e) -> String.format(Locale.ROOT,
            "client %d tier %d limit %s used %s%s", e.getKey(), e.getValue().tier(), mm(e.getValue().limit()),
            mm(e.getValue().utilised()), flags(e.getKey()))).toArray(String[]::new);
    }

    synchronized String[] inventoryLines()
    {
        return inventory.entrySet().stream().map((e) -> String.format(Locale.ROOT,
            "%d %s net %s maxLong %s maxShort %s%s", e.getKey(), Instruments.name(e.getKey()), mm(e.getValue().net()),
            mm(e.getValue().maxLong()), mm(e.getValue().maxShort()), e.getValue().halted() ? " HALTED" : ""))
            .toArray(String[]::new);
    }

    synchronized String[] userLines()
    {
        return entitlements.entrySet().stream().map((e) -> String.format(Locale.ROOT,
            "%s perms [%s] clients %s instruments %s maxCredit %s maxAdjust %s maxInventory %s",
            Users.nameOf(e.getKey()), Permissions.describe(e.getValue().permissions()),
            e.getValue().allClients() ? "ALL" : e.getValue().clientCount() + " scoped",
            instruments(e.getValue().instrumentScope()), mm(e.getValue().maxCreditLimit()),
            mm(e.getValue().maxAdjust()), mm(e.getValue().maxInventoryLimit()))).toArray(String[]::new);
    }

    synchronized String[] pendingLines()
    {
        return pending.values().stream().map((p) -> String.format(Locale.ROOT,
            "#%d %s requested by %s", p.pendingId(), Commands.describeTemplate(p.templateId()),
            Users.nameOf(p.requestedBy()))).toArray(String[]::new);
    }

    synchronized String toJson()
    {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"leader\":").append(leader).append(",\"term\":").append(term)
            .append(",\"killSwitch\":").append(killSwitch)
            .append(",\"priceCollarBps\":").append(priceCollarBps)
            .append(",\"maxRfqsPerSecond\":").append(maxRfqsPerSecond);
        sb.append(",\"credit\":[");
        boolean first = true;
        for (final Map.Entry<Integer, Credit> e : credit.entrySet())
        {
            final Eligibility el = eligibility.get(e.getKey());
            sb.append(first ? "" : ",").append("{\"client\":").append(e.getKey())
                .append(",\"tier\":").append(e.getValue().tier())
                .append(",\"limit\":").append(e.getValue().limit())
                .append(",\"utilised\":").append(e.getValue().utilised())
                .append(",\"kycValid\":").append(null == el || el.kycValid())
                .append(",\"sanctioned\":").append(null != el && el.sanctioned()).append('}');
            first = false;
        }
        sb.append("],\"inventory\":[");
        first = true;
        for (final Map.Entry<Integer, Inventory> e : inventory.entrySet())
        {
            final Inventory i = e.getValue();
            sb.append(first ? "" : ",").append("{\"id\":").append(e.getKey())
                .append(",\"name\":\"").append(Instruments.name(e.getKey())).append('"')
                .append(",\"net\":").append(i.net()).append(",\"maxLong\":").append(i.maxLong())
                .append(",\"maxShort\":").append(i.maxShort()).append(",\"halted\":").append(i.halted()).append('}');
            first = false;
        }
        sb.append("],\"pending\":[");
        first = true;
        for (final Pending p : pending.values())
        {
            sb.append(first ? "" : ",").append("{\"id\":").append(p.pendingId())
                .append(",\"what\":\"").append(Commands.describeTemplate(p.templateId())).append('"')
                .append(",\"by\":\"").append(Users.nameOf(p.requestedBy())).append("\"}");
            first = false;
        }
        sb.append("],\"users\":[");
        first = true;
        for (final Map.Entry<Integer, Entitlement> e : entitlements.entrySet())
        {
            final Entitlement en = e.getValue();
            sb.append(first ? "" : ",").append("{\"user\":\"").append(Users.nameOf(e.getKey())).append('"')
                .append(",\"perms\":\"").append(Permissions.describe(en.permissions())).append('"')
                .append(",\"clients\":\"").append(en.allClients() ? "ALL" : en.clientCount() + " scoped").append('"')
                .append(",\"instruments\":\"").append(instruments(en.instrumentScope())).append('"')
                .append(",\"maxCredit\":").append(en.maxCreditLimit())
                .append(",\"maxAdjust\":").append(en.maxAdjust())
                .append(",\"maxInventory\":").append(en.maxInventoryLimit()).append('}');
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String flags(final int clientId)
    {
        final Eligibility e = eligibility.get(clientId);
        if (null == e)
        {
            return "";
        }
        return (e.kycValid() ? "" : " KYC-BLOCKED") + (e.sanctioned() ? " SANCTIONED" : "");
    }

    private static String instruments(final int scope)
    {
        if (scope == (1 << Instruments.COUNT) - 1)
        {
            return "ALL";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Instruments.COUNT; i++)
        {
            if ((scope & (1 << i)) != 0)
            {
                sb.append(sb.isEmpty() ? "" : ",").append(i);
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    static String mm(final long notional)
    {
        if (notional == Long.MAX_VALUE)
        {
            return "unlimited";
        }
        return String.format(Locale.ROOT, "%.1fmm", notional / 1_000_000.0);
    }
}
