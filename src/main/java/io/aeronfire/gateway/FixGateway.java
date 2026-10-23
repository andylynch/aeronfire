package io.aeronfire.gateway;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.artio.ArtioRuntime;
import io.aeronfire.util.ShutdownFlag;
import io.aeronfire.domain.Instruments;
import io.aeronfire.fix.ExecType;
import io.aeronfire.fix.FixDictionaryImpl;
import io.aeronfire.fix.OrdStatus;
import io.aeronfire.fix.QuoteRequestRejectReason;
import io.aeronfire.fix.QuoteRespType;
import io.aeronfire.fix.builder.ExecutionReportEncoder;
import io.aeronfire.fix.builder.QuoteEncoder;
import io.aeronfire.fix.builder.QuoteRequestRejectEncoder;
import io.aeronfire.fix.decoder.QuoteRequestDecoder;
import io.aeronfire.fix.decoder.QuoteResponseDecoder;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.QuoteAcceptDecoder;
import io.aeronfire.sbe.QuoteAcceptEncoder;
import io.aeronfire.sbe.QuoteDecoder;
import io.aeronfire.sbe.RejectDecoder;
import io.aeronfire.sbe.RejectReason;
import io.aeronfire.sbe.RfqRequestDecoder;
import io.aeronfire.sbe.RfqRequestEncoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.TradeDecoder;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.AcquiringSessionExistsHandler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;
import uk.co.real_logic.artio.builder.Encoder;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * FIX gateway: the dealer's side of a Tradeweb-style RFQ venue.
 * <p>
 * Venue sessions connect over FIX 4.4 (Artio acceptor). The flow, simplified from a real venue:
 * <pre>
 *   venue  QuoteRequest (R)  ─▶ gateway ─▶ cluster RfqRequest
 *   venue ◀ Quote (S)          gateway ◀─ cluster Quote
 *   venue  QuoteResponse (AJ) ─▶ gateway ─▶ cluster QuoteAccept   (HIT_LIFT; PASS just closes the RFQ)
 *   venue ◀ ExecutionReport (8) gateway ◀─ cluster Trade or Reject
 *   venue ◀ QuoteRequestReject (AG) on an RFQ the cluster rejects (credit, tier, instrument)
 * </pre>
 * The gateway is one cluster client session carrying every venue's flow, so it keeps the FIX-to-cluster
 * id mapping. It runs single-threaded: Artio library polling and cluster egress on the same duty cycle.
 * It is the edge, so it allocates a small object per RFQ for clarity; pooling these is on the roadmap.
 */
public final class FixGateway implements EgressListener
{
    public static final String GATEWAY_COMP_ID = "AERONFIRE";

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final RfqRequestEncoder rfqEncoder = new RfqRequestEncoder();
    private final QuoteAcceptEncoder acceptEncoder = new QuoteAcceptEncoder();
    private final QuoteDecoder clusterQuote = new QuoteDecoder();
    private final TradeDecoder clusterTrade = new TradeDecoder();
    private final RejectDecoder clusterReject = new RejectDecoder();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

    private final QuoteRequestDecoder fixQuoteRequest = new QuoteRequestDecoder();
    private final QuoteResponseDecoder fixQuoteResponse = new QuoteResponseDecoder();
    private final QuoteEncoder fixQuote = new QuoteEncoder();
    private final ExecutionReportEncoder fixExecutionReport = new ExecutionReportEncoder();
    private final QuoteRequestRejectEncoder fixQuoteRequestReject = new QuoteRequestRejectEncoder();
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    private final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();

    private final Long2ObjectHashMap<PendingRfq> byRfqId = new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<PendingRfq> byQuoteId = new Long2ObjectHashMap<>();
    private final IdleStrategy sendIdle = new BackoffIdleStrategy();
    private final GatewayStats stats = new GatewayStats();

    private static final long OFFER_TIMEOUT_MS = 2_000;

    private final ArrayDeque<PendingRfq> pool = new ArrayDeque<>();
    private final ArrayList<PendingRfq> retired = new ArrayList<>();
    private final long quoteWaitMs = Long.parseLong(env("QUOTE_WAIT_MS", "5000"));
    private final long acceptWaitMs = Long.parseLong(env("ACCEPT_WAIT_MS", "5000"));
    private final long venueIdleMs = Long.parseLong(env("VENUE_IDLE_MS", "30000"));

