package io.aeronfire.control;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.cluster.ClusterAuthenticator;
import io.aeronfire.sbe.AlgoControlsStatusDecoder;
import io.aeronfire.sbe.ApprovalDecoder;
import io.aeronfire.sbe.BooleanType;
import io.aeronfire.sbe.ClientEligibilityStatusDecoder;
import io.aeronfire.sbe.CreditResetAckDecoder;
import io.aeronfire.sbe.CreditStatusDecoder;
import io.aeronfire.sbe.EntitlementStatusDecoder;
import io.aeronfire.sbe.InventoryStatusDecoder;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.PendingChangeStatusDecoder;
import io.aeronfire.sbe.RejectDecoder;
import io.aeronfire.sbe.StateDumpEndDecoder;
import io.aeronfire.sbe.SubscribeEncoder;
import io.aeronfire.util.ShutdownFlag;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The control plane: the one way operators change the system.
 * <p>
 * Operations are exposed as JMX MBeans under {@code aeronfire:type=*}. Attach the Jolokia JVM agent and they
 * are also JSON over HTTP; the admin web page calls the same MBeans in-process. MBean operations only send
 * commands; the cluster authorises them per user (entitlements, scope, limits, four-eyes) and journals them.
 * <p>
 * Two cluster sessions, both authenticated as control:
 * <ul>
 *   <li><b>command</b>: one command at a time; its replies are exactly that command's outcome.</li>
 *   <li><b>view</b>: subscribed for the state dump and drop copy, maintaining {@link ControlView}.</li>
 * </ul>
 * Aeron clients aren't thread-safe, so JMX and HTTP threads hand commands to the poll thread over a queue.
 */
public final class ControlPlane
{
    private static final long COMMAND_TIMEOUT_MS = 5_000;

    /**
     * One command in flight: its bytes, whether it's an approval (which yields two replies), and its result.
     */
    private record Command(byte[] bytes, boolean approval, CompletableFuture<String> result)
    {
    }

    private final ControlView view = new ControlView();
    private final ManyToOneConcurrentArrayQueue<Command> queue = new ManyToOneConcurrentArrayQueue<>(1024);
    private final Queue<Command> inFlight = new ArrayDeque<>();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final StringBuilder outcome = new StringBuilder();

    // ------------------------------------------------------------------ API used by MBeans and the web page

