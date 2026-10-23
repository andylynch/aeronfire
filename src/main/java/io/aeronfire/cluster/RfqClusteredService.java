package io.aeronfire.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeronfire.domain.CreditBook;
import io.aeronfire.domain.Instruments;
import io.aeronfire.domain.OpenQuotes;
import io.aeronfire.domain.Positions;
import io.aeronfire.domain.Pricer;
import io.aeronfire.domain.Tier;
import io.aeronfire.sbe.BookSnapshotDecoder;
import io.aeronfire.sbe.BookSnapshotEncoder;
import io.aeronfire.sbe.BooleanType;
import io.aeronfire.sbe.ClientCreditSnapshotDecoder;
import io.aeronfire.sbe.ClientCreditSnapshotEncoder;
import io.aeronfire.sbe.CreditResetAckEncoder;
import io.aeronfire.sbe.CreditResetDecoder;
import io.aeronfire.sbe.CreditStatusEncoder;
import io.aeronfire.sbe.CreditUpdateDecoder;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.OpenQuoteSnapshotDecoder;
import io.aeronfire.sbe.OpenQuoteSnapshotEncoder;
import io.aeronfire.sbe.QuoteAcceptDecoder;
import io.aeronfire.sbe.QuoteEncoder;
import io.aeronfire.sbe.RejectEncoder;
import io.aeronfire.sbe.RejectReason;
import io.aeronfire.sbe.RfqRequestDecoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.SnapshotEndDecoder;
import io.aeronfire.sbe.SnapshotEndEncoder;
import io.aeronfire.sbe.TradeEncoder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Credit RFQ engine running as a replicated state machine inside Aeron Cluster.
 * <p>
 * Every node applies the same sequenced log, so this class must be deterministic: it uses only cluster
 * time (never the wall clock), issues ids from counters, and derives all state from log messages. Hot-path
 * handlers do not allocate: codecs and buffers are preallocated and state lives in primitive arrays.
 * <p>
 * Pre-trade credit runs here too, so limits are enforced against the same sequenced view of trades on
 * every member: no race between two gateways spending the same headroom.
 */
public final class RfqClusteredService implements ClusteredService
{
    private static final int OPEN_QUOTE_CAPACITY = 4096;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final RfqRequestDecoder rfqDecoder = new RfqRequestDecoder();
    private final QuoteAcceptDecoder acceptDecoder = new QuoteAcceptDecoder();
    private final CreditUpdateDecoder creditUpdateDecoder = new CreditUpdateDecoder();
    private final CreditResetDecoder creditResetDecoder = new CreditResetDecoder();
    private final CreditResetAckEncoder creditResetAckEncoder = new CreditResetAckEncoder();
    private final QuoteEncoder quoteEncoder = new QuoteEncoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final RejectEncoder rejectEncoder = new RejectEncoder();
    private final CreditStatusEncoder creditStatusEncoder = new CreditStatusEncoder();
    private final BookSnapshotEncoder bookSnapshotEncoder = new BookSnapshotEncoder();
    private final BookSnapshotDecoder bookSnapshotDecoder = new BookSnapshotDecoder();
    private final OpenQuoteSnapshotEncoder quoteSnapshotEncoder = new OpenQuoteSnapshotEncoder();
    private final OpenQuoteSnapshotDecoder quoteSnapshotDecoder = new OpenQuoteSnapshotDecoder();
    private final ClientCreditSnapshotEncoder creditSnapshotEncoder = new ClientCreditSnapshotEncoder();
    private final ClientCreditSnapshotDecoder creditSnapshotDecoder = new ClientCreditSnapshotDecoder();
    private final SnapshotEndEncoder snapshotEndEncoder = new SnapshotEndEncoder();
    private final SnapshotEndDecoder snapshotEndDecoder = new SnapshotEndDecoder();
    private final MutableDirectBuffer egressBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

    private final Pricer pricer;
    private final Positions positions = new Positions();
    private final OpenQuotes openQuotes = new OpenQuotes(OPEN_QUOTE_CAPACITY);
    private final CreditBook credit = CreditBook.withDemoClients();

    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private long nextQuoteId;
    private long nextTradeId;
    private boolean snapshotLoaded;

    public RfqClusteredService()
    {
        this(Pricer.defaults());
    }

