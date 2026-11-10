package io.aeronfire.demo;

import io.aeronfire.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The panel's view of the world, refreshed in the background: the monitor's cluster and flow state, the venue's
 * latency numbers, and the Docker state of each member. It also does the timing that makes the demo convincing:
 * when a fault or a risk scenario is triggered it watches the monitor and writes to the timeline how long the
 * cluster took to elect a leader, resume trading, or start enforcing the control.
 */
final class DemoState implements Faults.Watcher, Scenarios.Observer
{
    private static final long WATCH_TIMEOUT_MS = 30_000;
    private static final long FOLLOWER_SETTLE_MS = 2_000;

    private static final class Watch
    {
        final int nodeId;
        final String op;
        final boolean wasLeader;
        final long startMs;
        final long changes0;
        long tradesAtElection = -1;
        boolean elected;

        Watch(final int nodeId, final String op, final boolean wasLeader, final long startMs, final long changes0)
        {
            this.nodeId = nodeId;
            this.op = op;
            this.wasLeader = wasLeader;
            this.startMs = startMs;
            this.changes0 = changes0;
        }
    }

    private record ReasonWatch(String title, String reason, long startMs, long count0)
    {
    }

    private record Since(String state, long sinceMs)
    {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final String monitorUrl;
    private final String venueUrl;
    private final DockerApi docker;
    private final List<String> nodes;
    private final Timeline timeline;
    private final Map<String, Since> since = new HashMap<>();
    private final List<ReasonWatch> reasonWatches = new ArrayList<>();

    private volatile Object monitor;
    private volatile Object venue;
    private volatile List<DockerApi.Container> containers = List.of();
    private volatile boolean dockerUp;
    private volatile long lastMonitorOkMs;
    private Watch watch;

    DemoState(
        final String monitorUrl, final String venueUrl, final DockerApi docker, final List<String> nodes, final Timeline timeline)
    {
        this.monitorUrl = trim(monitorUrl);
        this.venueUrl = trim(venueUrl);
        this.docker = docker;
        this.nodes = List.copyOf(nodes);
        this.timeline = timeline;
    }

    void start(final ScheduledExecutorService scheduler)
    {
        scheduler.scheduleWithFixedDelay(this::pollFast, 0, 500, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::pollDocker, 0, 1, TimeUnit.SECONDS);
    }

    // ---- Faults.Watcher / Scenarios.Observer -------------------------------------------------------------

    @Override
    public synchronized void onFault(final int nodeId, final String op)
    {
        final Object m = monitor;
        final boolean wasLeader = number(m, "cluster.leader", -2) == nodeId;
        watch = new Watch(nodeId, op, wasLeader, System.currentTimeMillis(), number(m, "cluster.leaderChanges", 0));
        if (wasLeader)
        {
            timeline.add("info", "node" + nodeId + " is the current leader. Watching for the election...");
        }
    }

    @Override
    public synchronized void onScenario(final String id, final String title, final String expectedReason)
    {
        reasonWatches.add(new ReasonWatch(title, expectedReason, System.currentTimeMillis(),
            number(monitor, "flow.rejectsByReason." + expectedReason, 0)));
    }

    // ---- polling -----------------------------------------------------------------------------------------

    private void pollFast()
    {
        final Object m = get(monitorUrl + "/state");
        if (null != m)
        {
            monitor = m;
            lastMonitorOkMs = System.currentTimeMillis();
        }
        venue = get(venueUrl + "/stats");
        try
        {
            observe();
        }
        catch (final RuntimeException ex)
        {
            System.err.println("demo observe failed: " + ex);
        }
    }

    private void pollDocker()
    {
        if (null == docker)
        {
            return;
        }
        try
        {
            final List<DockerApi.Container> list = docker.containers(Set.copyOf(nodes));
            final long now = System.currentTimeMillis();
            synchronized (since)
            {
                for (final DockerApi.Container c : list)
                {
                    final Since previous = since.get(c.service());
                    if (null == previous || !previous.state().equals(c.state()))
                    {
                        since.put(c.service(), new Since(c.state(), now));
                    }
                }
            }
            containers = list;
            dockerUp = true;
        }
        catch (final IOException | RuntimeException ex)
        {
            dockerUp = false;
        }
    }

