package io.aeronfire.gateway;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.artio.ArtioRuntime;
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
import org.agrona.concurrent.SigInt;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private AeronCluster cluster;
    private long nextRfqId = 1;
    private long nextRejectExecId = 1;

    /**
     * One live RFQ, from QuoteRequest until trade, reject or pass.
     */
    static final class PendingRfq
    {
        final long rfqId;
        final Session session;
        final String quoteReqId;
        final String account;
        final String securityId;
        final int instrumentId;
        final char side;
        final long notional;
        long quoteId = -1;

        PendingRfq(
            final long rfqId,
            final Session session,
            final String quoteReqId,
            final String account,
            final String securityId,
            final int instrumentId,
            final char side,
            final long notional)
        {
            this.rfqId = rfqId;
            this.session = session;
            this.quoteReqId = quoteReqId;
            this.account = account;
            this.securityId = securityId;
            this.instrumentId = instrumentId;
            this.side = side;
            this.notional = notional;
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
        final AtomicBoolean running = new AtomicBoolean(true);
        SigInt.register(() -> running.set(false));

        try (ArtioRuntime artio = ArtioRuntime.launch("gateway", workDir, (configuration) -> configuration
                .bindTo("0.0.0.0", fixPort)
                .acceptorfixDictionary(FixDictionaryImpl.class)
                .authenticationStrategy(AuthenticationStrategy.of(
                    MessageValidationStrategy.targetCompId(GATEWAY_COMP_ID))));
             AeronCluster cluster = AeronCluster.connect(new AeronCluster.Context()
                .egressListener(gateway)
                .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ClusterConfig.ingressEndpoints(hosts, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                .aeronDirectoryName(artio.aeronDirectoryName()));
             FixLibrary library = artio.connectLibrary(new LibraryConfiguration()
                .sessionAcquireHandler((session, info) -> gateway.onVenueSession(session))
                .sessionExistsHandler(new AcquiringSessionExistsHandler(false))))
        {
            gateway.cluster = cluster;
            System.out.printf("gateway up: FIX %s on port %d, cluster leader member %d%n",
                GATEWAY_COMP_ID, fixPort, cluster.leaderMemberId());
            gateway.run(library, running);
        }
    }

    private void run(final FixLibrary library, final AtomicBoolean running)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        long lastKeepAliveMs = System.currentTimeMillis();
        long lastStatsMs = lastKeepAliveMs;

        while (running.get())
        {
            final int work = library.poll(10) + cluster.pollEgress();

            final long nowMs = System.currentTimeMillis();
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

        final PendingRfq rfq = new PendingRfq(
            rfqId,
            session,
            fixQuoteRequest.quoteReqIDAsString(),
            account,
            securityId,
            parseIntOr(securityId, -1),
            side,
            wholeUnits(fixQuoteRequest.orderQty()));
        byRfqId.put(rfqId, rfq);

        rfqEncoder.wrapAndApplyHeader(ingressBuffer, 0, headerEncoder)
            .rfqId(rfqId)
            .clientId(parseIntOr(account, -1))
            .instrumentId(rfq.instrumentId)
            .side(side == '1' ? Side.BUY : Side.SELL)
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
            acceptEncoder.wrapAndApplyHeader(ingressBuffer, 0, headerEncoder)
                .rfqId(rfq.rfqId)
                .quoteId(quoteId);
            offerToCluster(MessageHeaderEncoder.ENCODED_LENGTH + acceptEncoder.encodedLength());
        }
        else
        {
            stats.passes++;
            forget(rfq);
        }
    }

    private void offerToCluster(final int length)
    {
        sendIdle.reset();
        while (cluster.offer(ingressBuffer, 0, length) < 0)
        {
            if (cluster.isClosed())
            {
                throw new IllegalStateException("cluster session closed");
            }
            sendIdle.idle();
        }
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

        void print(final int liveRfqs)
        {
            System.out.printf(
                "gateway: rfqs %d  quotes %d  hits %d  passes %d  trades %d  rejects %d  live %d%s%n",
                rfqsIn, quotesOut, hits, passes, trades, rejects, liveRfqs,
                sendFailures > 0 ? "  SEND FAILURES " + sendFailures : "");
        }
    }
}
