package io.aeronfire.monitor;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.domain.Tier;
import io.aeronfire.sbe.AlgoControlsStatusDecoder;
import io.aeronfire.sbe.BooleanType;
import io.aeronfire.sbe.ClientEligibilityStatusDecoder;
import io.aeronfire.sbe.InventoryStatusDecoder;
import io.aeronfire.sbe.PendingChangeStatusDecoder;
import io.aeronfire.sbe.PendingState;
import io.aeronfire.sbe.CreditResetAckDecoder;
import io.aeronfire.sbe.CreditStatusDecoder;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.PositionStatusDecoder;
import io.aeronfire.sbe.RiskUpdateDecoder;
import io.aeronfire.sbe.QuoteDecoder;
import io.aeronfire.sbe.RejectDecoder;
import io.aeronfire.sbe.RfqRequestDecoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.StateDumpEndDecoder;
import io.aeronfire.sbe.SubscribeEncoder;
import io.aeronfire.sbe.TradeDecoder;
import io.aeronfire.util.ShutdownFlag;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Blotter process: subscribes to the cluster's drop copy and shows it in a browser, a terminal, or both.
 * <p>
 * Environment: {@code CLUSTER_HOSTS}, {@code PORT_BASE}, {@code EGRESS_HOST} as for the other clients,
 * {@code UI} ({@code web}, {@code tui} or {@code both}, default {@code web}) and {@code HTTP_PORT} (8080).
 * The monitor's cluster session survives a leader change, so the views keep running through failover.
 */
public final class Monitor implements EgressListener
{
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final QuoteDecoder quote = new QuoteDecoder();
    private final TradeDecoder trade = new TradeDecoder();
    private final RejectDecoder reject = new RejectDecoder();
    private final CreditStatusDecoder creditStatus = new CreditStatusDecoder();
    private final PositionStatusDecoder positionStatus = new PositionStatusDecoder();
    private final InventoryStatusDecoder inventoryStatus = new InventoryStatusDecoder();
    private final RiskUpdateDecoder riskUpdate = new RiskUpdateDecoder();
    private final AlgoControlsStatusDecoder algoStatus = new AlgoControlsStatusDecoder();
    private final ClientEligibilityStatusDecoder eligibilityStatus = new ClientEligibilityStatusDecoder();
    private final PendingChangeStatusDecoder pendingStatus = new PendingChangeStatusDecoder();
    private final MonitorModel model = new MonitorModel();
    private volatile String json = "{}";

    @Override
    public void onMessage(
        final long clusterSessionId,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        headerDecoder.wrap(buffer, offset);
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int blockLength = headerDecoder.blockLength();
        final int version = headerDecoder.version();
        final long nowMs = System.currentTimeMillis();

        switch (headerDecoder.templateId())
        {
            case QuoteDecoder.TEMPLATE_ID ->
            {
                quote.wrap(buffer, bodyOffset, blockLength, version);
                model.onQuote(nowMs, quote.clientId(), quote.instrumentId(), quote.side() == Side.BUY,
                    quote.notional(), quote.price(), quote.spreadBps());
            }
            case TradeDecoder.TEMPLATE_ID ->
            {
                trade.wrap(buffer, bodyOffset, blockLength, version);
                model.onTrade(nowMs, trade.clientId(), trade.instrumentId(), trade.side() == Side.BUY,
                    trade.notional(), trade.price(), trade.clientUtilised());
            }
            case RejectDecoder.TEMPLATE_ID ->
            {
                reject.wrap(buffer, bodyOffset, blockLength, version);
                model.onReject(nowMs, reject.reason(), reject.rejectedTemplateId() == RfqRequestDecoder.TEMPLATE_ID);
            }
            case CreditStatusDecoder.TEMPLATE_ID ->
            {
                creditStatus.wrap(buffer, bodyOffset, blockLength, version);
                model.onCredit(creditStatus.clientId(), Tier.fromCode(creditStatus.tierRaw()),
                    creditStatus.creditLimit(), creditStatus.utilised());
            }
            case PositionStatusDecoder.TEMPLATE_ID ->
            {
                positionStatus.wrap(buffer, bodyOffset, blockLength, version);
                model.onPosition(positionStatus.instrumentId(), positionStatus.netNotional());
            }
            case RiskUpdateDecoder.TEMPLATE_ID ->
            {
                riskUpdate.wrap(buffer, bodyOffset, blockLength, version);
                model.onRisk(riskUpdate.instrumentId(), riskUpdate.netNotional(), riskUpdate.mark(), riskUpdate.cs01(),
                    riskUpdate.dv01());
            }
            case InventoryStatusDecoder.TEMPLATE_ID ->
            {
                inventoryStatus.wrap(buffer, bodyOffset, blockLength, version);
                model.onInventory(inventoryStatus.instrumentId(), inventoryStatus.netNotional(),
                    inventoryStatus.haltedRaw() == BooleanType.TRUE.value());
            }
            case AlgoControlsStatusDecoder.TEMPLATE_ID ->
            {
                algoStatus.wrap(buffer, bodyOffset, blockLength, version);
                model.killSwitch = algoStatus.killSwitchRaw() == BooleanType.TRUE.value();
            }
            case ClientEligibilityStatusDecoder.TEMPLATE_ID ->
            {
                eligibilityStatus.wrap(buffer, bodyOffset, blockLength, version);
                model.onEligibility(eligibilityStatus.clientId(),
                    eligibilityStatus.kycValidRaw() == BooleanType.TRUE.value(),
                    eligibilityStatus.sanctionedRaw() == BooleanType.TRUE.value());
            }
            case PendingChangeStatusDecoder.TEMPLATE_ID ->
            {
                pendingStatus.wrap(buffer, bodyOffset, blockLength, version);
                model.onPending(pendingStatus.pendingId(), pendingStatus.state() == PendingState.PENDING);
            }
            case StateDumpEndDecoder.TEMPLATE_ID -> model.stateDumpComplete = true;
            case CreditResetAckDecoder.TEMPLATE_ID -> model.onCreditReset();
            default ->
            {
            }
        }
    }