    private AeronCluster cluster;
    private Supplier<AeronCluster> reconnect;
    // Ids restart from the clock, not 1, so a restarted gateway can't reuse ids the cluster has already seen.
    private long nextRfqId = System.currentTimeMillis() * 1_000;
    private long nextRejectExecId = 1;

    /**
     * One live RFQ, from QuoteRequest until trade, reject or pass. Pooled: the gateway allocates these per
     * RFQ otherwise, and they die young. Recycled after the handler that retired them has finished with them.
     */
    static final class PendingRfq
    {
        long rfqId;
        Session session;
        String quoteReqId;
        String account;
        String securityId;
        int instrumentId;
        char side;
        long notional;
        long quoteId;
        String venueTxnId;
        long lastActivityMs;
        int acceptSends;
        int rfqSends;

        PendingRfq init(
            final long rfqId,
            final Session session,
            final String quoteReqId,
            final String account,
            final String securityId,
            final int instrumentId,
            final char side,
            final long notional,
            final long nowMs)
        {
            this.rfqId = rfqId;
            this.session = session;
            this.quoteReqId = quoteReqId;
            this.account = account;
            this.securityId = securityId;
            this.instrumentId = instrumentId;
            this.side = side;
            this.notional = notional;
            this.quoteId = -1;
            this.venueTxnId = null;
            this.lastActivityMs = nowMs;
            this.acceptSends = 0;
            this.rfqSends = 0;
            return this;
        }

        void clear()
        {
            session = null;
            quoteReqId = null;
            account = null;
            securityId = null;
            venueTxnId = null;
        }
    }

