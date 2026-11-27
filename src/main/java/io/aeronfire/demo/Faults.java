package io.aeronfire.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Infrastructure faults against the cluster members, done for real through Docker: crash a member, stop it
 * cleanly, freeze it (a long GC pause or VM stall), degrade its network (delay, jitter, loss) or wipe its
 * state and make it rejoin from scratch.
 * <p>
 * Everything is an allowlist. Only the configured member services can be touched, only these operations
 * exist, numbers are range-checked, and the commands run inside a container are fixed templates.
 */
final class Faults
{
    /** Told just before a fault lands, so the panel can time how long the cluster takes to recover. */
    interface Watcher
    {
        void onFault(int nodeId, String op);
    }

    static final String WIPE_MARKER = "/tmp/aeronfire-wipe";
    private static final Pattern IFACE = Pattern.compile("[a-zA-Z0-9]{1,15}");

    private final DockerApi docker;
    private final List<String> nodes;
    private final Timeline timeline;
    private final Watcher watcher;
    private final String iface;
    private final Map<String, String> netem = new ConcurrentHashMap<>();
    private final Map<String, Long> netemSince = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    Faults(
        final DockerApi docker, final List<String> nodes, final Timeline timeline, final Watcher watcher, final String iface)
    {
        if (!IFACE.matcher(iface).matches())
        {
            throw new IllegalArgumentException("bad network interface name: " + iface);
        }
        this.docker = docker;
        this.nodes = List.copyOf(nodes);
        this.timeline = timeline;
        this.watcher = watcher;
        this.iface = iface;
    }

    List<String> nodes()
    {
        return nodes;
    }

    String netemOf(final String service)
    {
        return netem.get(service);
    }

    long netemSinceMs(final String service)
    {
        return netemSince.getOrDefault(service, 0L);
    }