    @Override
    public void onNewLeader(
        final long clusterSessionId, final long leadershipTermId, final int leaderMemberId, final String ingressEndpoints)
    {
        if (model.leaderMemberId >= 0 && model.leaderMemberId != leaderMemberId)
        {
            model.leaderChanges++;
        }
        model.leaderMemberId = leaderMemberId;
        model.leadershipTermId = leadershipTermId;
    }

    @SuppressWarnings("try")
    public static void main(final String[] args) throws Exception
    {
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));
        final String egressHost = env("EGRESS_HOST", "localhost");
        final String ui = env("UI", "web");
        final int httpPort = Integer.parseInt(env("HTTP_PORT", "8080"));
        final boolean web = ui.equals("web") || ui.equals("both");
        final boolean tui = ui.equals("tui") || ui.equals("both");

        final Monitor monitor = new Monitor();
        final ShutdownFlag running = ShutdownFlag.register();

        try (MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
             AeronCluster cluster = AeronCluster.connect(new AeronCluster.Context()
                .egressListener(monitor)
                .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ClusterConfig.ingressEndpoints(hosts, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                .aeronDirectoryName(driver.aeronDirectoryName()));
             WebView webView = web ? new WebView(httpPort, () -> monitor.json) : null)
        {
            monitor.model.leaderMemberId = cluster.leaderMemberId();
            monitor.subscribe(cluster);
            if (web)
            {
                System.out.printf("blotter at http://localhost:%d%n", httpPort);
            }
            monitor.run(cluster, running, tui ? new TerminalView(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), false, java.nio.charset.StandardCharsets.UTF_8)) : null);
        }
        running.closed();
    }

    private void subscribe(final AeronCluster cluster)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
        final SubscribeEncoder encoder = new SubscribeEncoder()
            .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .sendStateDump(BooleanType.TRUE);
        final IdleStrategy idle = new BackoffIdleStrategy();
        while (cluster.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength()) < 0)
        {
            idle.idle(cluster.pollEgress());
        }
    }

    private void run(final AeronCluster cluster, final ShutdownFlag running, final TerminalView terminal)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        long lastKeepAliveMs = 0;
        long lastJsonMs = 0;
        long lastRenderMs = 0;

        while (running.isRunning() && !cluster.isClosed())
        {
            final int work = cluster.pollEgress();
            final long nowMs = System.currentTimeMillis();

            if (nowMs - lastKeepAliveMs >= 1_000)
            {
                cluster.sendKeepAlive();
                lastKeepAliveMs = nowMs;
            }
            if (nowMs - lastJsonMs >= 250)
            {
                json = model.toJson(nowMs);
                lastJsonMs = nowMs;
            }
            if (null != terminal && nowMs - lastRenderMs >= 500)
            {
                terminal.render(model, nowMs);
                lastRenderMs = nowMs;
            }

            idle.idle(work);
        }
    }

    private static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }
}
