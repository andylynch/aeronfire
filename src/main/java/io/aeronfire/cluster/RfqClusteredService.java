package io.aeronfire.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeronfire.domain.AlgoControls;
import io.aeronfire.domain.ClientEligibility;
import io.aeronfire.domain.Entitlements;
import io.aeronfire.domain.PendingChanges;
import io.aeronfire.domain.CreditBook;
import io.aeronfire.domain.EventJournal;
import io.aeronfire.domain.Instruments;
import io.aeronfire.domain.InventoryBook;
import io.aeronfire.domain.OpenQuotes;
import io.aeronfire.domain.Positions;
import io.aeronfire.domain.Pricer;
import io.aeronfire.domain.Tier;
import io.aeronfire.sbe.AdjustReason;
import io.aeronfire.sbe.AlgoControlsDecoder;
import io.aeronfire.sbe.AlgoControlsSnapshotDecoder;
import io.aeronfire.sbe.AlgoControlsSnapshotEncoder;
import io.aeronfire.sbe.AlgoControlsStatusEncoder;
import io.aeronfire.sbe.ApprovalDecoder;
import io.aeronfire.sbe.ClientEligibilityDecoder;
import io.aeronfire.sbe.ClientEligibilitySnapshotDecoder;
import io.aeronfire.sbe.ClientEligibilitySnapshotEncoder;
import io.aeronfire.sbe.ClientEligibilityStatusEncoder;
import io.aeronfire.sbe.EntitlementSnapshotDecoder;
import io.aeronfire.sbe.EntitlementSnapshotEncoder;
import io.aeronfire.sbe.EntitlementStatusEncoder;
import io.aeronfire.sbe.PendingChangeSnapshotDecoder;
import io.aeronfire.sbe.PendingChangeSnapshotEncoder;
import io.aeronfire.sbe.PendingChangeStatusEncoder;
import io.aeronfire.sbe.PendingState;
import io.aeronfire.sbe.SetEntitlementDecoder;
import io.aeronfire.sbe.BookSnapshotDecoder;
import io.aeronfire.sbe.BookSnapshotEncoder;
import io.aeronfire.sbe.BooleanType;
import io.aeronfire.sbe.ClientCreditSnapshotDecoder;
import io.aeronfire.sbe.ClientCreditSnapshotEncoder;
import io.aeronfire.sbe.CreditResetAckEncoder;
import io.aeronfire.sbe.CreditResetDecoder;
import io.aeronfire.sbe.CreditStatusEncoder;
import io.aeronfire.sbe.CreditUpdateDecoder;
import io.aeronfire.sbe.EventFeedBatchEndEncoder;
import io.aeronfire.sbe.EventFeedRequestDecoder;
import io.aeronfire.sbe.InventoryLimitDecoder;
import io.aeronfire.sbe.InventorySnapshotDecoder;
import io.aeronfire.sbe.InventorySnapshotEncoder;
import io.aeronfire.sbe.InventoryStatusEncoder;
import io.aeronfire.sbe.JournalEventSnapshotDecoder;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.OpenQuoteSnapshotDecoder;
import io.aeronfire.sbe.OpenQuoteSnapshotEncoder;
import io.aeronfire.sbe.PositionAdjustDecoder;
import io.aeronfire.sbe.QuoteAcceptDecoder;
import io.aeronfire.sbe.QuoteEncoder;
import io.aeronfire.sbe.RejectEncoder;
import io.aeronfire.sbe.RejectReason;
import io.aeronfire.sbe.RfqRequestDecoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.SnapshotEndDecoder;
import io.aeronfire.sbe.SnapshotEndEncoder;
import io.aeronfire.sbe.StateDumpEndEncoder;
import io.aeronfire.sbe.SubscribeDecoder;
import io.aeronfire.sbe.SubscriberSnapshotDecoder;
import io.aeronfire.sbe.SubscriberSnapshotEncoder;
import io.aeronfire.sbe.TradeEncoder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Credit RFQ engine running as a replicated state machine inside Aeron Cluster.
 * <p>
 * Every member applies the same sequenced log, so this class must be deterministic: it uses only cluster
 * time (never the wall clock), issues ids from counters, and derives all state from log messages. Hot-path
 * handlers do not allocate: codecs and buffers are preallocated and state lives in primitive arrays.
 * <p>
 * Pre-trade controls run here, against one sequenced view of trades on every member: credit and tier
 * (per client), inventory limits and halts (per instrument), and the MiFID II RTS 6 controls (kill switch,
 * price collar, per-client throttle). No race between two gateways spending the same headroom.
 * <p>
 * Admin commands are accepted only from sessions the cluster authenticated as the control plane
 * ({@link ClusterAuthenticator}), then authorised per user against {@link Entitlements}: permission, client
 * and instrument scope, and personal limits. Changes above a user's limits, kill-switch release, entitlement
 * grants and sanctions removals need a second user's approval (four-eyes, {@link PendingChanges}).
 * <p>
 * Every state change is written to the {@link EventJournal}: the order and transaction record (MiFIR Art 25)
 * and the source of the guaranteed post-trade feed, which consumers pull by sequence number.
 * <p>
 * Monitors (the blotter UIs) subscribe for a drop copy of all flow. The copy is best effort: one offer
 * attempt per subscriber, never blocking the log, and a subscriber can always resync from a state dump.
 */
public final class RfqClusteredService implements ClusteredService
{
    private static final int OPEN_QUOTE_CAPACITY = 4096;
    private static final int JOURNAL_CAPACITY = 1 << 16;
    private static final int MAX_FEED_BATCH = 512;
    private static final int MAX_SUBSCRIBERS = 16;
    private static final byte[] CONTROL_PRINCIPAL = ClusterAuthenticator.CONTROL_PRINCIPAL.getBytes(StandardCharsets.US_ASCII);
    private static final int ANY = -2; // scope check not applicable