    private synchronized void observe()
    {
        final Object m = monitor;
        if (null == m)
        {
            return;
        }
        final long now = System.currentTimeMillis();
        final long trades = number(m, "flow.trades", 0);
        final int leader = (int)number(m, "cluster.leader", -1);
        final long changes = number(m, "cluster.leaderChanges", 0);
        final long term = number(m, "cluster.term", 0);

        final Watch w = watch;
        if (null != w)
        {
            final long elapsed = now - w.startMs;
            if (w.wasLeader && !w.elected && changes > w.changes0 && leader >= 0 && leader != w.nodeId)
            {
                w.elected = true;
                w.tradesAtElection = trades;
                timeline.add("measure", String.format(Locale.ROOT,
                    "New leader: node%d elected (term %d) %.1fs after node%d %s.", leader, term, elapsed / 1000.0,
                    w.nodeId, verb(w.op)));
            }
            if (w.wasLeader && w.elected && trades > w.tradesAtElection)
            {
                timeline.add("measure", String.format(Locale.ROOT,
                    "Trading resumed %.1fs after the %s. Trade count carried on to %d (no reset: the state lives on "
                        + "the other members). RFQs caught in the gap timed out and were retried.",
                    elapsed / 1000.0, w.op, trades));
                watch = null;
            }
            else if (!w.wasLeader && elapsed >= FOLLOWER_SETTLE_MS)
            {
                timeline.add("measure", String.format(Locale.ROOT,
                    "node%d was a follower: no interruption. Still %.0f trades/s and the same leader (node%d, term %d).",
                    w.nodeId, decimal(m, "flow.tradesPerSec"), leader, term));
                watch = null;
            }
            else if (elapsed > WATCH_TIMEOUT_MS)
            {
                timeline.add("warn", "No recovery after 30s. With fewer than 2 of 3 members the cluster stops by design;"
                    + " start a member to resume.");
                watch = null;
            }
        }

        reasonWatches.removeIf((rw) ->
        {
            final long count = number(m, "flow.rejectsByReason." + rw.reason(), 0);
            if (count > rw.count0())
            {
                timeline.add("measure", String.format(Locale.ROOT,
                    "%s enforced: first %s reject %.1fs after the change (%d so far).",
                    rw.title(), rw.reason(), (now - rw.startMs()) / 1000.0, count - rw.count0()));
                return true;
            }
            return now - rw.startMs() > 20_000;
        });
    }

    // ---- views -------------------------------------------------------------------------------------------

    /**
     * Services that have been down or frozen for longer than {@code olderThanMs}; the auto-heal loop restarts them.
     */
    List<DockerApi.Container> unhealthyFor(final long olderThanMs)
    {
        final long now = System.currentTimeMillis();
        final List<DockerApi.Container> out = new ArrayList<>();
        for (final DockerApi.Container c : containers)
        {
            final Since s;
            synchronized (since)
            {
                s = since.get(c.service());
            }
            if (!"running".equals(c.state()) && null != s && now - s.sinceMs() > olderThanMs)
            {
                out.add(c);
            }
        }
        return out;
    }

    int runningMembers()
    {
        int running = 0;
        for (final DockerApi.Container c : containers)
        {
            if ("running".equals(c.state()))
            {
                running++;
            }
        }
        return running;
    }

    boolean dockerUp()
    {
        return dockerUp;
    }

    String toJson(final Faults faults, final Scenarios scenarios, final int maxRate, final long autoHealSeconds,
        final String blotterUrl)
    {
        final Object m = monitor;
        final Object v = venue;
        final long now = System.currentTimeMillis();
        final int leader = (int)number(m, "cluster.leader", -1);
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"now\":").append(now)
            .append(",\"monitorUp\":").append(null != m && now - lastMonitorOkMs < 3_000)
            .append(",\"dockerUp\":").append(dockerUp)
            .append(",\"autoHealS\":").append(autoHealSeconds)
            .append(",\"blotterUrl\":").append(Json.quote(blotterUrl));

