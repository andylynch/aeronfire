package io.aeronfire.monitor;

import io.aeronfire.domain.CreditBook;
import io.aeronfire.domain.Instruments;
import io.aeronfire.domain.Pricer;
import io.aeronfire.domain.Tier;
import io.aeronfire.sbe.RejectReason;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Everything the blotter shows, rebuilt from the cluster's drop copy and state dump.
 * <p>
 * Updated only by the monitor's poll thread. Views (web and terminal) read immutable renderings of it.
 */
public final class MonitorModel
{
    private final boolean[] clusterRisk = new boolean[Instruments.COUNT];
    private final double[] clusterMark = new double[Instruments.COUNT];
    private final double[] clusterCs01 = new double[Instruments.COUNT];
    private final double[] clusterDv01 = new double[Instruments.COUNT];
    static final int RECENT_EVENTS = 30;
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /**
     * One line in the blotter.
     */
    record Event(long timeMs, String type, int clientId, int instrumentId, boolean clientBuys,
        long notional, long priceE6, int spreadBps, String detail)
    {
    }

    private final Pricer pricer = Pricer.defaults();

    // cluster health
    int leaderMemberId = -1;
    long leadershipTermId = -1;
    int leaderChanges;
    boolean stateDumpComplete;
    long lastEventMs;

    // flow
    long quotes;
    long trades;
    long tradedNotional;
    final long[] rejectsByReason = new long[RejectReason.values().length];
    private final RateMeter quoteRate = new RateMeter();
    private final RateMeter tradeRate = new RateMeter();

    // controls
    boolean killSwitch;
    int pendingApprovals;
    final java.util.Set<Long> pendingIds = new java.util.HashSet<>();
    final boolean[] halted = new boolean[Instruments.COUNT];
    final boolean[] kycBlocked = new boolean[CreditBook.CAPACITY];
    final boolean[] sanctioned = new boolean[CreditBook.CAPACITY];

    // book
    final long[] positions = new long[Instruments.COUNT];
    final boolean[] knownClient = new boolean[CreditBook.CAPACITY];
    final Tier[] tier = new Tier[CreditBook.CAPACITY];
    final long[] limit = new long[CreditBook.CAPACITY];
    final long[] utilised = new long[CreditBook.CAPACITY];
    final long[] clientTrades = new long[CreditBook.CAPACITY];

    final Deque<Event> recent = new ArrayDeque<>(RECENT_EVENTS);

    // ------------------------------------------------------------------ updates from the drop copy

    void onQuote(final long nowMs, final int clientId, final int instrumentId, final boolean clientBuys,
        final long notional, final long priceE6, final int spreadBps)
    {
        quotes++;
        quoteRate.mark(nowMs);
        add(new Event(nowMs, "QUOTE", clientId, instrumentId, clientBuys, notional, priceE6, spreadBps, ""));
    }

    void onTrade(final long nowMs, final int clientId, final int instrumentId, final boolean clientBuys,
        final long notional, final long priceE6, final long clientUtilised)
    {
        trades++;
        tradedNotional += notional;
        tradeRate.mark(nowMs);
        positions[instrumentId] += clientBuys ? -notional : notional;
        if (CreditBook.isValidId(clientId))
        {
            utilised[clientId] = clientUtilised;
            clientTrades[clientId]++;
        }
        add(new Event(nowMs, "TRADE", clientId, instrumentId, clientBuys, notional, priceE6, 0, ""));
    }

    void onReject(final long nowMs, final RejectReason reason, final boolean onRfq)
    {
        if (reason != RejectReason.NULL_VAL)
        {
            rejectsByReason[reason.value()]++;
        }
        add(new Event(nowMs, "REJECT", -1, -1, false, 0, 0, 0, (onRfq ? "rfq " : "accept ") + reason));
    }

    void onCredit(final int clientId, final Tier clientTier, final long clientLimit, final long clientUtilised)
    {
        if (CreditBook.isValidId(clientId) && null != clientTier)
        {
            knownClient[clientId] = true;
            tier[clientId] = clientTier;
            limit[clientId] = clientLimit;
            utilised[clientId] = clientUtilised;
        }
    }

