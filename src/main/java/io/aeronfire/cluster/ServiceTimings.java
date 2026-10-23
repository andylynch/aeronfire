package io.aeronfire.cluster;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.TimeUnit;

/**
 * Wall-clock time the service spends handling each request (decode, checks, state change, journal, egress offers).
 * Metrics only: never read by the state machine, so it has no effect on determinism. Recorders are wait-free for
 * the service thread; the reporter thread takes interval snapshots.
 */
public final class ServiceTimings
{
    private final Recorder rfq = new Recorder(TimeUnit.SECONDS.toNanos(10), 3);
    private final Recorder accept = new Recorder(TimeUnit.SECONDS.toNanos(10), 3);

    public void rfq(final long nanos)
    {
        rfq.recordValue(Math.min(nanos, TimeUnit.SECONDS.toNanos(10)));
    }

    public void accept(final long nanos)
    {
        accept.recordValue(Math.min(nanos, TimeUnit.SECONDS.toNanos(10)));
    }

    /**
     * One line per request type for the interval since the last call; empty if nothing was handled.
     */
    public String report(final int nodeId)
    {
        final StringBuilder sb = new StringBuilder();
        line(sb, nodeId, "rfq", rfq.getIntervalHistogram());
        line(sb, nodeId, "accept", accept.getIntervalHistogram());
        return sb.toString();
    }

    private static void line(final StringBuilder sb, final int nodeId, final String name, final Histogram h)
    {
        if (h.getTotalCount() == 0)
        {
            return;
        }
        sb.append(String.format("latency ts=%d node=%d hop=service-%s n=%d p50=%d p99=%d p99.9=%d max=%d%n",
            System.currentTimeMillis(), nodeId, name, h.getTotalCount(), h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getValueAtPercentile(99.9),
            h.getMaxValue()));
    }
}
