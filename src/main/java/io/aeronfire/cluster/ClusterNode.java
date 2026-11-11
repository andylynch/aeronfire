package io.aeronfire.cluster;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.samples.cluster.ClusterConfig;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Launches one cluster member: media driver, archive, consensus module and the RFQ service container.
 * <p>
 * Configure with environment variables:
 * <ul>
 *   <li>{@code NODE_ID}: 0, 1 or 2.</li>
 *   <li>{@code CLUSTER_HOSTS}: comma-separated hostnames of all members, in node-id order.</li>
 *   <li>{@code PORT_BASE}: first port; each member uses a block of ports above it.</li>
 * </ul>
 * All three members can share one host (the defaults), since ports are offset by node id.
 */
public final class ClusterNode
{
    private static final Logger LOG = LoggerFactory.getLogger(ClusterNode.class);

    private ClusterNode()
    {
    }

    @SuppressWarnings("try")
    public static void main(final String[] args)
    {
        final int nodeId = Integer.parseInt(env("NODE_ID", "0"));
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));

        final RfqClusteredService service = new RfqClusteredService();
        final ClusterConfig config = ClusterConfig.create(nodeId, hosts, hosts, portBase, service);
        // ClusterConfig doesn't set this; the member's own ingress endpoint comes from the cluster members string.
        config.consensusModuleContext().ingressChannel("aeron:udp?term-length=64k");
        final String controlSecret = env("CONTROL_SECRET", ClusterAuthenticator.DEFAULT_SECRET);
        config.consensusModuleContext().authenticatorSupplier(() -> new ClusterAuthenticator(controlSecret));
        config.mediaDriverContext().errorHandler(errorHandler(nodeId, "media driver"));
        config.archiveContext().errorHandler(errorHandler(nodeId, "archive"));
        config.aeronArchiveContext().errorHandler(errorHandler(nodeId, "archive client"));
        config.consensusModuleContext().errorHandler(errorHandler(nodeId, "consensus module"));
        config.clusteredServiceContext().errorHandler(errorHandler(nodeId, "service"));
        wipeIfRequested(nodeId, config);
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        try (ClusteredMediaDriver ignoredDriver = ClusteredMediaDriver.launch(
                config.mediaDriverContext(), config.archiveContext(), config.consensusModuleContext());
             ClusteredServiceContainer ignoredContainer = ClusteredServiceContainer.launch(
                config.clusteredServiceContext()))
        {
            LOG.info("aeronfire node {} up, members {}, port base {}", nodeId, hosts, portBase);
            startLatencyReporter(nodeId, service.timings());
            barrier.await();
            LOG.info("aeronfire node {} shutting down", nodeId);
        }
    }

    /**
     * Demo hook: if the marker file exists, delete this member's Aeron, archive and cluster state before starting,
     * so the member rejoins as if it were a brand-new machine and catches up from the leader. The demo panel
     * drops the marker with a fixed command, kills the member and starts it again. Off unless the file is there.
     */
    private static void wipeIfRequested(final int nodeId, final ClusterConfig config)
    {
        final java.io.File marker = new java.io.File(env("WIPE_MARKER", "/tmp/aeronfire-wipe"));
        if (!marker.exists())
        {
            return;
        }
        System.out.printf("aeronfire node %d: wipe marker found, deleting local cluster state%n", nodeId);
        final String aeronDir = config.mediaDriverContext().aeronDirectoryName();
        deleteTree(null == aeronDir ? null : new java.io.File(aeronDir));
        deleteTree(config.archiveContext().archiveDir());
        deleteTree(config.consensusModuleContext().clusterDir());
        deleteTree(config.clusteredServiceContext().clusterDir());
        if (!marker.delete())
        {
            System.err.printf("aeronfire node %d: could not delete %s%n", nodeId, marker);
        }
    }

    private static void deleteTree(final java.io.File directory)
    {
        if (null != directory && directory.exists())
        {
            org.agrona.IoUtil.delete(directory, true);
        }
    }

    /**
     * LATENCY_REPORT_S=n prints the service handling-time histograms every n seconds (for the latency report).
     */
    private static void startLatencyReporter(final int nodeId, final ServiceTimings timings)
    {
        final int seconds = Integer.parseInt(env("LATENCY_REPORT_S", "0"));
        if (seconds <= 0)
        {
            return;
        }
        final Thread t = new Thread(() ->
        {
            while (!Thread.currentThread().isInterrupted())
            {
                try
                {
                    Thread.sleep(seconds * 1_000L);
                }
                catch (final InterruptedException ex)
                {
                    return;
                }
                System.out.print(timings.report(nodeId));
                System.out.flush();
            }
        }, "latency-reporter");
        t.setDaemon(true);
        t.start();
    }

    private static org.agrona.ErrorHandler errorHandler(final int nodeId, final String component)
    {
        return (throwable) ->
        {
            LOG.error("node {} {} error", nodeId, component, throwable);
        };
    }

    static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }
}