    public RfqClusteredService(final Pricer pricer)
    {
        this.pricer = pricer;
    }

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage)
    {
        this.cluster = cluster;
        this.idleStrategy = cluster.idleStrategy();

        if (null != snapshotImage)
        {
            loadSnapshot(snapshotImage);
        }
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp)
    {
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason)
    {
    }

    @Override
    public void onSessionMessage(
        final ClientSession session,
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
            case RfqRequestDecoder.TEMPLATE_ID ->
            {
                rfqDecoder.wrap(buffer, bodyOffset, blockLength, version);
                onRfq(session, timestamp);
            }
            case QuoteAcceptDecoder.TEMPLATE_ID ->
            {
                acceptDecoder.wrap(buffer, bodyOffset, blockLength, version);
                onAccept(session, timestamp);
            }
            case CreditUpdateDecoder.TEMPLATE_ID ->
            {
                creditUpdateDecoder.wrap(buffer, bodyOffset, blockLength, version);
                onCreditUpdate(session);
            }
            case CreditResetDecoder.TEMPLATE_ID ->
            {
                creditResetDecoder.wrap(buffer, bodyOffset, blockLength, version);
                final int clientId = creditResetDecoder.clientId();
                creditResetAckEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                    .clientId(clientId)
                    .linesReset(credit.resetUtilisation(clientId));
                offer(session, MessageHeaderEncoder.ENCODED_LENGTH + creditResetAckEncoder.encodedLength());
            }
            default -> reject(session, -1, RejectReason.INVALID_REQUEST, headerDecoder.templateId());
        }
    }

    private void onRfq(final ClientSession session, final long timestamp)
    {
        final long rfqId = rfqDecoder.rfqId();
        final int clientId = rfqDecoder.clientId();
        final int instrumentId = rfqDecoder.instrumentId();
        // Read the raw enum value: decoding an unknown value would throw, and an exception here would stall the log.
        final short sideRaw = rfqDecoder.sideRaw();
        final long notional = rfqDecoder.notional();

        if (!Instruments.isValid(instrumentId))
        {
            reject(session, rfqId, RejectReason.UNKNOWN_INSTRUMENT, RfqRequestDecoder.TEMPLATE_ID);
            return;
        }
        if (notional <= 0 || (sideRaw != Side.BUY.value() && sideRaw != Side.SELL.value()))
        {
            reject(session, rfqId, RejectReason.INVALID_REQUEST, RfqRequestDecoder.TEMPLATE_ID);
            return;
        }

        final CreditBook.Check check = credit.check(clientId, notional);
        if (check != CreditBook.Check.OK)
        {
            reject(session, rfqId, toRejectReason(check), RfqRequestDecoder.TEMPLATE_ID);
            return;
        }

        final Tier tier = credit.tier(clientId);
        final boolean clientBuys = sideRaw == Side.BUY.value();
        final Side side = clientBuys ? Side.BUY : Side.SELL;
        final int spreadBps = pricer.quotedSpreadBps(instrumentId, clientBuys, notional, positions.net(instrumentId))
            + tier.spreadAdjustBps();
        final long priceE6 = pricer.priceE6(instrumentId, spreadBps);
        final long quoteId = nextQuoteId++;
        final long validUntil = timestamp + tier.quoteTtlMs();

        openQuotes.put(quoteId, rfqId, session.id(), clientId, instrumentId, clientBuys, notional, priceE6, validUntil);

        quoteEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .rfqId(rfqId)
            .quoteId(quoteId)
            .clientId(clientId)
            .instrumentId(instrumentId)
            .side(side)
            .notional(notional)
            .price(priceE6)
            .spreadBps(spreadBps)
            .validUntil(validUntil);
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + quoteEncoder.encodedLength());
    }

    private void onAccept(final ClientSession session, final long timestamp)
    {
        final long rfqId = acceptDecoder.rfqId();
        final long quoteId = acceptDecoder.quoteId();
        final int slot = openQuotes.find(quoteId);

        if (slot < 0 || openQuotes.clusterSessionIdAt(slot) != session.id() || openQuotes.rfqIdAt(slot) != rfqId)
        {
            reject(session, quoteId, RejectReason.UNKNOWN_QUOTE, QuoteAcceptDecoder.TEMPLATE_ID);
            return;
        }
        if (timestamp > openQuotes.validUntilAt(slot))
        {
            openQuotes.remove(slot);
            reject(session, quoteId, RejectReason.QUOTE_EXPIRED, QuoteAcceptDecoder.TEMPLATE_ID);
            return;
        }

        final int clientId = openQuotes.clientIdAt(slot);
        final long notional = openQuotes.notionalAt(slot);

        // Re-check: other trades may have used the client's headroom since we quoted.
        final CreditBook.Check check = credit.check(clientId, notional);
        if (check != CreditBook.Check.OK)
        {
            openQuotes.remove(slot);
            reject(session, quoteId, toRejectReason(check), QuoteAcceptDecoder.TEMPLATE_ID);
            return;
        }

        final int instrumentId = openQuotes.instrumentIdAt(slot);
        final boolean clientBuys = openQuotes.clientBuysAt(slot);
        final long priceE6 = openQuotes.priceE6At(slot);
        openQuotes.remove(slot);
        positions.applyFill(instrumentId, clientBuys, notional);
        credit.consume(clientId, notional);

        tradeEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .tradeId(nextTradeId++)
            .rfqId(rfqId)
            .quoteId(quoteId)
            .clientId(clientId)
            .instrumentId(instrumentId)
            .side(clientBuys ? Side.BUY : Side.SELL)
            .notional(notional)
            .price(priceE6)
            .executedAt(timestamp)
            .clientUtilised(credit.utilised(clientId));
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength());
    }

    private void onCreditUpdate(final ClientSession session)
    {
        final int clientId = creditUpdateDecoder.clientId();
        final Tier tier = Tier.fromCode(creditUpdateDecoder.tierRaw());
        final long limit = creditUpdateDecoder.creditLimit();

        if (!CreditBook.isValidId(clientId) || null == tier || limit < 0)
        {
            reject(session, clientId, RejectReason.INVALID_REQUEST, CreditUpdateDecoder.TEMPLATE_ID);
            return;
        }

        final boolean reset = creditUpdateDecoder.resetUtilisationRaw() == BooleanType.TRUE.value();
        final long utilised = reset || !credit.isKnown(clientId) ? 0 : credit.utilised(clientId);
        credit.upsert(clientId, tier, limit, utilised);

        creditStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .clientId(clientId)
            .tier(io.aeronfire.sbe.Tier.get((short)tier.ordinal()))
            .creditLimit(limit)
            .utilised(utilised);
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + creditStatusEncoder.encodedLength());
    }

    private static RejectReason toRejectReason(final CreditBook.Check check)
    {
        return switch (check)
        {
            case UNKNOWN_CLIENT -> RejectReason.UNKNOWN_CLIENT;
            case ABOVE_TIER_MAX -> RejectReason.ABOVE_TIER_MAX;
            case CREDIT_LIMIT -> RejectReason.CREDIT_LIMIT;
            case OK -> throw new IllegalArgumentException("not a rejection");
        };
    }

    private void reject(
        final ClientSession session, final long correlationId, final RejectReason reason, final int rejectedTemplateId)
    {
        rejectEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .correlationId(correlationId)
            .reason(reason)
            .rejectedTemplateId(rejectedTemplateId);
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + rejectEncoder.encodedLength());
    }

    private void offer(final ClientSession session, final int length)
    {
        // Followers get a mocked offer result, so only the leader actually sends egress.
        idleStrategy.reset();
        while (session.offer(egressBuffer, 0, length) < 0)
        {
            idleStrategy.idle();
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp)
    {
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
    {
        final long now = cluster.time();

        final BookSnapshotEncoder.PositionsEncoder positionsEncoder =
            bookSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                .nextQuoteId(nextQuoteId)
                .nextTradeId(nextTradeId)
                .positionsCount(positions.nonZeroCount());
        for (int instrumentId = 0; instrumentId < Instruments.COUNT; instrumentId++)
        {
            final long net = positions.net(instrumentId);
            if (net != 0)
            {
                positionsEncoder.next().instrumentId(instrumentId).netNotional(net);
            }
        }
        offerSnapshot(snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + bookSnapshotEncoder.encodedLength());

        int quotesWritten = 0;
        for (int slot = 0; slot < openQuotes.capacity(); slot++)
        {
            final long quoteId = openQuotes.quoteIdAt(slot);
            if (quoteId == OpenQuotes.NONE || openQuotes.validUntilAt(slot) < now)
            {
                continue;
            }

            quoteSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                .quoteId(quoteId)
                .rfqId(openQuotes.rfqIdAt(slot))
                .clusterSessionId(openQuotes.clusterSessionIdAt(slot))
                .clientId(openQuotes.clientIdAt(slot))
                .instrumentId(openQuotes.instrumentIdAt(slot))
                .side(openQuotes.clientBuysAt(slot) ? Side.BUY : Side.SELL)
                .notional(openQuotes.notionalAt(slot))
                .price(openQuotes.priceE6At(slot))
                .validUntil(openQuotes.validUntilAt(slot));
            offerSnapshot(snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + quoteSnapshotEncoder.encodedLength());
            quotesWritten++;
        }

        int clientsWritten = 0;
        for (int clientId = 0; clientId < CreditBook.CAPACITY; clientId++)
        {
            if (!credit.isKnown(clientId))
            {
                continue;
            }

            creditSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                .clientId(clientId)
                .tier(io.aeronfire.sbe.Tier.get((short)credit.tier(clientId).ordinal()))
                .creditLimit(credit.limit(clientId))
                .utilised(credit.utilised(clientId));
            offerSnapshot(snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + creditSnapshotEncoder.encodedLength());
            clientsWritten++;
        }

        snapshotEndEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .openQuoteCount(quotesWritten)
            .clientCount(clientsWritten);
        offerSnapshot(snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + snapshotEndEncoder.encodedLength());
    }

    private void offerSnapshot(final ExclusivePublication publication, final int length)
    {
        idleStrategy.reset();
        while (publication.offer(egressBuffer, 0, length) < 0)
        {
            idleStrategy.idle();
        }
    }

    private void loadSnapshot(final Image snapshotImage)
    {
        snapshotLoaded = false;
        credit.clear(); // the snapshot is authoritative; drop the demo seed
        final FragmentHandler handler = this::onSnapshotFragment;

        idleStrategy.reset();
        while (!snapshotLoaded)
        {
            final int fragments = snapshotImage.poll(handler, 1);
            if (snapshotLoaded)
            {
                break;
            }
            if (snapshotImage.isClosed())
            {
                throw new IllegalStateException("snapshot image closed before SnapshotEnd");
            }
            idleStrategy.idle(fragments);
        }
    }

    private void onSnapshotFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        headerDecoder.wrap(buffer, offset);
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int blockLength = headerDecoder.blockLength();
        final int version = headerDecoder.version();

        switch (headerDecoder.templateId())
        {
            case BookSnapshotDecoder.TEMPLATE_ID ->
            {
                bookSnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                nextQuoteId = bookSnapshotDecoder.nextQuoteId();
                nextTradeId = bookSnapshotDecoder.nextTradeId();
                for (final BookSnapshotDecoder.PositionsDecoder p : bookSnapshotDecoder.positions())
                {
                    positions.set(p.instrumentId(), p.netNotional());
                }
            }
            case OpenQuoteSnapshotDecoder.TEMPLATE_ID ->
            {
                quoteSnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                openQuotes.put(
                    quoteSnapshotDecoder.quoteId(),
                    quoteSnapshotDecoder.rfqId(),
                    quoteSnapshotDecoder.clusterSessionId(),
                    quoteSnapshotDecoder.clientId(),
                    quoteSnapshotDecoder.instrumentId(),
                    quoteSnapshotDecoder.side() == Side.BUY,
                    quoteSnapshotDecoder.notional(),
                    quoteSnapshotDecoder.price(),
                    quoteSnapshotDecoder.validUntil());
            }
            case ClientCreditSnapshotDecoder.TEMPLATE_ID ->
            {
                creditSnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                credit.upsert(
                    creditSnapshotDecoder.clientId(),
                    Tier.fromCode(creditSnapshotDecoder.tierRaw()),
                    creditSnapshotDecoder.creditLimit(),
                    creditSnapshotDecoder.utilised());
            }
            case SnapshotEndDecoder.TEMPLATE_ID ->
            {
                snapshotEndDecoder.wrap(buffer, bodyOffset, blockLength, version);
                snapshotLoaded = true;
            }
            default -> throw new IllegalStateException("unknown snapshot template: " + headerDecoder.templateId());
        }
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole)
    {
    }

    @Override
    public void onTerminate(final Cluster cluster)
    {
    }
}
