package io.aeronfire.bench;

import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.QuoteDecoder;
import io.aeronfire.sbe.QuoteEncoder;
import io.aeronfire.sbe.RfqRequestDecoder;
import io.aeronfire.sbe.RfqRequestEncoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.TradeDecoder;
import io.aeronfire.sbe.TradeEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * SBE encode and decode of the three hot-path messages, into and out of a direct buffer. Decoders read every
 * field and fold them into the result so nothing is dead code. Run with {@code -prof gc} to confirm the
 * hot path allocates nothing.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"})
public class CodecBenchmark
{
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final RfqRequestEncoder rfqEncoder = new RfqRequestEncoder();
    private final RfqRequestDecoder rfqDecoder = new RfqRequestDecoder();
    private final QuoteEncoder quoteEncoder = new QuoteEncoder();
    private final QuoteDecoder quoteDecoder = new QuoteDecoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final TradeDecoder tradeDecoder = new TradeDecoder();
    private long n;

    @Setup
    public void setup()
    {
        // One message of each kind at its own offset so the decode benchmarks have something to read.
        encodeRfq(0);
        encodeQuote(64);
        encodeTrade(160);
    }

    private int encodeRfq(final int offset)
    {
        rfqEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .rfqId(n++)
            .clientId((int)(n & 7))
            .instrumentId((int)(n % 5))
            .side((n & 1) == 0 ? Side.BUY : Side.SELL)
            .notional(2_000_000L);
        return MessageHeaderEncoder.ENCODED_LENGTH + rfqEncoder.encodedLength();
    }

    private int encodeQuote(final int offset)
    {
        quoteEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .rfqId(n)
            .quoteId(n)
            .clientId(3)
            .instrumentId(2)
            .side(Side.BUY)
            .notional(2_000_000L)
            .price(97_250_000L)
            .spreadBps(612)
            .validUntil(n + 5_000);
        return MessageHeaderEncoder.ENCODED_LENGTH + quoteEncoder.encodedLength();
    }

    private int encodeTrade(final int offset)
    {
        tradeEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .tradeId(n)
            .rfqId(n)
            .quoteId(n)
            .clientId(3)
            .instrumentId(2)
            .side(Side.BUY)
            .notional(2_000_000L)
            .price(97_250_000L)
            .executedAt(n)
            .clientUtilised(4_000_000L);
        return MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength();
    }

    @Benchmark
    public int encodeRfqRequest()
    {
        return encodeRfq(0);
    }

    @Benchmark
    public int encodeQuote()
    {
        n++;
        return encodeQuote(64);
    }

    @Benchmark
    public int encodeTrade()
    {
        n++;
        return encodeTrade(160);
    }

    @Benchmark
    public long decodeRfqRequest()
    {
        headerDecoder.wrap(buffer, 0);
        rfqDecoder.wrap(buffer, MessageHeaderDecoder.ENCODED_LENGTH, headerDecoder.blockLength(), headerDecoder.version());
        return rfqDecoder.rfqId() + rfqDecoder.clientId() + rfqDecoder.instrumentId() + rfqDecoder.sideRaw() +
            rfqDecoder.notional();
    }

    @Benchmark
    public long decodeQuote()
    {
        headerDecoder.wrap(buffer, 64);
        quoteDecoder.wrap(buffer, 64 + MessageHeaderDecoder.ENCODED_LENGTH, headerDecoder.blockLength(), headerDecoder.version());
        return quoteDecoder.rfqId() + quoteDecoder.quoteId() + quoteDecoder.clientId() + quoteDecoder.instrumentId() +
            quoteDecoder.sideRaw() + quoteDecoder.notional() + quoteDecoder.price() + quoteDecoder.spreadBps() +
            quoteDecoder.validUntil();
    }

    @Benchmark
    public long decodeTrade()
    {
        headerDecoder.wrap(buffer, 160);
        tradeDecoder.wrap(buffer, 160 + MessageHeaderDecoder.ENCODED_LENGTH, headerDecoder.blockLength(), headerDecoder.version());
        return tradeDecoder.tradeId() + tradeDecoder.rfqId() + tradeDecoder.quoteId() + tradeDecoder.clientId() +
            tradeDecoder.instrumentId() + tradeDecoder.sideRaw() + tradeDecoder.notional() + tradeDecoder.price() +
            tradeDecoder.executedAt() + tradeDecoder.clientUtilised();
    }

    /**
     * The service's per-RFQ codec work: decode the request, encode the quote.
     */
    @Benchmark
    public long rfqInQuoteOut()
    {
        final long sum = decodeRfqRequest();
        n++;
        return sum + encodeQuote(64);
    }
}