    /**
     * Apply one fault. Operations are serialised so a wipe's kill-then-start can't interleave with another click.
     */
    String apply(final String service, final String op, final int delayMs, final int jitterMs, final int lossPct)
        throws IOException
    {
        final int nodeId = nodes.indexOf(service);
        if (nodeId < 0)
        {
            throw new IllegalArgumentException("unknown node: " + service);
        }
        if (null == docker)
        {
            throw new IOException("Docker is not available to the demo panel, so infrastructure faults are off");
        }
        lock.lock();
        try
        {
            final DockerApi.Container c = find(service);
            return switch (op)
            {
                case "kill" -> kill(c, nodeId);
                case "stop" -> stop(c, nodeId);
                case "start" -> start(c);
                case "pause" -> pause(c, nodeId);
                case "unpause" -> unpause(c);
                case "restart" -> restart(c, nodeId);
                case "netem" -> degradeNetwork(c, delayMs, jitterMs, lossPct);
                case "netem-clear" -> clearNetwork(c);
                case "wipe" -> wipe(c, nodeId);
                default -> throw new IllegalArgumentException("unknown operation: " + op);
            };
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Put everything back: unfreeze, start anything down, remove network faults. Returns what it changed.
     */
    List<String> healAll() throws IOException
    {
        final List<String> changes = new ArrayList<>();
        if (null == docker)
        {
            return changes;
        }
        lock.lock();
        try
        {
            for (final DockerApi.Container c : docker.containers(Set.copyOf(nodes)))
            {
                if ("paused".equals(c.state()) && docker.action(c.id(), "unpause").ok())
                {
                    changes.add(c.service() + " unfrozen");
                }
                else if (!"running".equals(c.state()) && !"paused".equals(c.state()) && docker.action(c.id(), "start").ok())
                {
                    changes.add(c.service() + " started");
                }
                if (netem.containsKey(c.service()) && "running".equals(c.state()))
                {
                    docker.exec(c.id(), List.of("tc", "qdisc", "del", "dev", iface, "root"));
                    netem.remove(c.service());
                    netemSince.remove(c.service());
                    changes.add(c.service() + " network restored");
                }
            }
        }
        finally
        {
            lock.unlock();
        }
        return changes;
    }

    private String kill(final DockerApi.Container c, final int nodeId) throws IOException
    {
        requireState(c, "running", "paused");
        watcher.onFault(nodeId, "kill");
        done(docker.action(c.id(), "kill"), "kill");
        timeline.add("fault", c.service() + " crashed (SIGKILL: no clean shutdown, no goodbye)");
        warnIfQuorumLost(nodeId);
        return c.service() + " killed";
    }

    private String stop(final DockerApi.Container c, final int nodeId) throws IOException
    {
        requireState(c, "running");
        watcher.onFault(nodeId, "stop");
        done(docker.action(c.id(), "stop"), "stop");
        timeline.add("fault", c.service() + " stopped cleanly (planned maintenance)");
        warnIfQuorumLost(nodeId);
        return c.service() + " stopped";
    }

    private String start(final DockerApi.Container c) throws IOException
    {
        if ("running".equals(c.state()))
        {
            return c.service() + " is already running";
        }
        if ("paused".equals(c.state()))
        {
            return unpause(c);
        }
        done(docker.action(c.id(), "start"), "start");
        timeline.add("heal", c.service() + " started, rejoining from its own log and snapshot");
        return c.service() + " started";
    }

    private String pause(final DockerApi.Container c, final int nodeId) throws IOException
    {
        requireState(c, "running");
        watcher.onFault(nodeId, "pause");
        done(docker.action(c.id(), "pause"), "pause");
        timeline.add("fault", c.service() + " frozen: process alive but silent, like a long GC pause or VM stall");
        warnIfQuorumLost(nodeId);
        return c.service() + " frozen";
    }

    private String unpause(final DockerApi.Container c) throws IOException
    {
        requireState(c, "paused");
        done(docker.action(c.id(), "unpause"), "unpause");
        timeline.add("heal", c.service() + " resumed, catching up on what it missed");
        return c.service() + " resumed";
    }

    private String restart(final DockerApi.Container c, final int nodeId) throws IOException
    {
        requireState(c, "running", "paused");
        watcher.onFault(nodeId, "restart");
        done(docker.action(c.id(), "restart"), "restart");
        timeline.add("fault", c.service() + " restarted (rolling-upgrade style)");
        return c.service() + " restarted";
    }

    private String degradeNetwork(final DockerApi.Container c, final int delayMs, final int jitterMs, final int lossPct)
        throws IOException
    {
        requireState(c, "running");
        if (delayMs < 0 || delayMs > 500 || jitterMs < 0 || jitterMs > 200 || lossPct < 0 || lossPct > 30)
        {
            throw new IllegalArgumentException("delay 0-500ms, jitter 0-200ms, loss 0-30% only");
        }
        if (delayMs == 0 && lossPct == 0)
        {
            return clearNetwork(c);
        }
        final List<String> cmd = new ArrayList<>(List.of("tc", "qdisc", "replace", "dev", iface, "root", "netem"));
        final StringBuilder what = new StringBuilder();
        if (delayMs > 0)
        {
            cmd.addAll(List.of("delay", delayMs + "ms", jitterMs + "ms"));
            what.append(delayMs).append("ms delay");
            if (jitterMs > 0)
            {
                what.append(" ±").append(jitterMs).append("ms");
            }
        }
        if (lossPct > 0)
        {
            cmd.addAll(List.of("loss", lossPct + "%"));
            what.append(what.isEmpty() ? "" : ", ").append(lossPct).append("% packet loss");
        }
        final int exit = docker.exec(c.id(), cmd);
        if (exit != 0)
        {
            throw new IOException("tc failed (exit " + exit + "): is NET_ADMIN granted and iproute2 installed?");
        }
        netem.put(c.service(), what.toString());
        netemSince.put(c.service(), System.currentTimeMillis());
        timeline.add("fault", c.service() + " network degraded: " + what + " on its egress");
        return c.service() + " network degraded";
    }

    private String clearNetwork(final DockerApi.Container c) throws IOException
    {
        requireState(c, "running");
        docker.exec(c.id(), List.of("tc", "qdisc", "del", "dev", iface, "root")); // exit 2 if none: fine
        if (null != netem.remove(c.service()))
        {
            timeline.add("heal", c.service() + " network back to normal");
        }
        netemSince.remove(c.service());
        return c.service() + " network restored";
    }

    /**
     * Drop a marker the member checks at start-up, crash it, start it again: it comes back with no Aeron,
     * archive or cluster state at all and has to catch up from the leader.
     */
    private String wipe(final DockerApi.Container c, final int nodeId) throws IOException
    {
        requireState(c, "running");
        final int exit = docker.exec(c.id(), List.of("touch", WIPE_MARKER));
        if (exit != 0)
        {
            throw new IOException("could not drop the wipe marker (exit " + exit + ")");
        }
        watcher.onFault(nodeId, "wipe");
        done(docker.action(c.id(), "kill"), "kill");
        timeline.add("fault", c.service() + " crashed and its disk wiped: it has no log, no snapshot, no memory");
        warnIfQuorumLost(nodeId);
        done(docker.action(c.id(), "start"), "start");
        timeline.add("heal", c.service() + " started empty and is rejoining: catching up from the leader");
        return c.service() + " wiped and restarted";
    }

    private void warnIfQuorumLost(final int excludingNodeId) throws IOException
    {
        int running = 0;
        for (final DockerApi.Container other : docker.containers(Set.copyOf(nodes)))
        {
            if ("running".equals(other.state()) && nodes.indexOf(other.service()) != excludingNodeId)
            {
                running++;
            }
        }
        final int needed = nodes.size() / 2 + 1;
        if (running < needed)
        {
            timeline.add("warn", "Quorum lost: " + needed + " of " + nodes.size() + " members needed. The cluster stops "
                + "accepting trades rather than risk diverging. Nothing is lost; bring a member back and it resumes.");
        }
    }

    private DockerApi.Container find(final String service) throws IOException
    {
        for (final DockerApi.Container c : docker.containers(Set.copyOf(nodes)))
        {
            if (c.service().equals(service))
            {
                return c;
            }
        }
        throw new IOException("no container found for " + service);
    }

    private static void requireState(final DockerApi.Container c, final String... allowed)
    {
        for (final String state : allowed)
        {
            if (state.equals(c.state()))
            {
                return;
            }
        }
        throw new IllegalArgumentException(c.service() + " is " + c.state() + "; operation needs " + String.join(" or ", allowed));
    }

    private static void done(final DockerApi.Result result, final String what) throws IOException
    {
        if (!result.ok() && result.status() != 304)
        {
            throw new IOException("docker " + what + " failed: " + result.status() + " " + result.body());
        }
    }
}