    /**
     * Send a command and wait for the cluster's decision.
     *
     * @return "APPLIED: ...", "PENDING APPROVAL #n: ..." or "REJECTED: reason".
     */
    public String submit(final byte[] command, final boolean approval)
    {
        final Command c = new Command(command, approval, new CompletableFuture<>());
        if (!queue.offer(c))
        {
            return "REJECTED: control plane busy";
        }
        try
        {
            return c.result().get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (final Exception ex)
        {
            return "UNKNOWN: no reply within " + COMMAND_TIMEOUT_MS + "ms (" + ex.getClass().getSimpleName() + ")";
        }
    }

    public ControlView view()
    {
        return view;
    }

    // ------------------------------------------------------------------ egress: command session

    private final EgressListener commandListener = (clusterSessionId, timestamp, buffer, offset, length, h) ->
    {
        final Command current = inFlight.peek();
        if (null == current)
        {
            return;
        }
        header.wrap(buffer, offset);
        final int templateId = header.templateId();
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        updateView(buffer, offset, templateId);

        if (templateId == RejectDecoder.TEMPLATE_ID)
        {
            final RejectDecoder d = new RejectDecoder().wrap(buffer, bodyOffset, header.blockLength(), header.version());
            complete("REJECTED: " + d.reason());
        }
        else if (templateId == PendingChangeStatusDecoder.TEMPLATE_ID)
        {
            final PendingChangeStatusDecoder d =
                new PendingChangeStatusDecoder().wrap(buffer, bodyOffset, header.blockLength(), header.version());
            final String what = Commands.describeTemplate(d.templateId());
            complete(switch (d.state())
            {
                case PENDING -> "PENDING APPROVAL #" + d.pendingId() + ": " + what + " needs a second user";
                case APPROVED -> "APPLIED: #" + d.pendingId() + " " + what + " approved by " + Users.nameOf(d.decidedBy()) +
                    (outcome.isEmpty() ? "" : " (" + outcome + ")");
                default -> "APPLIED: #" + d.pendingId() + " " + what + " rejected by " + Users.nameOf(d.decidedBy());
            });
        }
        else
        {
            final String applied = describeStatus(templateId);
            if (current.approval())
            {
                outcome.setLength(0);
                outcome.append(applied); // the approval's own status follows
            }
            else
            {
                complete("APPLIED: " + applied);
            }
        }
    };

    private void complete(final String result)
    {
        final Command done = inFlight.poll();
        outcome.setLength(0);
        if (null != done)
        {
            done.result().complete(result);
        }
    }

    private String describeStatus(final int templateId)
    {
        return switch (templateId)
        {
            case CreditStatusDecoder.TEMPLATE_ID -> "credit line updated";
            case CreditResetAckDecoder.TEMPLATE_ID -> "credit utilisation reset";
            case InventoryStatusDecoder.TEMPLATE_ID -> "inventory updated";
            case AlgoControlsStatusDecoder.TEMPLATE_ID -> "algo controls updated (kill switch " +
                (view.killSwitch() ? "ENGAGED" : "off") + ")";
            case EntitlementStatusDecoder.TEMPLATE_ID -> "entitlements updated";
            case ClientEligibilityStatusDecoder.TEMPLATE_ID -> "client eligibility updated";
            default -> "template " + templateId;
        };
    }

    // ------------------------------------------------------------------ egress: view session

    private final EgressListener viewListener = new EgressListener()
    {
        @Override
        public void onMessage(
            final long clusterSessionId, final long timestamp, final DirectBuffer buffer, final int offset,
            final int length, final Header h)
        {
            header.wrap(buffer, offset);
            updateView(buffer, offset, header.templateId());
        }

        @Override
        public void onNewLeader(
            final long clusterSessionId, final long leadershipTermId, final int leaderMemberId, final String endpoints)
        {
            view.leader(leaderMemberId, leadershipTermId);
        }
    };

    private void updateView(final DirectBuffer buffer, final int offset, final int templateId)
    {
        final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int bl = header.blockLength();
        final int v = header.version();
        switch (templateId)
        {
            case CreditStatusDecoder.TEMPLATE_ID ->
            {
                final CreditStatusDecoder d = new CreditStatusDecoder().wrap(buffer, body, bl, v);
                view.credit(d.clientId(), d.tierRaw() + 1, d.creditLimit(), d.utilised());
            }
            case InventoryStatusDecoder.TEMPLATE_ID ->
            {
                final InventoryStatusDecoder d = new InventoryStatusDecoder().wrap(buffer, body, bl, v);
                view.inventory(d.instrumentId(), d.maxLong(), d.maxShort(),
                    d.haltedRaw() == BooleanType.TRUE.value(), d.netNotional());
            }
            case AlgoControlsStatusDecoder.TEMPLATE_ID ->
            {
                final AlgoControlsStatusDecoder d = new AlgoControlsStatusDecoder().wrap(buffer, body, bl, v);
                view.algo(d.killSwitchRaw() == BooleanType.TRUE.value(), d.priceCollarBps(), d.maxRfqsPerSecond());
            }
            case EntitlementStatusDecoder.TEMPLATE_ID ->
            {
                final EntitlementStatusDecoder d = new EntitlementStatusDecoder().wrap(buffer, body, bl, v);
                view.entitlement(d.targetUserId(), new ControlView.Entitlement(d.permissions(),
                    d.allClientsRaw() == BooleanType.TRUE.value(), d.instrumentScope(), d.maxCreditLimit(),
                    d.maxAdjust(), d.maxInventoryLimit(), d.clientCount()));
            }
            case PendingChangeStatusDecoder.TEMPLATE_ID ->
            {
                final PendingChangeStatusDecoder d = new PendingChangeStatusDecoder().wrap(buffer, body, bl, v);
                view.pending(d.pendingId(), d.requestedBy(), d.templateId(), d.state());
            }
            case ClientEligibilityStatusDecoder.TEMPLATE_ID ->
            {
                final ClientEligibilityStatusDecoder d = new ClientEligibilityStatusDecoder().wrap(buffer, body, bl, v);
                view.eligibility(d.clientId(), d.kycValidRaw() == BooleanType.TRUE.value(),
                    d.sanctionedRaw() == BooleanType.TRUE.value());
            }
            case StateDumpEndDecoder.TEMPLATE_ID ->
            {
            }
            default ->
            {
            }
        }
    }

    // ------------------------------------------------------------------ main loop

    @SuppressWarnings("try")
    public static void main(final String[] args) throws Exception
    {
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));
        final String egressHost = env("EGRESS_HOST", "localhost");
        final String secret = env("CONTROL_SECRET", ClusterAuthenticator.DEFAULT_SECRET);
        final int httpPort = Integer.parseInt(env("ADMIN_HTTP_PORT", "8090"));
        final String clusterDirs = env("CLUSTER_DIRS", "");

