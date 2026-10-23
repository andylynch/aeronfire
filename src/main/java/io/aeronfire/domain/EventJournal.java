package io.aeronfire.domain;

/**
 * The cluster's audit event journal: every state change, in sequence, so downstream consumers can pull a
 * guaranteed, gap-free feed. It is both the order and transaction record (MiFIR Art 25) and the source of
 * post-trade processing.
 * <p>
 * Sequence numbers are contiguous, so {@code seq & mask} gives the slot: a fixed ring of the latest
 * {@code capacity} events, structure-of-arrays, no allocation on append. Field use by type is documented on
 * the {@code EventRecord} message in the SBE schema. A consumer that falls further behind than the ring sees
 * a gap and must recover from the archived cluster log (roadmap); the archived log is the authoritative
 * long-term record either way.
 */
public final class EventJournal
{
    public static final int VENUE_TXN_ID_LENGTH = 32;

    // Event types, mirroring the SBE EventType enum.
    public static final byte RFQ = 0;
    public static final byte QUOTE = 1;
    public static final byte REJECT = 2;
    public static final byte TRADE = 3;
    public static final byte CREDIT_CHANGE = 4;
    public static final byte CREDIT_RESET = 5;
    public static final byte INVENTORY_CHANGE = 6;
    public static final byte POSITION_ADJUST = 7;
    public static final byte ALGO_CONTROLS = 8;
    public static final byte ENTITLEMENT_CHANGE = 9;
    public static final byte PENDING_CHANGE = 10;
    public static final byte APPROVAL = 11;
    public static final byte ELIGIBILITY_CHANGE = 12;

    private final int mask;
    private final long[] seq;
    private final byte[] type;
    private final long[] timestamp;
    private final long[] rfqId;
    private final long[] quoteId;
    private final long[] tradeId;
    private final int[] clientId;
    private final int[] instrumentId;
    private final byte[] side;
    private final long[] notional;
    private final long[] priceE6;
    private final int[] spreadBps;
    private final short[] code;
    private final long[] amount1;
    private final long[] amount2;
    private final byte[] venueTxnId;
    private final int[] userId;
    private long next;

    public EventJournal(final int capacityPowerOfTwo)
    {
        if (Integer.bitCount(capacityPowerOfTwo) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two: " + capacityPowerOfTwo);
        }
        mask = capacityPowerOfTwo - 1;
        seq = new long[capacityPowerOfTwo];
        type = new byte[capacityPowerOfTwo];
        timestamp = new long[capacityPowerOfTwo];
        rfqId = new long[capacityPowerOfTwo];
        quoteId = new long[capacityPowerOfTwo];
        tradeId = new long[capacityPowerOfTwo];
        clientId = new int[capacityPowerOfTwo];
        instrumentId = new int[capacityPowerOfTwo];
        side = new byte[capacityPowerOfTwo];
        notional = new long[capacityPowerOfTwo];
        priceE6 = new long[capacityPowerOfTwo];
        spreadBps = new int[capacityPowerOfTwo];
        code = new short[capacityPowerOfTwo];
        amount1 = new long[capacityPowerOfTwo];
        amount2 = new long[capacityPowerOfTwo];
        venueTxnId = new byte[capacityPowerOfTwo * VENUE_TXN_ID_LENGTH];
        userId = new int[capacityPowerOfTwo];
        java.util.Arrays.fill(seq, -1);
    }

    public int capacity()
    {
        return mask + 1;
    }

    /**
     * Start a new event at the next sequence number with every field cleared. Fill fields with the setters.
     *
     * @return the slot to fill.
     */
    public int append(final byte eventType, final long time)
    {
        final long s = next++;
        final int slot = (int)(s & mask);
        seq[slot] = s;
        type[slot] = eventType;
        timestamp[slot] = time;
        rfqId[slot] = -1;
        quoteId[slot] = -1;
        tradeId[slot] = -1;
        clientId[slot] = -1;
        instrumentId[slot] = -1;
        side[slot] = -1;
        notional[slot] = 0;
        priceE6[slot] = 0;
        spreadBps[slot] = 0;
        code[slot] = 0;
        amount1[slot] = 0;
        amount2[slot] = 0;
        userId[slot] = -1;
        java.util.Arrays.fill(venueTxnId, slot * VENUE_TXN_ID_LENGTH, (slot + 1) * VENUE_TXN_ID_LENGTH, (byte)0);
        return slot;
    }

    /**
     * Restore one event from a snapshot, at its original sequence number.
     */
    public int restore(final long sequence, final byte eventType, final long time)
    {
        next = sequence;
        return append(eventType, time);
    }

    public void ids(final int slot, final long rfq, final long quote, final long trade)
    {
        rfqId[slot] = rfq;
        quoteId[slot] = quote;
        tradeId[slot] = trade;
    }

    public void order(
        final int slot, final int client, final int instrument, final int sideCode, final long size, final long price)
    {
        clientId[slot] = client;
        instrumentId[slot] = instrument;
        side[slot] = (byte)sideCode;
        notional[slot] = size;
        priceE6[slot] = price;
    }

    public void extra(final int slot, final int spread, final int codeValue, final long a1, final long a2)
    {
        spreadBps[slot] = spread;
        code[slot] = (short)codeValue;
        amount1[slot] = a1;
        amount2[slot] = a2;
    }

    public void user(final int slot, final int user)
    {
        userId[slot] = user;
    }

    public int userIdAt(final int slot)
    {
        return userId[slot];
    }

    /**
     * Oldest sequence number still held.
     */
    public long oldest()
    {
        return Math.max(0, next - capacity());
    }

    /**
     * One past the newest sequence number.
     */
    public long next()
    {
        return next;
    }

    public int slotOf(final long sequence)
    {
        if (sequence < oldest() || sequence >= next)
        {
            return -1;
        }
        final int slot = (int)(sequence & mask);
        return seq[slot] == sequence ? slot : -1;
    }

    public long seqAt(final int slot)
    {
        return seq[slot];
    }

    public byte typeAt(final int slot)
    {
        return type[slot];
    }

    public long timestampAt(final int slot)
    {
        return timestamp[slot];
    }

    public long rfqIdAt(final int slot)
    {
        return rfqId[slot];
    }

    public long quoteIdAt(final int slot)
    {
        return quoteId[slot];
    }

    public long tradeIdAt(final int slot)
    {
        return tradeId[slot];
    }

    public int clientIdAt(final int slot)
    {
        return clientId[slot];
    }

    public int instrumentIdAt(final int slot)
    {
        return instrumentId[slot];
    }

    /**
     * SBE Side value, or -1 if not applicable.
     */
    public int sideAt(final int slot)
    {
        return side[slot];
    }

    public long notionalAt(final int slot)
    {
        return notional[slot];
    }

    public long priceE6At(final int slot)
    {
        return priceE6[slot];
    }

    public int spreadBpsAt(final int slot)
    {
        return spreadBps[slot];
    }

    public int codeAt(final int slot)
    {
        return code[slot];
    }

    public long amount1At(final int slot)
    {
        return amount1[slot];
    }

    public long amount2At(final int slot)
    {
        return amount2[slot];
    }

    /**
     * Backing bytes for venue ids; a slot's id starts at {@code slot * VENUE_TXN_ID_LENGTH}.
     */
    public byte[] venueTxnIdBytes()
    {
        return venueTxnId;
    }
}