    public static void main(final String[] args)
    {
        final int fixPort = Integer.parseInt(env("FIX_PORT", "9880"));
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));
        final String egressHost = env("EGRESS_HOST", "localhost");
        final File workDir = new File(env("WORK_DIR", "build"));

        final FixGateway gateway = new FixGateway();
        final ShutdownFlag running = ShutdownFlag.register();

        try (ArtioRuntime artio = ArtioRuntime.launch("gateway", workDir, (configuration) -> configuration
                .bindTo("0.0.0.0", fixPort)
                .acceptorfixDictionary(FixDictionaryImpl.class)
                .authenticationStrategy(AuthenticationStrategy.of(
                    MessageValidationStrategy.targetCompId(GATEWAY_COMP_ID))));
             FixLibrary library = artio.connectLibrary(new LibraryConfiguration()
                .sessionAcquireHandler((session, info) -> gateway.onVenueSession(session))
                .sessionExistsHandler(new AcquiringSessionExistsHandler(false))))
        {
            gateway.reconnect = () -> AeronCluster.connect(new AeronCluster.Context()
                .egressListener(gateway)
                .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ClusterConfig.ingressEndpoints(hosts, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                .aeronDirectoryName(artio.aeronDirectoryName()));
            gateway.cluster = gateway.reconnect.get();
            System.out.printf("gateway up: FIX %s on port %d, cluster leader member %d%n",
                GATEWAY_COMP_ID, fixPort, gateway.cluster.leaderMemberId());
            try
            {
                gateway.run(library, running);
            }
            finally
            {
                gateway.cluster.close();
            }
        }
        running.closed();
    }

    private void run(final FixLibrary library, final ShutdownFlag running)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        long lastKeepAliveMs = System.currentTimeMillis();
        long lastStatsMs = lastKeepAliveMs;
        long lastSweepMs = lastKeepAliveMs;

        while (running.isRunning())
        {
            if (cluster.isClosed())
            {
                reconnectToCluster(running);
                continue;
            }
            final int work = library.poll(10) + cluster.pollEgress();

            final long nowMs = System.currentTimeMillis();
            if (nowMs - lastSweepMs >= 500)
            {
                sweep(nowMs);
                lastSweepMs = nowMs;
            }
            if (nowMs - lastKeepAliveMs >= 1_000)
            {
                cluster.sendKeepAlive();
                lastKeepAliveMs = nowMs;
            }
            if (nowMs - lastStatsMs >= 5_000)
            {
                stats.print(byRfqId.size());
                lastStatsMs = nowMs;
            }

            idle.idle(work);
        }
    }

    /**
     * The cluster client session closed (all members unreachable for too long). Keep the venue sessions up,
     * keep trying, and let the sweep answer RFQs that were in flight.
     */
    private void reconnectToCluster(final ShutdownFlag running)
    {
        System.out.println("cluster session closed, reconnecting");
        while (running.isRunning())
        {
            try
            {
                cluster.close();
                cluster = reconnect.get();
                System.out.printf("reconnected, leader member %d%n", cluster.leaderMemberId());
                stats.reconnects++;
                return;
            }
            catch (final RuntimeException ex)
            {
                System.out.println("reconnect failed: " + ex.getMessage());
                try
                {
                    Thread.sleep(1_000);
                }
                catch (final InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Answer RFQs the cluster hasn't answered. During a leader change replies can be lost, and the venue must
     * not wait forever.
     * <ul>
     *   <li>No quote yet: resend the RFQ once (the cluster dedupes on RFQ id), then reject it with a timeout.</li>
     *   <li>Accepted, no trade yet: resend the accept (the cluster replays the original trade if it already
     *       filled), then give up with an UNKNOWN_OUTCOME execution report to be checked against the blotter.</li>
     *   <li>Quoted, venue hasn't responded: drop it silently once stale.</li>
     * </ul>
     */
    private void sweep(final long nowMs)
    {
        if (byRfqId.isEmpty())
        {
            return;
        }
        final ArrayList<PendingRfq> due = new ArrayList<>();
        for (final PendingRfq rfq : byRfqId.values())
        {
            final long idle = nowMs - rfq.lastActivityMs;
            if ((rfq.quoteId < 0 && idle > quoteWaitMs) ||
                (rfq.quoteId >= 0 && rfq.acceptSends > 0 && idle > acceptWaitMs) ||
                (rfq.quoteId >= 0 && rfq.acceptSends == 0 && idle > venueIdleMs))
            {
                due.add(rfq);
            }
        }
        for (final PendingRfq rfq : due)
        {
            if (rfq.quoteId < 0 && rfq.rfqSends < 2)
            {
                // The cluster returns the existing quote for a resent RFQ id, so this can't double-quote.
                stats.rfqResends++;
                sendRfq(rfq, nowMs);
            }
            else if (rfq.quoteId < 0)
            {
                stats.timeouts++;
                forget(rfq);
                fixQuoteRequestReject.reset();
                fixQuoteRequestReject.quoteReqID(rfq.quoteReqId)
                    .quoteRequestRejectReason(QuoteRequestRejectReason.OTHER).text("TIMEOUT");
                send(rfq.session, fixQuoteRequestReject);
            }
            else if (rfq.acceptSends == 0)
            {
                stats.expired++;
                forget(rfq);
            }
            else if (rfq.acceptSends < 3)
            {
                stats.resends++;
                sendAccept(rfq, nowMs);
            }
            else
            {
                stats.unknownOutcomes++;
                forget(rfq);
                fixExecutionReport.reset();
                fixExecutionReport
                    .orderID(Long.toString(rfq.quoteId))
                    .execID("R" + nextRejectExecId++)
                    .execType(ExecType.REJECTED)
                    .ordStatus(OrdStatus.REJECTED)
                    .quoteReqID(rfq.quoteReqId)
                    .quoteID(Long.toString(rfq.quoteId))
                    .account(rfq.account)
                    .side(rfq.side)
                    .text("UNKNOWN_OUTCOME: check the blotter before retrying");
                fixExecutionReport.instrument().securityID(rfq.securityId).securityIDSource("8");
                send(rfq.session, fixExecutionReport);
            }
        }
        flushRetired();
    }

    private void sendAccept(final PendingRfq rfq, final long nowMs)
    {
        rfq.acceptSends++;
        rfq.lastActivityMs = nowMs;
        acceptEncoder.wrapAndApplyHeader(ingressBuffer, 0, headerEncoder)
            .rfqId(rfq.rfqId)
            .quoteId(rfq.quoteId)
            .venueTxnId(rfq.venueTxnId == null ? "" : rfq.venueTxnId);
        offerToCluster(MessageHeaderEncoder.ENCODED_LENGTH + acceptEncoder.encodedLength());
    }

    private PendingRfq newRfq()
    {
        final PendingRfq pooled = pool.pollFirst();
        return null != pooled ? pooled : new PendingRfq();
    }

    /**
     * Return retired RFQs to the pool. Deferred until the handler that retired them is done reading them.
     */
    private void flushRetired()
    {
        for (int i = 0, n = retired.size(); i < n; i++)
        {
            final PendingRfq rfq = retired.get(i);
            rfq.clear();
            pool.addFirst(rfq);
        }
        retired.clear();
    }

    private SessionHandler onVenueSession(final Session session)
    {
        System.out.printf("venue session %s logged in%n", session.compositeKey());
        return new SessionHandler()
        {
            @Override
            public Action onMessage(
                final DirectBuffer buffer,
                final int offset,
                final int length,
                final int libraryId,
                final Session session,
                final int sequenceIndex,
                final long messageType,
                final long timestampInNs,
                final long position,
                final OnMessageInfo messageInfo)
            {
                asciiBuffer.wrap(buffer, offset, length);
                if (messageType == QuoteRequestDecoder.MESSAGE_TYPE)
                {
                    fixQuoteRequest.reset();
                    fixQuoteRequest.decode(asciiBuffer, 0, length);
                    onQuoteRequest(session);
                }
                else if (messageType == QuoteResponseDecoder.MESSAGE_TYPE)
                {
                    fixQuoteResponse.reset();
                    fixQuoteResponse.decode(asciiBuffer, 0, length);
                    onQuoteResponse();
                }
                return Action.CONTINUE;
            }

            @Override
            public void onTimeout(final int libraryId, final Session session)
            {
            }

            @Override
            public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
            {
            }

            @Override
            public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
            {
                System.out.printf("venue session %s disconnected: %s%n", session.compositeKey(), reason);
                return Action.CONTINUE;
            }

            @Override
            public void onSessionStart(final Session session)
            {
            }
        };
    }

    // ---------------------------------------------------------------- venue to cluster

    private void onQuoteRequest(final Session session)
    {
        stats.rfqsIn++;
        final String account = fixQuoteRequest.accountAsString();
        final String securityId = fixQuoteRequest.securityIDAsString();
        final char side = fixQuoteRequest.side();
        final long rfqId = nextRfqId++;

        final PendingRfq rfq = newRfq().init(
            rfqId,
            session,
            fixQuoteRequest.quoteReqIDAsString(),
            account,
            securityId,
            parseIntOr(securityId, -1),
            side,
            wholeUnits(fixQuoteRequest.orderQty()),
            System.currentTimeMillis());
        byRfqId.put(rfqId, rfq);
        sendRfq(rfq, rfq.lastActivityMs);
    }

    private void sendRfq(final PendingRfq rfq, final long nowMs)
    {
        rfq.rfqSends++;
        rfq.lastActivityMs = nowMs;
        rfqEncoder.wrapAndApplyHeader(ingressBuffer, 0, headerEncoder)
            .rfqId(rfq.rfqId)
            .clientId(parseIntOr(rfq.account, -1))
            .instrumentId(rfq.instrumentId)
            .side(rfq.side == '1' ? Side.BUY : Side.SELL)
            .notional(rfq.notional);
        offerToCluster(MessageHeaderEncoder.ENCODED_LENGTH + rfqEncoder.encodedLength());
    }

    private void onQuoteResponse()
    {
        final long quoteId = parseLongOr(fixQuoteResponse.quoteIDAsString(), -1);
        final PendingRfq rfq = byQuoteId.get(quoteId);
        if (null == rfq)
        {
            stats.unknownResponses++;
            return;
        }

        if (fixQuoteResponse.quoteRespType() == QuoteRespType.HIT_LIFT.representation())
        {
            stats.hits++;
            // The venue's trade id (FIX TrdMatchID) is the MiFIR TVTIC; carry it onto the trade.
            rfq.venueTxnId = fixQuoteResponse.trdMatchIDAsString();
            sendAccept(rfq, System.currentTimeMillis());
        }
        else
        {
            stats.passes++;
            forget(rfq);
            flushRetired();
        }
    }

    /**
     * Offer to the cluster, polling egress while waiting: the client only learns about a new leader by
     * polling, so a bare retry loop would spin forever through a failover. Gives up after a couple of seconds;
     * the sweep answers whatever was left waiting.
     */
    private boolean offerToCluster(final int length)
    {
        sendIdle.reset();
        final long deadline = System.currentTimeMillis() + OFFER_TIMEOUT_MS;
        while (cluster.offer(ingressBuffer, 0, length) < 0)
        {
            if (cluster.isClosed() || System.currentTimeMillis() > deadline)
            {
                stats.sendFailures++;
                return false;
            }
            sendIdle.idle(cluster.pollEgress());
        }
        return true;
    }

    // ---------------------------------------------------------------- cluster to venue

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

        switch (headerDecoder.templateId())
        {
            case QuoteDecoder.TEMPLATE_ID ->
            {
                clusterQuote.wrap(buffer, bodyOffset, blockLength, version);
                onClusterQuote();
            }
            case TradeDecoder.TEMPLATE_ID ->
            {
                clusterTrade.wrap(buffer, bodyOffset, blockLength, version);
                onClusterTrade();
            }
            case RejectDecoder.TEMPLATE_ID ->
            {
                clusterReject.wrap(buffer, bodyOffset, blockLength, version);
                onClusterReject();
            }
            default ->
            {
            }
        }
        flushRetired();
    }

    private void onClusterQuote()
    {
        final PendingRfq rfq = byRfqId.get(clusterQuote.rfqId());
        if (null == rfq)
        {
            return;
        }

        stats.quotesOut++;
        rfq.quoteId = clusterQuote.quoteId();
        rfq.lastActivityMs = System.currentTimeMillis();
        byQuoteId.put(rfq.quoteId, rfq);

        fixQuote.reset();
        fixQuote
            .quoteReqID(rfq.quoteReqId)
            .quoteID(Long.toString(rfq.quoteId))
            .account(rfq.account)
            .side(rfq.side)
            .orderQty(rfq.notional, 0)
            .price(clusterQuote.price(), 6)
            .spread(clusterQuote.spreadBps(), 0);
        final int timestampLength = timestampEncoder.encode(clusterQuote.validUntil());
        fixQuote.validUntilTime(timestampEncoder.buffer(), timestampLength);
        fixQuote.instrument()
            .securityID(rfq.securityId)
            .securityIDSource("8")
            .symbol(Instruments.name(rfq.instrumentId));
        send(rfq.session, fixQuote);
    }

    private void onClusterTrade()
    {
        final PendingRfq rfq = byRfqId.get(clusterTrade.rfqId());
        if (null == rfq)
        {
            return;
        }

        stats.trades++;
        forget(rfq);

        fixExecutionReport.reset();
        fixExecutionReport
            .orderID(Long.toString(clusterTrade.quoteId()))
            .execID("T" + clusterTrade.tradeId())
            .execType(ExecType.TRADE)
            .ordStatus(OrdStatus.FILLED)
            .quoteReqID(rfq.quoteReqId)
            .quoteID(Long.toString(clusterTrade.quoteId()))
            .trdMatchID(rfq.venueTxnId == null ? "" : rfq.venueTxnId)
            .account(rfq.account)
            .side(rfq.side)
            .lastQty(clusterTrade.notional(), 0)
            .lastPx(clusterTrade.price(), 6);
        fixExecutionReport.instrument().securityID(rfq.securityId).securityIDSource("8");
        send(rfq.session, fixExecutionReport);
    }

    private void onClusterReject()
    {
        final RejectReason reason = clusterReject.reason();
        final long correlationId = clusterReject.correlationId();
        stats.rejects++;

        if (clusterReject.rejectedTemplateId() == RfqRequestDecoder.TEMPLATE_ID)
        {
            final PendingRfq rfq = byRfqId.get(correlationId);
            if (null == rfq)
            {
                return;
            }
            forget(rfq);

            fixQuoteRequestReject.reset();
            fixQuoteRequestReject
                .quoteReqID(rfq.quoteReqId)
                .quoteRequestRejectReason(toFixReason(reason))
                .text(reason.name());
            send(rfq.session, fixQuoteRequestReject);
        }
        else if (clusterReject.rejectedTemplateId() == QuoteAcceptDecoder.TEMPLATE_ID)
        {
            final PendingRfq rfq = byQuoteId.get(correlationId);
            if (null == rfq)
            {
                return;
            }
            forget(rfq);

            fixExecutionReport.reset();
            fixExecutionReport
                .orderID(Long.toString(correlationId))
                .execID("R" + nextRejectExecId++)
                .execType(ExecType.REJECTED)
                .ordStatus(OrdStatus.REJECTED)
                .quoteReqID(rfq.quoteReqId)
                .quoteID(Long.toString(correlationId))
                .account(rfq.account)
                .side(rfq.side)
                .text(reason.name());
            fixExecutionReport.instrument().securityID(rfq.securityId).securityIDSource("8");
            send(rfq.session, fixExecutionReport);
        }
    }

    private static QuoteRequestRejectReason toFixReason(final RejectReason reason)
    {
        return switch (reason)
        {
            case UNKNOWN_INSTRUMENT -> QuoteRequestRejectReason.UNKNOWN_SYMBOL;
            case CREDIT_LIMIT -> QuoteRequestRejectReason.INSUFFICIENT_CREDIT;
            case ABOVE_TIER_MAX -> QuoteRequestRejectReason.EXCEEDS_LIMIT;
            case UNKNOWN_CLIENT -> QuoteRequestRejectReason.NOT_AUTHORIZED;
            default -> QuoteRequestRejectReason.OTHER;
        };
    }

    private void forget(final PendingRfq rfq)
    {
        byRfqId.remove(rfq.rfqId);
        if (rfq.quoteId >= 0)
        {
            byQuoteId.remove(rfq.quoteId);
        }
        retired.add(rfq);
    }

    private void send(final Session session, final Encoder encoder)
    {
        sendIdle.reset();
        for (int attempt = 0; attempt < 1_000; attempt++)
        {
            if (session.trySend(encoder) >= 0)
            {
                return;
            }
            sendIdle.idle();
        }
        stats.sendFailures++;
    }

    // ---------------------------------------------------------------- helpers

    static long wholeUnits(final DecimalFloat value)
    {
        long units = value.value();
        for (int scale = value.scale(); scale > 0; scale--)
        {
            units /= 10;
        }
        return units;
    }

    static int parseIntOr(final String value, final int fallback)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (final NumberFormatException ex)
        {
            return fallback;
        }
    }

    static long parseLongOr(final String value, final long fallback)
    {
        try
        {
            return Long.parseLong(value);
        }
        catch (final NumberFormatException ex)
        {
            return fallback;
        }
    }

    static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }

    /**
     * Running counters, printed every few seconds.
     */
    static final class GatewayStats
    {
        long rfqsIn;
        long quotesOut;
        long hits;
        long passes;
        long trades;
        long rejects;
        long unknownResponses;
        long sendFailures;
        long timeouts;
        long expired;
        long resends;
        long rfqResends;
        long unknownOutcomes;
        long reconnects;

        void print(final int liveRfqs)
        {
            System.out.printf(
                "gateway: rfqs %d  quotes %d  hits %d  passes %d  trades %d  rejects %d  live %d%s%s%n",
                rfqsIn, quotesOut, hits, passes, trades, rejects, liveRfqs,
                timeouts + expired + resends + rfqResends + unknownOutcomes + reconnects > 0
                    ? "  timeouts " + timeouts + " expired " + expired + " resends " + resends + " rfq-resends " + rfqResends + " unknown " +
                    unknownOutcomes + " reconnects " + reconnects : "",
                sendFailures > 0 ? "  SEND FAILURES " + sendFailures : "");
        }
    }
}