        sb.append(",\"cluster\":");
        final Object cluster = Json.path(m, "cluster");
        if (cluster instanceof Map<?, ?> map)
        {
            final StringBuilder c = new StringBuilder(JsonOut.of(map));
            c.setLength(c.length() - 1);
            final int needed = nodes.size() / 2 + 1;
            c.append(",\"size\":").append(nodes.size()).append(",\"needed\":").append(needed)
                .append(",\"running\":").append(dockerUp ? runningMembers() : -1).append('}');
            sb.append(c);
        }
        else
        {
            sb.append("null");
        }

        sb.append(",\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++)
        {
            final String service = nodes.get(i);
            DockerApi.Container found = null;
            for (final DockerApi.Container c : containers)
            {
                if (c.service().equals(service))
                {
                    found = c;
                }
            }
            final Since s;
            synchronized (since)
            {
                s = since.get(service);
            }
            final String state = null == found ? "unknown" : found.state();
            final String role = !"running".equals(state) ? "down" : leader == i ? "leader" : leader >= 0 ? "follower" : "unknown";
            sb.append(i == 0 ? "" : ",")
                .append("{\"id\":").append(i)
                .append(",\"service\":").append(Json.quote(service))
                .append(",\"state\":").append(Json.quote(state))
                .append(",\"status\":").append(Json.quote(null == found ? "" : found.status()))
                .append(",\"role\":").append(Json.quote(role))
                .append(",\"stateSinceMs\":").append(null == s ? 0 : s.sinceMs())
                .append(",\"netem\":").append(Json.quote(faults.netemOf(service)))
                .append(",\"netemSinceMs\":").append(faults.netemSinceMs(service))
                .append('}');
        }
        sb.append(']');

        sb.append(",\"flow\":").append(JsonOut.of(Json.path(m, "flow")));
        sb.append(",\"positions\":").append(JsonOut.of(Json.path(m, "positions")));
        sb.append(",\"credit\":").append(JsonOut.of(Json.path(m, "credit")));
        sb.append(",\"recent\":[");
        if (Json.path(m, "recent") instanceof List<?> recent)
        {
            for (int i = 0; i < Math.min(14, recent.size()); i++)
            {
                sb.append(i == 0 ? "" : ",").append(JsonOut.of(recent.get(i)));
            }
        }
        sb.append(']');

        sb.append(",\"load\":{\"rate\":").append(number(v, "rate", -1))
            .append(",\"maxRate\":").append(Math.min(maxRate, number(v, "maxRate", maxRate)))
            .append(",\"venueUp\":").append(null != v)
            .append(",\"stats\":").append(JsonOut.of(Json.path(v, "stats"))).append('}');

        final Map<String, Long> active = scenarios.activeWithRevertTime();
        sb.append(",\"scenarios\":[");
        boolean first = true;
        for (final Scenarios.Def d : Scenarios.defs())
        {
            sb.append(first ? "" : ",")
                .append("{\"id\":").append(Json.quote(d.id()))
                .append(",\"title\":").append(Json.quote(d.title()))
                .append(",\"story\":").append(Json.quote(d.story()))
                .append(",\"expect\":").append(Json.quote(d.expect()))
                .append(",\"active\":").append(active.containsKey(d.id()))
                .append(",\"revertAt\":").append(active.getOrDefault(d.id(), 0L)).append('}');
            first = false;
        }
        sb.append(']');

        sb.append(",\"timeline\":").append(timeline.toJson(60)).append('}');
        return sb.toString();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private Object get(final String url)
    {
        try
        {
            final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Json.parse(response.body()) : null;
        }
        catch (final IOException | RuntimeException ex)
        {
            return null;
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static long number(final Object root, final String path, final long fallback)
    {
        return Json.path(root, path) instanceof Number n ? n.longValue() : fallback;
    }

    private static double decimal(final Object root, final String path)
    {
        return Json.path(root, path) instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String verb(final String op)
    {
        return switch (op)
        {
            case "kill" -> "crashed";
            case "stop" -> "was stopped";
            case "pause" -> "froze";
            case "wipe" -> "was wiped";
            default -> op;
        };
    }

    private static String trim(final String url)
    {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
