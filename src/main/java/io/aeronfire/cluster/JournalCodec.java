package io.aeronfire.cluster;

import io.aeronfire.domain.EventJournal;
import io.aeronfire.sbe.EventRecordEncoder;
import io.aeronfire.sbe.EventType;
import io.aeronfire.sbe.JournalEventSnapshotDecoder;
import io.aeronfire.sbe.JournalEventSnapshotEncoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.Side;
import org.agrona.MutableDirectBuffer;

/**
 * Encodes journal events onto the event feed and into snapshots, and restores them. Allocation-free.
 */
final class JournalCodec
{
    private final EventRecordEncoder record = new EventRecordEncoder();
    private final JournalEventSnapshotEncoder snapshot = new JournalEventSnapshotEncoder();

    /**
     * @return encoded length including the message header.
     */
    int encodeRecord(
        final MutableDirectBuffer buffer, final MessageHeaderEncoder header, final EventJournal journal, final int slot)
    {
        record.wrapAndApplyHeader(buffer, 0, header)
            .seq(journal.seqAt(slot))
            .eventType(EventType.get(journal.typeAt(slot)))
            .timestamp(journal.timestampAt(slot))
            .rfqId(journal.rfqIdAt(slot))
            .quoteId(journal.quoteIdAt(slot))
            .tradeId(journal.tradeIdAt(slot))
            .clientId(journal.clientIdAt(slot))
            .instrumentId(journal.instrumentIdAt(slot))
            .side(side(journal.sideAt(slot)))
            .notional(journal.notionalAt(slot))
            .price(journal.priceE6At(slot))
            .spreadBps(journal.spreadBpsAt(slot))
            .code((short)journal.codeAt(slot))
            .amount1(journal.amount1At(slot))
            .amount2(journal.amount2At(slot))
            .putVenueTxnId(journal.venueTxnIdBytes(), slot * EventJournal.VENUE_TXN_ID_LENGTH)
            .userId(journal.userIdAt(slot));
        return MessageHeaderEncoder.ENCODED_LENGTH + record.encodedLength();
    }

    int encodeSnapshot(
        final MutableDirectBuffer buffer, final MessageHeaderEncoder header, final EventJournal journal, final int slot)
    {
        snapshot.wrapAndApplyHeader(buffer, 0, header)
            .seq(journal.seqAt(slot))
            .eventType(EventType.get(journal.typeAt(slot)))
            .timestamp(journal.timestampAt(slot))
            .rfqId(journal.rfqIdAt(slot))
            .quoteId(journal.quoteIdAt(slot))
            .tradeId(journal.tradeIdAt(slot))
            .clientId(journal.clientIdAt(slot))
            .instrumentId(journal.instrumentIdAt(slot))
            .side(side(journal.sideAt(slot)))
            .notional(journal.notionalAt(slot))
            .price(journal.priceE6At(slot))
            .spreadBps(journal.spreadBpsAt(slot))
            .code((short)journal.codeAt(slot))
            .amount1(journal.amount1At(slot))
            .amount2(journal.amount2At(slot))
            .putVenueTxnId(journal.venueTxnIdBytes(), slot * EventJournal.VENUE_TXN_ID_LENGTH)
            .userId(journal.userIdAt(slot));
        return MessageHeaderEncoder.ENCODED_LENGTH + snapshot.encodedLength();
    }

    static void restore(final JournalEventSnapshotDecoder d, final EventJournal journal)
    {
        final int slot = journal.restore(d.seq(), (byte)d.eventTypeRaw(), d.timestamp());
        journal.ids(slot, d.rfqId(), d.quoteId(), d.tradeId());
        final short sideRaw = d.sideRaw();
        journal.order(slot, d.clientId(), d.instrumentId(), sideRaw == Side.NULL_VAL.value() ? -1 : sideRaw,
            d.notional(), d.price());
        journal.extra(slot, d.spreadBps(), d.code(), d.amount1(), d.amount2());
        d.getVenueTxnId(journal.venueTxnIdBytes(), slot * EventJournal.VENUE_TXN_ID_LENGTH);
        journal.user(slot, d.userId());
    }

    private static Side side(final int value)
    {
        return value < 0 ? Side.NULL_VAL : Side.get((short)value);
    }
}