    /**
     * Risk as computed by the cluster after each trade; preferred over the local estimate below.
     */
    void onRisk(final int instrumentId, final long net, final double mark, final double cs01, final double dv01)
    {
        if (Instruments.isValid(instrumentId))
        {
            positions[instrumentId] = net;
            clusterRisk[instrumentId] = true;
            clusterMark[instrumentId] = mark;
            clusterCs01[instrumentId] = cs01;
            clusterDv01[instrumentId] = dv01;
        }
    }

    void onPosition(final int instrumentId, final long net)
    {
        if (Instruments.isValid(instrumentId))
        {
            positions[instrumentId] = net;
        }
    }

    void onInventory(final int instrumentId, final long net, final boolean isHalted)
    {
        if (Instruments.isValid(instrumentId))
        {
            positions[instrumentId] = net;
            halted[instrumentId] = isHalted;
        }
    }

    void onEligibility(final int clientId, final boolean kycValid, final boolean isSanctioned)
    {
        if (CreditBook.isValidId(clientId))
        {
            kycBlocked[clientId] = !kycValid;
            sanctioned[clientId] = isSanctioned;
        }
    }

    void onPending(final long pendingId, final boolean open)
    {
        if (open)
        {
            pendingIds.add(pendingId);
        }
        else
        {
            pendingIds.remove(pendingId);
        }
        pendingApprovals = pendingIds.size();
    }

    void onCreditReset()
    {
        for (int i = 0; i < CreditBook.CAPACITY; i++)
        {
            utilised[i] = 0;
        }
    }

    private void add(final Event event)
    {
        lastEventMs = event.timeMs();
        if (recent.size() == RECENT_EVENTS)
        {
            recent.removeLast();
        }
        recent.addFirst(event);
    }

    // ------------------------------------------------------------------ derived

    double cs01(final int instrumentId)
    {
        if (clusterRisk[instrumentId])
        {
            return clusterCs01[instrumentId];
        }
        return pricer.cs01(instrumentId, Instruments.baseSpreadBps(instrumentId), positions[instrumentId]);
    }

    double dv01(final int instrumentId)
    {
        if (clusterRisk[instrumentId])
        {
            return clusterDv01[instrumentId];
        }
        return pricer.dv01(instrumentId, Instruments.baseSpreadBps(instrumentId), positions[instrumentId]);
    }

    double markValue(final int instrumentId)
    {
        if (clusterRisk[instrumentId])
        {
            return clusterMark[instrumentId];
        }
        return pricer.price(instrumentId, Instruments.baseSpreadBps(instrumentId)) / 100.0 * positions[instrumentId];
    }

    long rejects()
    {
        long total = 0;
        for (final long r : rejectsByReason)
        {
            total += r;
        }
        return total;
    }

    double hitRatio()
    {
        return quotes == 0 ? 0.0 : (double)trades / quotes;
    }

    double quotesPerSecond(final long nowMs)
    {
        return quoteRate.perSecond(nowMs);
    }

    double tradesPerSecond(final long nowMs)
    {
        return tradeRate.perSecond(nowMs);
    }

    static String time(final long epochMs)
    {
        return TIME.format(Instant.ofEpochMilli(epochMs));
    }

    // ------------------------------------------------------------------ JSON for the web view

