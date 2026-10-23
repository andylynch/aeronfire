package io.aeronfire.cluster;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.samples.cluster.ClusterConfig;
import org.agrona.concurrent.ShutdownSignalBarrier;

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
    private ClusterNode()
    {
    }

    @SuppressWarnings("try")
    public static void main(final String[] args)
    {
        final int nodeId = Integer.parseInt(env("NODE_ID", "0"));
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));

        final ClusterConfig config = ClusterConfig.create(nodeId, hosts, hosts, portBase, new RfqClusteredService());
        // ClusterConfig doesn't set this; the member's own ingress endpoint comes from the cluster members string.
        config.consensusModuleContext().ingressChannel("aeron:udp?term-length=64k");
        config.mediaDriverContext().errorHandler(errorHandler(nodeId, "media driver"));
        config.archiveContext().errorHandler(errorHandler(nodeId, "archive"));
        config.aeronArchiveContext().errorHandler(errorHandler(nodeId, "archive client"));
        config.consensusModuleContext().errorHandler(errorHandler(nodeId, "consensus module"));
        config.clusteredServiceContext().errorHandler(errorHandler(nodeId, "service"));
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        try (ClusteredMediaDriver ignoredDriver = ClusteredMediaDriver.launch(
                config.mediaDriverContext(), config.archiveContext(), config.consensusModuleContext());
             ClusteredServiceContainer ignoredContainer = ClusteredServiceContainer.launch(
                config.clusteredServiceContext()))
        {
            System.out.printf("aeronfire node %d up, members %s, port base %d%n", nodeId, hosts, portBase);
            barrier.await();
            System.out.printf("aeronfire node %d shutting down%n", nodeId);
        }
    }

    private static org.agrona.ErrorHandler errorHandler(final int nodeId, final String component)
    {
        return (throwable) ->
        {
            System.err.printf("node %d %s error: %s%n", nodeId, component, throwable);
            throwable.printStackTrace(System.err);
        };
    }

    static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }
}
