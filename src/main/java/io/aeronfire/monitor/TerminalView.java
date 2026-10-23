package io.aeronfire.monitor;

import io.aeronfire.domain.CreditBook;
import io.aeronfire.domain.Instruments;
import io.aeronfire.domain.Pricer;
import io.aeronfire.sbe.RejectReason;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Terminal blotter: redraws the whole screen with ANSI escapes. No dependencies, works over ssh.
 */
final class TerminalView
{
    private static final String CLEAR = "\033[H\033[2J";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";
    private static final int BAR_WIDTH = 20;

    private final PrintStream out;

    TerminalView(final PrintStream out)
    {
        this.out = out;
    }

    void render(final MonitorModel m, final long nowMs)
    {
        final StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append(CLEAR);
        sb.append(BOLD).append("aeronfire").append(RESET).append(DIM).append("  credit RFQ engine on Aeron Cluster")
            .append(RESET).append('\n');

        final String health = m.leaderMemberId < 0 ? RED + "no leader" + RESET :
            GREEN + "leader " + m.leaderMemberId + RESET + DIM + " term " + m.leadershipTermId +
            "  leader changes " + m.leaderChanges + (m.stateDumpComplete ? "  synced" : "  syncing...") + RESET;
        sb.append("cluster  ").append(health).append("\n\n");

        sb.append(BOLD).append("FLOW").append(RESET).append('\n');
        sb.append(String.format(Locale.ROOT,
            "  quotes %,d (%.1f/s)   trades %,d (%.1f/s)   hit %.1f%%   traded %s   rejects %,d%n",
            m.quotes, m.quotesPerSecond(nowMs), m.trades, m.tradesPerSecond(nowMs), 100 * m.hitRatio(),
            mm(m.tradedNotional), m.rejects()));
        final StringBuilder reasons = new StringBuilder();
        for (final RejectReason reason : RejectReason.values())
        {
            if (reason != RejectReason.NULL_VAL && m.rejectsByReason[reason.value()] > 0)
            {
                reasons.append(reason).append(' ').append(m.rejectsByReason[reason.value()]).append("   ");
            }
        }
        if (!reasons.isEmpty())
        {
            sb.append("  ").append(YELLOW).append(reasons).append(RESET).append('\n');
        }
        sb.append('\n');

        sb.append(BOLD).append(String.format("%-28s %14s %14s %12s%n", "POSITIONS", "net", "mark", "CS01")).append(RESET)
            .append(DIM).append("  CS01 = P&L if spreads widen 1bp").append(RESET).append('\n');
        double totalCs01 = 0;
        for (int i = 0; i < Instruments.COUNT; i++)
        {
            final double cs01 = m.cs01(i);
            totalCs01 += cs01;
            final long net = m.positions[i];
            final String colour = net > 0 ? GREEN : net < 0 ? RED : DIM;
            sb.append(String.format(Locale.ROOT, "  %-26s %s%14s%s %14s %12s%n",
                Instruments.name(i), colour, mm(net), RESET, money(m.markValue(i)), money(cs01)));
        }
        sb.append(String.format(Locale.ROOT, "  %-26s %14s %14s %s%12s%s%n%n", "total", "", "", BOLD, money(totalCs01), RESET));

        sb.append(BOLD).append(String.format("%-10s %-5s %10s %10s  %-" + BAR_WIDTH + "s %7s%n",
            "CREDIT", "tier", "limit", "used", "utilisation", "trades")).append(RESET);
        for (int id = 0; id < CreditBook.CAPACITY; id++)
        {
            if (m.knownClient[id])
            {
                final double used = m.limit[id] == 0 ? 0 : (double)m.utilised[id] / m.limit[id];
                sb.append(String.format(Locale.ROOT, "  client %-2d %-5d %10s %10s  %s %7d%n",
                    id, m.tier[id].ordinal() + 1, mm(m.limit[id]), mm(m.utilised[id]), bar(used), m.clientTrades[id]));
            }
        }
        sb.append('\n');

        sb.append(BOLD).append("BLOTTER").append(RESET).append('\n');
        int lines = 0;
        for (final MonitorModel.Event e : m.recent)
        {
            if (lines++ == 12)
            {
                break;
            }
            final String colour = switch (e.type())
            {
                case "TRADE" -> GREEN;
                case "REJECT" -> RED;
                default -> CYAN;
            };
            if (e.type().equals("REJECT"))
            {
                sb.append(String.format("  %s %s%-6s%s %s%n", MonitorModel.time(e.timeMs()), colour, e.type(), RESET, e.detail()));
            }
            else
            {
                sb.append(String.format(Locale.ROOT, "  %s %s%-6s%s client %-2d %-4s %8s %-26s %9.4f%s%n",
                    MonitorModel.time(e.timeMs()), colour, e.type(), RESET, e.clientId(),
                    e.clientBuys() ? "BUY" : "SELL", mm(e.notional()), Instruments.name(e.instrumentId()),
                    e.priceE6() / (double)Pricer.PRICE_SCALE,
                    e.type().equals("QUOTE") ? DIM + "  @" + e.spreadBps() + "bp" + RESET : ""));
            }
        }

        out.print(sb);
        out.flush();
    }

    private static String bar(final double fraction)
    {
        final int filled = (int)Math.round(Math.max(0, Math.min(1, fraction)) * BAR_WIDTH);
        final String colour = fraction > 0.9 ? RED : fraction > 0.6 ? YELLOW : GREEN;
        return colour + "█".repeat(filled) + DIM + "░".repeat(BAR_WIDTH - filled) + RESET;
    }

    static String mm(final long notional)
    {
        return String.format(Locale.ROOT, "%.1fmm", notional / 1_000_000.0);
    }

    static String money(final double value)
    {
        return String.format(Locale.ROOT, "%,.0f", value);
    }
}