    String toJson(final long nowMs)
    {
        final StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append('{');
        sb.append("\"cluster\":{\"leader\":").append(leaderMemberId)
            .append(",\"term\":").append(leadershipTermId)
            .append(",\"leaderChanges\":").append(leaderChanges)
            .append(",\"synced\":").append(stateDumpComplete)
            .append(",\"lastEventAgoMs\":").append(lastEventMs == 0 ? -1 : nowMs - lastEventMs)
            .append(",\"killSwitch\":").append(killSwitch)
            .append(",\"pendingApprovals\":").append(pendingApprovals)
            .append("},");

        sb.append("\"flow\":{\"quotes\":").append(quotes)
            .append(",\"trades\":").append(trades)
            .append(",\"rejects\":").append(rejects())
            .append(",\"tradedNotional\":").append(tradedNotional)
            .append(",\"hitRatio\":").append(round(hitRatio(), 4))
            .append(",\"quotesPerSec\":").append(round(quotesPerSecond(nowMs), 1))
            .append(",\"tradesPerSec\":").append(round(tradesPerSecond(nowMs), 1))
            .append(",\"rejectsByReason\":{");
        boolean first = true;
        for (final RejectReason reason : RejectReason.values())
        {
            if (reason != RejectReason.NULL_VAL && rejectsByReason[reason.value()] > 0)
            {
                sb.append(first ? "" : ",").append('"').append(reason).append("\":").append(rejectsByReason[reason.value()]);
                first = false;
            }
        }
        sb.append("}},");

        sb.append("\"positions\":[");
        double totalCs01 = 0;
        double totalDv01 = 0;
        for (int i = 0; i < Instruments.COUNT; i++)
        {
            final double cs01 = cs01(i);
            totalCs01 += cs01;
            totalDv01 += dv01(i);
            sb.append(i == 0 ? "" : ",")
                .append("{\"id\":").append(i)
                .append(",\"name\":\"").append(Instruments.name(i)).append('"')
                .append(",\"net\":").append(positions[i])
                .append(",\"halted\":").append(halted[i])
                .append(",\"mark\":").append(round(markValue(i), 0))
                .append(",\"cs01\":").append(round(cs01, 0))
                .append(",\"dv01\":").append(round(dv01(i), 0))
                .append('}');
        }
        sb.append("],\"totalCs01\":").append(round(totalCs01, 0))
            .append(",\"totalDv01\":").append(round(totalDv01, 0)).append(',');

        sb.append("\"credit\":[");
        first = true;
        for (int id = 0; id < CreditBook.CAPACITY; id++)
        {
            if (knownClient[id])
            {
                sb.append(first ? "" : ",")
                    .append("{\"client\":").append(id)
                    .append(",\"tier\":").append(tier[id].ordinal() + 1)
                    .append(",\"limit\":").append(limit[id])
                    .append(",\"utilised\":").append(utilised[id])
                    .append(",\"trades\":").append(clientTrades[id])
                    .append(",\"kycBlocked\":").append(kycBlocked[id])
                    .append(",\"sanctioned\":").append(sanctioned[id])
                    .append('}');
                first = false;
            }
        }
        sb.append("],");

        sb.append("\"recent\":[");
        first = true;
        for (final Event e : recent)
        {
            sb.append(first ? "" : ",")
                .append("{\"time\":\"").append(time(e.timeMs())).append('"')
                .append(",\"type\":\"").append(e.type()).append('"')
                .append(",\"client\":").append(e.clientId())
                .append(",\"instrument\":\"").append(e.instrumentId() >= 0 ? Instruments.name(e.instrumentId()) : "").append('"')
                .append(",\"side\":\"").append(e.instrumentId() < 0 ? "" : e.clientBuys() ? "BUY" : "SELL").append('"')
                .append(",\"notional\":").append(e.notional())
                .append(",\"price\":").append(round(e.priceE6() / (double)Pricer.PRICE_SCALE, 4))
                .append(",\"spreadBps\":").append(e.spreadBps())
                .append(",\"detail\":\"").append(e.detail()).append('"')
                .append('}');
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String round(final double value, final int places)
    {
        if (!Double.isFinite(value))
        {
            return "0";
        }
        return places == 0 ? Long.toString(Math.round(value)) : String.format(java.util.Locale.ROOT, "%." + places + "f", value);
    }

    /**
     * Events per second over a sliding window of one-second buckets.
     */
    static final class RateMeter
    {
        private static final int WINDOW_SECONDS = 5;
        private final long[] counts = new long[WINDOW_SECONDS];
        private final long[] seconds = new long[WINDOW_SECONDS];

        void mark(final long nowMs)
        {
            final long second = nowMs / 1000;
            final int slot = (int)(second % WINDOW_SECONDS);
            if (seconds[slot] != second)
            {
                seconds[slot] = second;
                counts[slot] = 0;
            }
            counts[slot]++;
        }

        double perSecond(final long nowMs)
        {
            final long current = nowMs / 1000;
            long total = 0;
            for (int i = 0; i < WINDOW_SECONDS; i++)
            {
                // Count only complete seconds inside the window.
                if (seconds[i] < current && seconds[i] >= current - WINDOW_SECONDS)
                {
                    total += counts[i];
                }
            }
            return total / (double)(WINDOW_SECONDS - 1);
        }
    }
}