    // ------------------------------------------------------------------ codecs (preallocated)
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final RfqRequestDecoder rfqDecoder = new RfqRequestDecoder();
    private final QuoteAcceptDecoder acceptDecoder = new QuoteAcceptDecoder();
    private final CreditUpdateDecoder creditUpdateDecoder = new CreditUpdateDecoder();
    private final CreditResetDecoder creditResetDecoder = new CreditResetDecoder();
    private final InventoryLimitDecoder inventoryLimitDecoder = new InventoryLimitDecoder();
    private final PositionAdjustDecoder positionAdjustDecoder = new PositionAdjustDecoder();
    private final AlgoControlsDecoder algoControlsDecoder = new AlgoControlsDecoder();
    private final EventFeedRequestDecoder feedRequestDecoder = new EventFeedRequestDecoder();
    private final SubscribeDecoder subscribeDecoder = new SubscribeDecoder();
    private final SetEntitlementDecoder setEntitlementDecoder = new SetEntitlementDecoder();
    private final ApprovalDecoder approvalDecoder = new ApprovalDecoder();
    private final ClientEligibilityDecoder eligibilityDecoder = new ClientEligibilityDecoder();
    private final PendingChangeStatusEncoder pendingStatusEncoder = new PendingChangeStatusEncoder();
    private final EntitlementStatusEncoder entitlementStatusEncoder = new EntitlementStatusEncoder();
    private final ClientEligibilityStatusEncoder eligibilityStatusEncoder = new ClientEligibilityStatusEncoder();
    private final EntitlementSnapshotEncoder entitlementSnapshotEncoder = new EntitlementSnapshotEncoder();
    private final EntitlementSnapshotDecoder entitlementSnapshotDecoder = new EntitlementSnapshotDecoder();
    private final PendingChangeSnapshotEncoder pendingSnapshotEncoder = new PendingChangeSnapshotEncoder();
    private final PendingChangeSnapshotDecoder pendingSnapshotDecoder = new PendingChangeSnapshotDecoder();
    private final ClientEligibilitySnapshotEncoder eligibilitySnapshotEncoder = new ClientEligibilitySnapshotEncoder();
    private final ClientEligibilitySnapshotDecoder eligibilitySnapshotDecoder = new ClientEligibilitySnapshotDecoder();
    private final UnsafeBuffer commandBuffer = new UnsafeBuffer(new byte[PendingChanges.MAX_COMMAND_LENGTH]);
    private final QuoteEncoder quoteEncoder = new QuoteEncoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final RejectEncoder rejectEncoder = new RejectEncoder();
    private final CreditStatusEncoder creditStatusEncoder = new CreditStatusEncoder();
    private final CreditResetAckEncoder creditResetAckEncoder = new CreditResetAckEncoder();
    private final InventoryStatusEncoder inventoryStatusEncoder = new InventoryStatusEncoder();
    private final AlgoControlsStatusEncoder algoStatusEncoder = new AlgoControlsStatusEncoder();
    private final EventFeedBatchEndEncoder feedBatchEndEncoder = new EventFeedBatchEndEncoder();
    private final StateDumpEndEncoder stateDumpEndEncoder = new StateDumpEndEncoder();
    private final JournalCodec journalCodec = new JournalCodec();
    private final BookSnapshotEncoder bookSnapshotEncoder = new BookSnapshotEncoder();
    private final BookSnapshotDecoder bookSnapshotDecoder = new BookSnapshotDecoder();
    private final OpenQuoteSnapshotEncoder quoteSnapshotEncoder = new OpenQuoteSnapshotEncoder();
    private final OpenQuoteSnapshotDecoder quoteSnapshotDecoder = new OpenQuoteSnapshotDecoder();
    private final ClientCreditSnapshotEncoder creditSnapshotEncoder = new ClientCreditSnapshotEncoder();
    private final ClientCreditSnapshotDecoder creditSnapshotDecoder = new ClientCreditSnapshotDecoder();
    private final InventorySnapshotEncoder inventorySnapshotEncoder = new InventorySnapshotEncoder();
    private final InventorySnapshotDecoder inventorySnapshotDecoder = new InventorySnapshotDecoder();
    private final AlgoControlsSnapshotEncoder algoSnapshotEncoder = new AlgoControlsSnapshotEncoder();
    private final AlgoControlsSnapshotDecoder algoSnapshotDecoder = new AlgoControlsSnapshotDecoder();
    private final JournalEventSnapshotDecoder journalSnapshotDecoder = new JournalEventSnapshotDecoder();
    private final SubscriberSnapshotEncoder subscriberSnapshotEncoder = new SubscriberSnapshotEncoder();
    private final SubscriberSnapshotDecoder subscriberSnapshotDecoder = new SubscriberSnapshotDecoder();
    private final SnapshotEndEncoder snapshotEndEncoder = new SnapshotEndEncoder();
    private final SnapshotEndDecoder snapshotEndDecoder = new SnapshotEndDecoder();
    private final MutableDirectBuffer egressBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));

    // ------------------------------------------------------------------ state
    private final Pricer pricer;
    private final Positions positions = new Positions();
    private final OpenQuotes openQuotes = new OpenQuotes(OPEN_QUOTE_CAPACITY);
    private final CreditBook credit = CreditBook.withDemoClients();
    private final InventoryBook inventory = new InventoryBook();
    private final AlgoControls algo = new AlgoControls();
    private final EventJournal journal = new EventJournal(JOURNAL_CAPACITY);
    private final Entitlements entitlements = Entitlements.withDemoUsers();
    private final PendingChanges pending = new PendingChanges();
    private final ClientEligibility eligibility = new ClientEligibility();
    private final long[] subscribers = new long[MAX_SUBSCRIBERS];
    private int subscriberCount;
    private long nextQuoteId;
    private long nextTradeId;
    private long nextPendingId;

    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private boolean snapshotLoaded;
    private long droppedCopies;

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
        removeSubscriber(session.id());
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
            case EventFeedRequestDecoder.TEMPLATE_ID ->
            {
                feedRequestDecoder.wrap(buffer, bodyOffset, blockLength, version);
                onFeedRequest(session);
            }
            case CreditUpdateDecoder.TEMPLATE_ID, CreditResetDecoder.TEMPLATE_ID, InventoryLimitDecoder.TEMPLATE_ID,
                PositionAdjustDecoder.TEMPLATE_ID, AlgoControlsDecoder.TEMPLATE_ID, SetEntitlementDecoder.TEMPLATE_ID,
                ApprovalDecoder.TEMPLATE_ID, ClientEligibilityDecoder.TEMPLATE_ID ->
            {
                if (isControlSession(session))
                {
                    onAdmin(session, timestamp, buffer, offset, length, -1);
                }
                else
                {
                    reject(session, timestamp, -1, -1, -1, RejectReason.NOT_AUTHENTICATED, headerDecoder.templateId());
                }
            }
            case SubscribeDecoder.TEMPLATE_ID ->
            {
                subscribeDecoder.wrap(buffer, bodyOffset, blockLength, version);
                onSubscribe(session, subscribeDecoder.sendStateDumpRaw() == BooleanType.TRUE.value());
            }
            default -> reject(session, timestamp, -1, -1, -1, RejectReason.INVALID_REQUEST, headerDecoder.templateId());
        }
    }

    // ================================================================== trading flow

    private void onRfq(final ClientSession session, final long timestamp)
    {
        final long rfqId = rfqDecoder.rfqId();
        final int clientId = rfqDecoder.clientId();
        final int instrumentId = rfqDecoder.instrumentId();
        // Read the raw enum value: decoding an unknown value would throw, and an exception here would stall the log.
        final short sideRaw = rfqDecoder.sideRaw();
        final long notional = rfqDecoder.notional();
        final boolean validSide = sideRaw == Side.BUY.value() || sideRaw == Side.SELL.value();

        // Record the request before any check: rejected requests are part of the order record too.
        final int event = journal.append(EventJournal.RFQ, timestamp);
        journal.ids(event, rfqId, -1, -1);
        journal.order(event, clientId, instrumentId, validSide ? sideRaw : -1, notional, 0);

        final int t = RfqRequestDecoder.TEMPLATE_ID;
        final AlgoControls.Check algoCheck = algo.onRfq(clientId, timestamp);
        if (algoCheck != AlgoControls.Check.OK)
        {
            reject(session, timestamp, rfqId, clientId, instrumentId,
                algoCheck == AlgoControls.Check.KILL_SWITCH ? RejectReason.KILL_SWITCH : RejectReason.THROTTLED, t);
            return;
        }
        if (!Instruments.isValid(instrumentId))
        {
            reject(session, timestamp, rfqId, clientId, instrumentId, RejectReason.UNKNOWN_INSTRUMENT, t);
            return;
        }
        if (notional <= 0 || !validSide)
        {
            reject(session, timestamp, rfqId, clientId, instrumentId, RejectReason.INVALID_REQUEST, t);
            return;
        }

        final CreditBook.Check creditCheck = credit.check(clientId, notional);
        if (creditCheck != CreditBook.Check.OK)
        {
            reject(session, timestamp, rfqId, clientId, instrumentId, toRejectReason(creditCheck), t);
            return;
        }

        if (eligibility.sanctioned(clientId) || !eligibility.kycValid(clientId))
        {
            reject(session, timestamp, rfqId, clientId, instrumentId,
                eligibility.sanctioned(clientId) ? RejectReason.SANCTIONS : RejectReason.KYC, t);
            return;
        }

        final boolean clientBuys = sideRaw == Side.BUY.value();
        final InventoryBook.Check inventoryCheck =
            inventory.check(instrumentId, positions.net(instrumentId), clientBuys, notional);
        if (inventoryCheck != InventoryBook.Check.OK)
        {
            reject(session, timestamp, rfqId, clientId, instrumentId, toRejectReason(inventoryCheck), t);
            return;
        }

        final Tier tier = credit.tier(clientId);
        final int spreadBps = pricer.quotedSpreadBps(instrumentId, clientBuys, notional, positions.net(instrumentId))
            + tier.spreadAdjustBps();
        final long priceE6 = pricer.priceE6(instrumentId, spreadBps);
        if (!algo.withinCollar(priceE6, pricer.priceE6(instrumentId, Instruments.baseSpreadBps(instrumentId))))
        {
            reject(session, timestamp, rfqId, clientId, instrumentId, RejectReason.PRICE_COLLAR, t);
            return;
        }

        final long quoteId = nextQuoteId++;
        final long validUntil = timestamp + tier.quoteTtlMs();
        openQuotes.put(quoteId, rfqId, session.id(), clientId, instrumentId, clientBuys, notional, priceE6, validUntil);

        final int quoteEvent = journal.append(EventJournal.QUOTE, timestamp);
        journal.ids(quoteEvent, rfqId, quoteId, -1);
        journal.order(quoteEvent, clientId, instrumentId, sideRaw, notional, priceE6);
        journal.extra(quoteEvent, spreadBps, 0, validUntil, 0);

        quoteEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .rfqId(rfqId)
            .quoteId(quoteId)
            .clientId(clientId)
            .instrumentId(instrumentId)
            .side(clientBuys ? Side.BUY : Side.SELL)
            .notional(notional)
            .price(priceE6)
            .spreadBps(spreadBps)
            .validUntil(validUntil);
        offerAndCopy(session, MessageHeaderEncoder.ENCODED_LENGTH + quoteEncoder.encodedLength());
    }

    private void onAccept(final ClientSession session, final long timestamp)
    {
        final long rfqId = acceptDecoder.rfqId();
        final long quoteId = acceptDecoder.quoteId();
        final int slot = openQuotes.find(quoteId);
        final int t = QuoteAcceptDecoder.TEMPLATE_ID;

        if (slot < 0 || openQuotes.clusterSessionIdAt(slot) != session.id() || openQuotes.rfqIdAt(slot) != rfqId)
        {
            reject(session, timestamp, quoteId, -1, -1, RejectReason.UNKNOWN_QUOTE, t);
            return;
        }

        final int clientId = openQuotes.clientIdAt(slot);
        final int instrumentId = openQuotes.instrumentIdAt(slot);
        final boolean clientBuys = openQuotes.clientBuysAt(slot);
        final long notional = openQuotes.notionalAt(slot);

        if (algo.killSwitch())
        {
            openQuotes.remove(slot);
            reject(session, timestamp, quoteId, clientId, instrumentId, RejectReason.KILL_SWITCH, t);
            return;
        }
        if (timestamp > openQuotes.validUntilAt(slot))
        {
            openQuotes.remove(slot);
            reject(session, timestamp, quoteId, clientId, instrumentId, RejectReason.QUOTE_EXPIRED, t);
            return;
        }

        if (eligibility.sanctioned(clientId) || !eligibility.kycValid(clientId))
        {
            openQuotes.remove(slot);
            reject(session, timestamp, quoteId, clientId, instrumentId,
                eligibility.sanctioned(clientId) ? RejectReason.SANCTIONS : RejectReason.KYC, t);
            return;
        }

        // Re-check: other trades may have used the headroom since we quoted.
        final CreditBook.Check creditCheck = credit.check(clientId, notional);
        if (creditCheck != CreditBook.Check.OK)
        {
            openQuotes.remove(slot);
            reject(session, timestamp, quoteId, clientId, instrumentId, toRejectReason(creditCheck), t);
            return;
        }
        final InventoryBook.Check inventoryCheck =
            inventory.check(instrumentId, positions.net(instrumentId), clientBuys, notional);
        if (inventoryCheck != InventoryBook.Check.OK)
        {
            openQuotes.remove(slot);
            reject(session, timestamp, quoteId, clientId, instrumentId, toRejectReason(inventoryCheck), t);
            return;
        }

        final long priceE6 = openQuotes.priceE6At(slot);
        final long tradeId = nextTradeId++;
        openQuotes.remove(slot);
        positions.applyFill(instrumentId, clientBuys, notional);
        credit.consume(clientId, notional);

        final int event = journal.append(EventJournal.TRADE, timestamp);
        journal.ids(event, rfqId, quoteId, tradeId);
        journal.order(event, clientId, instrumentId, clientBuys ? Side.BUY.value() : Side.SELL.value(), notional, priceE6);
        final int venueIdOffset = event * EventJournal.VENUE_TXN_ID_LENGTH;
        acceptDecoder.getVenueTxnId(journal.venueTxnIdBytes(), venueIdOffset);

        tradeEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .tradeId(tradeId)
            .rfqId(rfqId)
            .quoteId(quoteId)
            .clientId(clientId)
            .instrumentId(instrumentId)
            .side(clientBuys ? Side.BUY : Side.SELL)
            .notional(notional)
            .price(priceE6)
            .executedAt(timestamp)
            .clientUtilised(credit.utilised(clientId))
            .putVenueTxnId(journal.venueTxnIdBytes(), venueIdOffset);
        offerAndCopy(session, MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength());
    }

    private int reject(
        final ClientSession session,
        final long timestamp,
        final long correlationId,
        final int clientId,
        final int instrumentId,
        final RejectReason reason,
        final int rejectedTemplateId)
    {
        final int event = journal.append(EventJournal.REJECT, timestamp);
        final boolean onAccept = rejectedTemplateId == QuoteAcceptDecoder.TEMPLATE_ID;
        journal.ids(event, onAccept ? -1 : correlationId, onAccept ? correlationId : -1, -1);
        journal.order(event, clientId, instrumentId, -1, 0, 0);
        journal.extra(event, 0, reason.value(), rejectedTemplateId, 0);

        rejectEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .correlationId(correlationId)
            .reason(reason)
            .rejectedTemplateId(rejectedTemplateId);
        offerAndCopy(session, MessageHeaderEncoder.ENCODED_LENGTH + rejectEncoder.encodedLength());
        return event;
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

    private static RejectReason toRejectReason(final InventoryBook.Check check)
    {
        return switch (check)
        {
            case HALTED -> RejectReason.INSTRUMENT_HALTED;
            case LIMIT -> RejectReason.INVENTORY_LIMIT;
            case OK -> throw new IllegalArgumentException("not a rejection");
        };
    }

    // ================================================================== guaranteed event feed (pull)

    /**
     * Send up to a batch of journalled events from {@code fromSeq}, then a batch end carrying the high-water
     * mark. Consumers pull until they reach it, so they never miss an event while connected or after a
     * restart. Read-only: not journalled. A request below the oldest held event is a gap.
     */
    private void onFeedRequest(final ClientSession session)
    {
        final long fromSeq = Math.max(0, feedRequestDecoder.fromSeq());
        final int max = Math.min(MAX_FEED_BATCH, Math.max(1, feedRequestDecoder.maxEvents()));

        if (fromSeq < journal.oldest())
        {
            rejectEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                .correlationId(journal.oldest())
                .reason(RejectReason.JOURNAL_GAP)
                .rejectedTemplateId(EventFeedRequestDecoder.TEMPLATE_ID);
            offer(session, MessageHeaderEncoder.ENCODED_LENGTH + rejectEncoder.encodedLength());
            return;
        }

        int count = 0;
        for (long seq = fromSeq; seq < journal.next() && count < max; seq++)
        {
            offer(session, journalCodec.encodeRecord(egressBuffer, headerEncoder, journal, journal.slotOf(seq)));
            count++;
        }

        feedBatchEndEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .fromSeq(fromSeq)
            .count(count)
            .nextSeq(journal.next())
            .oldestSeq(journal.oldest());
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + feedBatchEndEncoder.encodedLength());
    }

    // ================================================================== admin: authentication, entitlements, four-eyes

    private static boolean isControlSession(final ClientSession session)
    {
        return Arrays.equals(session.encodedPrincipal(), CONTROL_PRINCIPAL);
    }

    /**
     * Apply an admin command. {@code approver} is -1 for a direct request, or the approving user when a
     * pending change is being re-applied, in which case the approver's permissions and scope are checked and
     * the requester's personal limits are not.
     *
     * @return true if the command was applied.
     */
    private boolean onAdmin(
        final ClientSession session,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int approver)
    {
        headerDecoder.wrap(buffer, offset);
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int blockLength = headerDecoder.blockLength();
        final int version = headerDecoder.version();
        final int templateId = headerDecoder.templateId();

        return switch (templateId)
        {
            case CreditUpdateDecoder.TEMPLATE_ID ->
            {
                creditUpdateDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onCreditUpdate(session, timestamp, buffer, offset, length, approver);
            }
            case CreditResetDecoder.TEMPLATE_ID ->
            {
                creditResetDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onCreditReset(session, timestamp, buffer, offset, length, approver);
            }
            case InventoryLimitDecoder.TEMPLATE_ID ->
            {
                inventoryLimitDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onInventoryLimit(session, timestamp, buffer, offset, length, approver);
            }
            case PositionAdjustDecoder.TEMPLATE_ID ->
            {
                positionAdjustDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onPositionAdjust(session, timestamp, buffer, offset, length, approver);
            }
            case AlgoControlsDecoder.TEMPLATE_ID ->
            {
                algoControlsDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onAlgoControls(session, timestamp, buffer, offset, length, approver);
            }
            case SetEntitlementDecoder.TEMPLATE_ID ->
            {
                setEntitlementDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onSetEntitlement(session, timestamp, buffer, offset, length, approver);
            }
            case ClientEligibilityDecoder.TEMPLATE_ID ->
            {
                eligibilityDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield onEligibility(session, timestamp, buffer, offset, length, approver);
            }
            case ApprovalDecoder.TEMPLATE_ID ->
            {
                approvalDecoder.wrap(buffer, bodyOffset, blockLength, version);
                yield approver < 0 && onApproval(session, timestamp);
            }
            default -> false;
        };
    }

    /**
     * Entitlement gate shared by every admin command.
     *
     * @param clientScope     client the change touches, -1 for all clients, or {@link #ANY} if not client-scoped.
     * @param instrumentScope instrument the change touches, or {@link #ANY}.
     * @param overLimit       the change exceeds the requesting user's personal limit.
     * @param fourEyes        the change always needs a second user (kill-switch release, grants, de-listing).
     * @return true to apply now; false if rejected or parked for approval (a reply has been sent either way).
     */
    private boolean authorise(
        final ClientSession session,
        final long timestamp,
        final int actor,
        final int approver,
        final int permission,
        final int clientScope,
        final int instrumentScope,
        final boolean overLimit,
        final boolean fourEyes,
        final long correlationId,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        final int templateId = headerDecoder.templateId();
        final int user = approver >= 0 ? approver : actor;
        final boolean entitled = entitlements.has(user, permission) &&
            (clientScope == ANY || entitlements.clientInScope(user, clientScope)) &&
            (instrumentScope == ANY || entitlements.instrumentInScope(user, instrumentScope));
        if (!entitled)
        {
            final int event = reject(session, timestamp, correlationId,
                clientScope >= 0 ? clientScope : -1, instrumentScope >= 0 ? instrumentScope : -1,
                RejectReason.NOT_ENTITLED, templateId);
            journal.user(event, user);
            return false;
        }

        if (approver < 0 && actor != Entitlements.ROOT && (overLimit || fourEyes))
        {
            final long pendingId = nextPendingId++;
            if (pending.add(pendingId, actor, templateId, timestamp, buffer, offset, length) < 0)
            {
                final int event = reject(session, timestamp, correlationId, -1, -1, RejectReason.APPROVALS_FULL, templateId);
                journal.user(event, actor);
                return false;
            }

            final int event = journal.append(EventJournal.PENDING_CHANGE, timestamp);
            journal.ids(event, -1, -1, pendingId);
            journal.extra(event, 0, 0, templateId, 0);
            journal.user(event, actor);

            offerAndCopy(session, encodePendingStatus(pendingId, actor, templateId, PendingState.PENDING, -1));
            return false;
        }
        return true;
    }

    private boolean onApproval(final ClientSession session, final long timestamp)
    {
        final int user = approvalDecoder.userId();
        final long pendingId = approvalDecoder.pendingId();
        final boolean approve = approvalDecoder.approveRaw() == BooleanType.TRUE.value();
        final int t = ApprovalDecoder.TEMPLATE_ID;

        if (!entitlements.has(user, Entitlements.APPROVE))
        {
            journal.user(reject(session, timestamp, pendingId, -1, -1, RejectReason.NOT_ENTITLED, t), user);
            return false;
        }
        final int slot = pending.find(pendingId);
        if (slot < 0)
        {
            journal.user(reject(session, timestamp, pendingId, -1, -1, RejectReason.UNKNOWN_PENDING, t), user);
            return false;
        }
        final int requester = pending.requestedByAt(slot);
        final int templateId = pending.templateIdAt(slot);
        if (requester == user)
        {
            journal.user(reject(session, timestamp, pendingId, -1, -1, RejectReason.SELF_APPROVAL, t), user);
            return false;
        }

        if (approve)
        {
            // Re-apply the exact original bytes, authorised as the approver. If the approver isn't entitled
            // to the underlying change it is rejected and stays pending for someone who is.
            final int commandLength = pending.copyCommand(slot, commandBuffer);
            if (!onAdmin(session, timestamp, commandBuffer, 0, commandLength, user))
            {
                return false;
            }
        }

        pending.remove(slot);
        final int event = journal.append(EventJournal.APPROVAL, timestamp);
        journal.ids(event, -1, -1, pendingId);
        journal.extra(event, 0, approve ? 1 : 0, templateId, requester);
        journal.user(event, user);

        offerAndCopy(session, encodePendingStatus(
            pendingId, requester, templateId, approve ? PendingState.APPROVED : PendingState.REJECTED, user));
        return true;
    }

    // ================================================================== admin commands

    private boolean onCreditUpdate(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = creditUpdateDecoder.userId();
        final int clientId = creditUpdateDecoder.clientId();
        final Tier tier = Tier.fromCode(creditUpdateDecoder.tierRaw());
        final long limit = creditUpdateDecoder.creditLimit();
        final boolean reset = creditUpdateDecoder.resetUtilisationRaw() == BooleanType.TRUE.value();

        if (!CreditBook.isValidId(clientId) || null == tier || limit < 0)
        {
            journal.user(reject(session, timestamp, clientId, clientId, -1, RejectReason.INVALID_REQUEST,
                CreditUpdateDecoder.TEMPLATE_ID), actor);
            return false;
        }
        final boolean overLimit = limit > entitlements.maxCreditLimit(Entitlements.isValidUser(actor) ? actor : 0) ||
            !Entitlements.isValidUser(actor);
        if (!authorise(session, timestamp, actor, approver, Entitlements.CREDIT, clientId, ANY, overLimit, false,
            clientId, buffer, offset, length))
        {
            return false;
        }

        final long utilised = reset || !credit.isKnown(clientId) ? 0 : credit.utilised(clientId);
        credit.upsert(clientId, tier, limit, utilised);

        final int event = journal.append(EventJournal.CREDIT_CHANGE, timestamp);
        journal.order(event, clientId, -1, -1, 0, 0);
        journal.extra(event, 0, tier.ordinal(), limit, utilised);
        journal.user(event, actor);

        offerAndCopy(session, encodeCreditStatus(clientId));
        return true;
    }

    private boolean onCreditReset(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = creditResetDecoder.userId();
        final int clientId = creditResetDecoder.clientId();
        if (!authorise(session, timestamp, actor, approver, Entitlements.CREDIT, clientId, ANY, false, false,
            clientId, buffer, offset, length))
        {
            return false;
        }

        final int linesReset = credit.resetUtilisation(clientId);

        final int event = journal.append(EventJournal.CREDIT_RESET, timestamp);
        journal.order(event, clientId, -1, -1, 0, 0);
        journal.extra(event, 0, 0, linesReset, 0);
        journal.user(event, actor);

        creditResetAckEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .clientId(clientId)
            .linesReset(linesReset);
        offerAndCopy(session, MessageHeaderEncoder.ENCODED_LENGTH + creditResetAckEncoder.encodedLength());
        return true;
    }

    private boolean onInventoryLimit(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = inventoryLimitDecoder.userId();
        final int instrumentId = inventoryLimitDecoder.instrumentId();
        final long maxLong = inventoryLimitDecoder.maxLong();
        final long maxShort = inventoryLimitDecoder.maxShort();
        final boolean halted = inventoryLimitDecoder.haltedRaw() == BooleanType.TRUE.value();

        if (!Instruments.isValid(instrumentId) || maxLong < 0 || maxShort < 0)
        {
            journal.user(reject(session, timestamp, instrumentId, -1, instrumentId, RejectReason.INVALID_REQUEST,
                InventoryLimitDecoder.TEMPLATE_ID), actor);
            return false;
        }
        final boolean overLimit = !Entitlements.isValidUser(actor) ||
            Math.max(maxLong, maxShort) > entitlements.maxInventoryLimit(actor);
        if (!authorise(session, timestamp, actor, approver, Entitlements.INVENTORY, ANY, instrumentId, overLimit, false,
            instrumentId, buffer, offset, length))
        {
            return false;
        }

        inventory.set(instrumentId, maxLong, maxShort, halted);

        final int event = journal.append(EventJournal.INVENTORY_CHANGE, timestamp);
        journal.order(event, -1, instrumentId, -1, 0, 0);
        journal.extra(event, 0, halted ? 1 : 0, maxLong, maxShort);
        journal.user(event, actor);

        offerAndCopy(session, encodeInventoryStatus(instrumentId));
        return true;
    }

    private boolean onPositionAdjust(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = positionAdjustDecoder.userId();
        final int instrumentId = positionAdjustDecoder.instrumentId();
        final long delta = positionAdjustDecoder.delta();
        final short reason = positionAdjustDecoder.reasonRaw();

        if (!Instruments.isValid(instrumentId) || delta == 0 || reason > AdjustReason.ERROR_CORRECTION.value())
        {
            journal.user(reject(session, timestamp, instrumentId, -1, instrumentId, RejectReason.INVALID_REQUEST,
                PositionAdjustDecoder.TEMPLATE_ID), actor);
            return false;
        }
        final boolean overLimit = !Entitlements.isValidUser(actor) || Math.abs(delta) > entitlements.maxAdjust(actor);
        if (!authorise(session, timestamp, actor, approver, Entitlements.POSITION_ADJUST, ANY, instrumentId, overLimit,
            false, instrumentId, buffer, offset, length))
        {
            return false;
        }

        final long net = positions.adjust(instrumentId, delta);

        final int event = journal.append(EventJournal.POSITION_ADJUST, timestamp);
        journal.order(event, -1, instrumentId, -1, delta, 0);
        journal.extra(event, 0, reason, net, 0);
        journal.user(event, actor);

        offerAndCopy(session, encodeInventoryStatus(instrumentId));
        return true;
    }

    private boolean onAlgoControls(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = algoControlsDecoder.userId();
        final boolean killSwitch = algoControlsDecoder.killSwitchRaw() == BooleanType.TRUE.value();
        final int collarBps = algoControlsDecoder.priceCollarBps();
        final int maxRfqsPerSecond = algoControlsDecoder.maxRfqsPerSecond();

        if (collarBps <= 0 || maxRfqsPerSecond < 0)
        {
            journal.user(reject(session, timestamp, -1, -1, -1, RejectReason.INVALID_REQUEST,
                AlgoControlsDecoder.TEMPLATE_ID), actor);
            return false;
        }

        // Engaging the kill switch alone needs only KILL_SWITCH; anything else needs ALGO. Release is four-eyes.
        final boolean controlsUnchanged =
            collarBps == algo.priceCollarBps() && maxRfqsPerSecond == algo.maxRfqsPerSecond();
        final int permission = controlsUnchanged ? Entitlements.KILL_SWITCH : Entitlements.ALGO;
        final boolean release = algo.killSwitch() && !killSwitch;
        if (!authorise(session, timestamp, actor, approver, permission, ANY, ANY, false, release,
            -1, buffer, offset, length))
        {
            return false;
        }

        algo.set(killSwitch, collarBps, maxRfqsPerSecond);

        final int event = journal.append(EventJournal.ALGO_CONTROLS, timestamp);
        journal.extra(event, 0, killSwitch ? 1 : 0, collarBps, maxRfqsPerSecond);
        journal.user(event, actor);

        offerAndCopy(session, encodeAlgoStatus());
        return true;
    }

    private boolean onSetEntitlement(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = setEntitlementDecoder.userId();
        final int target = setEntitlementDecoder.targetUserId();
        final int permissions = setEntitlementDecoder.permissions();
        final boolean allClients = setEntitlementDecoder.allClientsRaw() == BooleanType.TRUE.value();
        final int instrumentScope = setEntitlementDecoder.instrumentScope();
        final long maxCreditLimit = setEntitlementDecoder.maxCreditLimit();
        final long maxAdjust = setEntitlementDecoder.maxAdjust();
        final long maxInventoryLimit = setEntitlementDecoder.maxInventoryLimit();

        if (!Entitlements.isValidUser(target) || target == Entitlements.ROOT ||
            (permissions & ~Entitlements.ALL) != 0 || maxCreditLimit < 0 || maxAdjust < 0 || maxInventoryLimit < 0)
        {
            journal.user(reject(session, timestamp, target, -1, -1, RejectReason.INVALID_REQUEST,
                SetEntitlementDecoder.TEMPLATE_ID), actor);
            return false;
        }
        if (target == actor || target == approver) // nobody changes their own entitlements
        {
            journal.user(reject(session, timestamp, target, -1, -1, RejectReason.NOT_ENTITLED,
                SetEntitlementDecoder.TEMPLATE_ID), approver >= 0 ? approver : actor);
            return false;
        }
        if (!authorise(session, timestamp, actor, approver, Entitlements.ENTITLEMENTS, ANY, ANY, false, true,
            target, buffer, offset, length))
        {
            return false;
        }

        entitlements.set(target, permissions, allClients, instrumentScope & Entitlements.ALL_INSTRUMENTS,
            maxCreditLimit, maxAdjust, maxInventoryLimit);
        for (final SetEntitlementDecoder.ClientsDecoder c : setEntitlementDecoder.clients())
        {
            entitlements.addClient(target, c.clientId());
        }

        final int event = journal.append(EventJournal.ENTITLEMENT_CHANGE, timestamp);
        journal.order(event, target, -1, -1, 0, 0);
        journal.extra(event, 0, 0, permissions, instrumentScope);
        journal.user(event, actor);

        offerAndCopy(session, encodeEntitlementStatus(target));
        return true;
    }

    private boolean onEligibility(
        final ClientSession session, final long timestamp, final DirectBuffer buffer, final int offset,
        final int length, final int approver)
    {
        final int actor = eligibilityDecoder.userId();
        final int clientId = eligibilityDecoder.clientId();
        final boolean kycValid = eligibilityDecoder.kycValidRaw() == BooleanType.TRUE.value();
        final boolean sanctioned = eligibilityDecoder.sanctionedRaw() == BooleanType.TRUE.value();

        if (!CreditBook.isValidId(clientId))
        {
            journal.user(reject(session, timestamp, clientId, -1, -1, RejectReason.INVALID_REQUEST,
                ClientEligibilityDecoder.TEMPLATE_ID), actor);
            return false;
        }
        // Blocking is immediate; lifting a sanctions block needs a second pair of eyes.
        final boolean delisting = eligibility.sanctioned(clientId) && !sanctioned;
        if (!authorise(session, timestamp, actor, approver, Entitlements.REFDATA, clientId, ANY, false, delisting,
            clientId, buffer, offset, length))
        {
            return false;
        }

        eligibility.set(clientId, kycValid, sanctioned);

        final int event = journal.append(EventJournal.ELIGIBILITY_CHANGE, timestamp);
        journal.order(event, clientId, -1, -1, 0, 0);
        journal.extra(event, 0, (kycValid ? 1 : 0) | (sanctioned ? 2 : 0), 0, 0);
        journal.user(event, actor);

        offerAndCopy(session, encodeEligibilityStatus(clientId));
        return true;
    }

    private int encodePendingStatus(
        final long pendingId, final int requestedBy, final int templateId, final PendingState state, final int decidedBy)
    {
        pendingStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .pendingId(pendingId)
            .requestedBy(requestedBy)
            .templateId(templateId)
            .state(state)
            .decidedBy(decidedBy);
        return MessageHeaderEncoder.ENCODED_LENGTH + pendingStatusEncoder.encodedLength();
    }

    private int encodeEntitlementStatus(final int user)
    {
        entitlementStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .targetUserId(user)
            .permissions(entitlements.permissions(user))
            .allClients(entitlements.allClients(user) ? BooleanType.TRUE : BooleanType.FALSE)
            .instrumentScope(entitlements.instrumentScope(user))
            .maxCreditLimit(entitlements.maxCreditLimit(user))
            .maxAdjust(entitlements.maxAdjust(user))
            .maxInventoryLimit(entitlements.maxInventoryLimit(user))
            .clientCount(entitlements.clientCount(user));
        return MessageHeaderEncoder.ENCODED_LENGTH + entitlementStatusEncoder.encodedLength();
    }

    private int encodeEligibilityStatus(final int clientId)
    {
        eligibilityStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .clientId(clientId)
            .kycValid(eligibility.kycValid(clientId) ? BooleanType.TRUE : BooleanType.FALSE)
            .sanctioned(eligibility.sanctioned(clientId) ? BooleanType.TRUE : BooleanType.FALSE);
        return MessageHeaderEncoder.ENCODED_LENGTH + eligibilityStatusEncoder.encodedLength();
    }

    private int encodeCreditStatus(final int clientId)
    {
        creditStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .clientId(clientId)
            .tier(io.aeronfire.sbe.Tier.get((short)credit.tier(clientId).ordinal()))
            .creditLimit(credit.limit(clientId))
            .utilised(credit.utilised(clientId));
        return MessageHeaderEncoder.ENCODED_LENGTH + creditStatusEncoder.encodedLength();
    }

    private int encodeInventoryStatus(final int instrumentId)
    {
        inventoryStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .instrumentId(instrumentId)
            .maxLong(inventory.maxLong(instrumentId))
            .maxShort(inventory.maxShort(instrumentId))
            .halted(inventory.isHalted(instrumentId) ? BooleanType.TRUE : BooleanType.FALSE)
            .netNotional(positions.net(instrumentId));
        return MessageHeaderEncoder.ENCODED_LENGTH + inventoryStatusEncoder.encodedLength();
    }

    private int encodeAlgoStatus()
    {
        algoStatusEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .killSwitch(algo.killSwitch() ? BooleanType.TRUE : BooleanType.FALSE)
            .priceCollarBps(algo.priceCollarBps())
            .maxRfqsPerSecond(algo.maxRfqsPerSecond());
        return MessageHeaderEncoder.ENCODED_LENGTH + algoStatusEncoder.encodedLength();
    }

    // ================================================================== monitors: drop copy and state dump

    private void onSubscribe(final ClientSession session, final boolean sendStateDump)
    {
        if (!isSubscriber(session.id()))
        {
            if (subscriberCount == MAX_SUBSCRIBERS)
            {
                rejectEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                    .correlationId(session.id())
                    .reason(RejectReason.INVALID_REQUEST)
                    .rejectedTemplateId(SubscribeDecoder.TEMPLATE_ID);
                offer(session, MessageHeaderEncoder.ENCODED_LENGTH + rejectEncoder.encodedLength());
                return;
            }
            subscribers[subscriberCount++] = session.id();
        }

        if (!sendStateDump)
        {
            return;
        }

        for (int instrumentId = 0; instrumentId < Instruments.COUNT; instrumentId++)
        {
            offer(session, encodeInventoryStatus(instrumentId));
        }
        for (int clientId = 0; clientId < CreditBook.CAPACITY; clientId++)
        {
            if (credit.isKnown(clientId))
            {
                offer(session, encodeCreditStatus(clientId));
            }
        }
        offer(session, encodeAlgoStatus());
        for (int user = 0; user < Entitlements.CAPACITY; user++)
        {
            if (entitlements.isKnown(user))
            {
                offer(session, encodeEntitlementStatus(user));
            }
        }
        for (int clientId = 0; clientId < CreditBook.CAPACITY; clientId++)
        {
            if (!eligibility.isDefault(clientId))
            {
                offer(session, encodeEligibilityStatus(clientId));
            }
        }
        for (int slot = 0; slot < PendingChanges.CAPACITY; slot++)
        {
            if (pending.pendingIdAt(slot) >= 0)
            {
                offer(session, encodePendingStatus(pending.pendingIdAt(slot), pending.requestedByAt(slot),
                    pending.templateIdAt(slot), PendingState.PENDING, -1));
            }
        }
        stateDumpEndEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .nextQuoteId(nextQuoteId)
            .nextTradeId(nextTradeId);
        offer(session, MessageHeaderEncoder.ENCODED_LENGTH + stateDumpEndEncoder.encodedLength());
    }

    private boolean isSubscriber(final long clusterSessionId)
    {
        for (int i = 0; i < subscriberCount; i++)
        {
            if (subscribers[i] == clusterSessionId)
            {
                return true;
            }
        }
        return false;
    }

    private void removeSubscriber(final long clusterSessionId)
    {
        for (int i = 0; i < subscriberCount; i++)
        {
            if (subscribers[i] == clusterSessionId)
            {
                subscribers[i] = subscribers[--subscriberCount];
                return;
            }
        }
    }

    /**
     * Reply to the requester (blocking until sent), then drop-copy to monitors (one attempt each).
     */
    private void offerAndCopy(final ClientSession session, final int length)
    {
        offer(session, length);
        for (int i = 0; i < subscriberCount; i++)
        {
            final long subscriberId = subscribers[i];
            if (subscriberId == session.id())
            {
                continue;
            }
            final ClientSession subscriber = cluster.getClientSession(subscriberId);
            if (null != subscriber && subscriber.offer(egressBuffer, 0, length) < 0)
            {
                droppedCopies++; // diagnostic only; never feeds back into state
            }
        }
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

    // ================================================================== snapshot

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
    {
        final long now = cluster.time();

        final BookSnapshotEncoder.PositionsEncoder positionsEncoder =
            bookSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                .nextQuoteId(nextQuoteId)
                .nextTradeId(nextTradeId)
                .nextPendingId(nextPendingId)
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
            if (credit.isKnown(clientId))
            {
                creditSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                    .clientId(clientId)
                    .tier(io.aeronfire.sbe.Tier.get((short)credit.tier(clientId).ordinal()))
                    .creditLimit(credit.limit(clientId))
                    .utilised(credit.utilised(clientId));
                offerSnapshot(
                    snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + creditSnapshotEncoder.encodedLength());
                clientsWritten++;
            }
        }

        for (int instrumentId = 0; instrumentId < Instruments.COUNT; instrumentId++)
        {
            inventorySnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                .instrumentId(instrumentId)
                .maxLong(inventory.maxLong(instrumentId))
                .maxShort(inventory.maxShort(instrumentId))
                .halted(inventory.isHalted(instrumentId) ? BooleanType.TRUE : BooleanType.FALSE);
            offerSnapshot(
                snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + inventorySnapshotEncoder.encodedLength());
        }

        algoSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
            .killSwitch(algo.killSwitch() ? BooleanType.TRUE : BooleanType.FALSE)
            .priceCollarBps(algo.priceCollarBps())
            .maxRfqsPerSecond(algo.maxRfqsPerSecond());
        offerSnapshot(snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + algoSnapshotEncoder.encodedLength());

        for (int user = 0; user < Entitlements.CAPACITY; user++)
        {
            if (!entitlements.isKnown(user))
            {
                continue;
            }
            final int clientCount = entitlements.clientCount(user);
            final EntitlementSnapshotEncoder.ClientsEncoder clients =
                entitlementSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                    .targetUserId(user)
                    .permissions(entitlements.permissions(user))
                    .allClients(entitlements.allClients(user) ? BooleanType.TRUE : BooleanType.FALSE)
                    .instrumentScope(entitlements.instrumentScope(user))
                    .maxCreditLimit(entitlements.maxCreditLimit(user))
                    .maxAdjust(entitlements.maxAdjust(user))
                    .maxInventoryLimit(entitlements.maxInventoryLimit(user))
                    .clientsCount(clientCount);
            for (int i = 0; i < clientCount; i++)
            {
                clients.next().clientId(entitlements.clientAt(user, i));
            }
            offerSnapshot(
                snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + entitlementSnapshotEncoder.encodedLength());
        }

        for (int slot = 0; slot < PendingChanges.CAPACITY; slot++)
        {
            if (pending.pendingIdAt(slot) >= 0)
            {
                pendingSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                    .pendingId(pending.pendingIdAt(slot))
                    .requestedBy(pending.requestedByAt(slot))
                    .templateId(pending.templateIdAt(slot))
                    .requestedAt(pending.requestedAtAt(slot))
                    .putCommand(pending.commandBytes(), slot * PendingChanges.MAX_COMMAND_LENGTH, pending.lengthAt(slot));
                offerSnapshot(
                    snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + pendingSnapshotEncoder.encodedLength());
            }
        }

        for (int clientId = 0; clientId < CreditBook.CAPACITY; clientId++)
        {
            if (!eligibility.isDefault(clientId))
            {
                eligibilitySnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder)
                    .clientId(clientId)
                    .kycValid(eligibility.kycValid(clientId) ? BooleanType.TRUE : BooleanType.FALSE)
                    .sanctioned(eligibility.sanctioned(clientId) ? BooleanType.TRUE : BooleanType.FALSE);
                offerSnapshot(
                    snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + eligibilitySnapshotEncoder.encodedLength());
            }
        }

        for (long seq = journal.oldest(); seq < journal.next(); seq++)
        {
            final int slot = journal.slotOf(seq);
            if (slot >= 0)
            {
                offerSnapshot(snapshotPublication, journalCodec.encodeSnapshot(egressBuffer, headerEncoder, journal, slot));
            }
        }

        for (int i = 0; i < subscriberCount; i++)
        {
            subscriberSnapshotEncoder.wrapAndApplyHeader(egressBuffer, 0, headerEncoder).clusterSessionId(subscribers[i]);
            offerSnapshot(
                snapshotPublication, MessageHeaderEncoder.ENCODED_LENGTH + subscriberSnapshotEncoder.encodedLength());
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
        credit.clear(); // the snapshot is authoritative; drop the demo seeds
        entitlements.clear();
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

        // The throttle's counters aren't snapshotted: rebuild them from the journalled RFQs, identically on every member.
        for (long seq = journal.oldest(); seq < journal.next(); seq++)
        {
            final int slot = journal.slotOf(seq);
            if (slot >= 0 && journal.typeAt(slot) == EventJournal.RFQ)
            {
                algo.replayRfq(journal.clientIdAt(slot), journal.timestampAt(slot));
            }
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
                nextPendingId = bookSnapshotDecoder.nextPendingId();
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
            case InventorySnapshotDecoder.TEMPLATE_ID ->
            {
                inventorySnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                inventory.set(
                    inventorySnapshotDecoder.instrumentId(),
                    inventorySnapshotDecoder.maxLong(),
                    inventorySnapshotDecoder.maxShort(),
                    inventorySnapshotDecoder.haltedRaw() == BooleanType.TRUE.value());
            }
            case AlgoControlsSnapshotDecoder.TEMPLATE_ID ->
            {
                algoSnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                algo.set(
                    algoSnapshotDecoder.killSwitchRaw() == BooleanType.TRUE.value(),
                    algoSnapshotDecoder.priceCollarBps(),
                    algoSnapshotDecoder.maxRfqsPerSecond());
            }
            case EntitlementSnapshotDecoder.TEMPLATE_ID ->
            {
                final EntitlementSnapshotDecoder d = entitlementSnapshotDecoder;
                d.wrap(buffer, bodyOffset, blockLength, version);
                final int user = d.targetUserId();
                entitlements.set(user, d.permissions(), d.allClientsRaw() == BooleanType.TRUE.value(),
                    d.instrumentScope(), d.maxCreditLimit(), d.maxAdjust(), d.maxInventoryLimit());
                for (final EntitlementSnapshotDecoder.ClientsDecoder c : d.clients())
                {
                    entitlements.addClient(user, c.clientId());
                }
            }
            case PendingChangeSnapshotDecoder.TEMPLATE_ID ->
            {
                final PendingChangeSnapshotDecoder d = pendingSnapshotDecoder;
                d.wrap(buffer, bodyOffset, blockLength, version);
                final long pendingId = d.pendingId();
                final int requestedBy = d.requestedBy();
                final int templateId = d.templateId();
                final long requestedAt = d.requestedAt();
                final int commandLength = d.getCommand(commandBuffer, 0, commandBuffer.capacity());
                pending.add(pendingId, requestedBy, templateId, requestedAt, commandBuffer, 0, commandLength);
            }
            case ClientEligibilitySnapshotDecoder.TEMPLATE_ID ->
            {
                eligibilitySnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                eligibility.set(
                    eligibilitySnapshotDecoder.clientId(),
                    eligibilitySnapshotDecoder.kycValidRaw() == BooleanType.TRUE.value(),
                    eligibilitySnapshotDecoder.sanctionedRaw() == BooleanType.TRUE.value());
            }
            case JournalEventSnapshotDecoder.TEMPLATE_ID ->
            {
                journalSnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                JournalCodec.restore(journalSnapshotDecoder, journal);
            }
            case SubscriberSnapshotDecoder.TEMPLATE_ID ->
            {
                subscriberSnapshotDecoder.wrap(buffer, bodyOffset, blockLength, version);
                if (subscriberCount < MAX_SUBSCRIBERS)
                {
                    subscribers[subscriberCount++] = subscriberSnapshotDecoder.clusterSessionId();
                }
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
        if (droppedCopies > 0)
        {
            System.out.printf("monitor drop copies dropped under back pressure: %d%n", droppedCopies);
        }
    }
}