        final ControlPlane control = new ControlPlane();
        final ShutdownFlag running = ShutdownFlag.register();
        final String ingress = ClusterConfig.ingressEndpoints(hosts, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        try (MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
             AeronCluster commands = connect(driver, ingress, egressHost, secret, control.commandListener);
             AeronCluster viewSession = connect(driver, ingress, egressHost, secret, control.viewListener);
             AdminWeb ignored = new AdminWeb(httpPort, control, new Users(System.getenv("CONTROL_USERS"))))
        {
            control.view.leader(viewSession.leaderMemberId(), -1);
            control.subscribe(viewSession);
            control.registerMBeans(clusterDirs);
            System.out.printf("control plane up: MBeans under aeronfire:type=*, admin page http://localhost:%d%n", httpPort);
            control.run(commands, viewSession, running);
        }
        running.closed();
    }

    private static AeronCluster connect(
        final MediaDriver driver, final String ingress, final String egressHost, final String secret,
        final EgressListener listener)
    {
        return AeronCluster.connect(new AeronCluster.Context()
            .egressListener(listener)
            .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
            .ingressChannel("aeron:udp")
            .ingressEndpoints(ingress)
            .aeronDirectoryName(driver.aeronDirectoryName())
            .credentialsSupplier(ClusterAuthenticator.controlCredentialsSupplier(secret)));
    }

    private void subscribe(final AeronCluster session)
    {
        final SubscribeEncoder e = new SubscribeEncoder()
            .wrapAndApplyHeader(sendBuffer, 0, new MessageHeaderEncoder())
            .sendStateDump(BooleanType.TRUE);
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    private void run(final AeronCluster commands, final AeronCluster viewSession, final ShutdownFlag running)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        long lastKeepAliveMs = 0;

        while (running.isRunning() && !commands.isClosed() && !viewSession.isClosed())
        {
            int work = commands.pollEgress() + viewSession.pollEgress();

            // One command at a time, so its replies are unambiguous.
            if (inFlight.isEmpty())
            {
                final Command next = queue.poll();
                if (null != next)
                {
                    sendBuffer.putBytes(0, next.bytes());
                    inFlight.add(next);
                    offer(commands, next.bytes().length);
                    work++;
                }
            }

            final long nowMs = System.currentTimeMillis();
            if (nowMs - lastKeepAliveMs >= 1_000)
            {
                commands.sendKeepAlive();
                viewSession.sendKeepAlive();
                lastKeepAliveMs = nowMs;
            }
            idle.idle(work);
        }
    }

    private void offer(final AeronCluster session, final int length)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        while (session.offer(sendBuffer, 0, length) < 0)
        {
            if (session.isClosed())
            {
                throw new IllegalStateException("cluster session closed");
            }
            idle.idle(session.pollEgress());
        }
    }

    // ------------------------------------------------------------------ MBeans

    private void registerMBeans(final String clusterDirs) throws Exception
    {
        final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        final Mbeans beans = new Mbeans(this, clusterDirs);
        server.registerMBean(new StandardMBean(beans, ControlMBeans.CreditMBean.class), new ObjectName("aeronfire:type=Credit"));
        server.registerMBean(
            new StandardMBean(beans, ControlMBeans.InventoryMBean.class), new ObjectName("aeronfire:type=Inventory"));
        server.registerMBean(
            new StandardMBean(beans, ControlMBeans.AlgoControlsMBean.class), new ObjectName("aeronfire:type=AlgoControls"));
        server.registerMBean(
            new StandardMBean(beans, ControlMBeans.EntitlementsMBean.class), new ObjectName("aeronfire:type=Entitlements"));
        server.registerMBean(
            new StandardMBean(beans, ControlMBeans.ApprovalsMBean.class), new ObjectName("aeronfire:type=Approvals"));
        server.registerMBean(
            new StandardMBean(beans, ControlMBeans.EligibilityMBean.class), new ObjectName("aeronfire:type=Eligibility"));
        server.registerMBean(new StandardMBean(beans, ControlMBeans.ClusterMBean.class), new ObjectName("aeronfire:type=Cluster"));
    }

    static long mmToUnits(final double mm)
    {
        return Math.round(mm * 1_000_000.0);
    }

    static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }

    static File[] dirs(final String csv)
    {
        return csv.isBlank() ? new File[0] :
            java.util.Arrays.stream(csv.split(",")).map(String::trim).map(File::new).toArray(File[]::new);
    }

    static boolean isApproval(final byte[] command)
    {
        final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(new UnsafeBuffer(command), 0);
        return h.templateId() == ApprovalDecoder.TEMPLATE_ID;
    }
}
