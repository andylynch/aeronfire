package io.aeronfire.client;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.QuoteAcceptEncoder;
import io.aeronfire.sbe.QuoteDecoder;
import io.aeronfire.sbe.RejectDecoder;
import io.aeronfire.sbe.RfqRequestEncoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.TradeDecoder;
import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Round-trip load client: sends an RFQ, waits for the quote, accepts it, waits for the trade.
 * Records RFQ-to-quote and accept-to-trade latency in HdrHistograms and prints percentiles.
 * <p>
 * Environment: {@code CLUSTER_HOSTS}, {@code PORT_BASE} (as for the nodes), {@code EGRESS_HOST}
 * (an address the cluster can reach this client on), {@code RFQ_COUNT}, {@code WARMUP_COUNT}.
 */
public final class RfqClient implements EgressListener
{
    private static final int NO_RESPONSE = 0;
    private static final int GOT_QUOTE = 1;
    private static final int GOT_TRADE = 2;
    private static final int GOT_REJECT = 3;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final QuoteDecoder quoteDecoder = new QuoteDecoder();
    private final TradeDecoder tradeDecoder = new TradeDecoder();
    private final RejectDecoder rejectDecoder = new RejectDecoder();
    private final RfqRequestEncoder rfqEncoder = new RfqRequestEncoder();
    private final QuoteAcceptEncoder acceptEncoder = new QuoteAcceptEncoder();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
    private final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

    private int response = NO_RESPONSE;
    private long lastQuoteId;
    private long trades;
    private long rejects;

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
                quoteDecoder.wrap(buffer, bodyOffset, blockLength, version);
                lastQuoteId = quoteDecoder.quoteId();
                response = GOT_QUOTE;
            }
            case TradeDecoder.TEMPLATE_ID ->
            {
                tradeDecoder.wrap(buffer, bodyOffset, blockLength, version);
                trades++;
                response = GOT_TRADE;
            }
            case RejectDecoder.TEMPLATE_ID ->
            {
                rejectDecoder.wrap(buffer, bodyOffset, blockLength, version);
                rejects++;
                response = GOT_REJECT;
            }
            default ->
            {
            }
        }
    }

    public static void main(final String[] args)
    {
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));
        final String egressHost = env("EGRESS_HOST", "localhost");
        final int count = Integer.parseInt(env("RFQ_COUNT", "20000"));
        final int warmup = Integer.parseInt(env("WARMUP_COUNT", "5000"));

        final RfqClient client = new RfqClient();
        final String ingressEndpoints =
            ClusterConfig.ingressEndpoints(hosts, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        try (MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
             AeronCluster cluster = AeronCluster.connect(new AeronCluster.Context()
                .egressListener(client)
                .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ingressEndpoints)
                .aeronDirectoryName(driver.aeronDirectoryName())))
        {
            System.out.printf("connected, leader member %d%n", cluster.leaderMemberId());
            client.run(cluster, warmup, null, null);

            final Histogram rfqToQuote = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
            final Histogram acceptToTrade = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
            client.run(cluster, count, rfqToQuote, acceptToTrade);

            print("RFQ -> quote", rfqToQuote);
            print("accept -> trade", acceptToTrade);
            System.out.printf("trades %d, rejects %d%n", client.trades, client.rejects);
        }
    }

    private void run(
        final AeronCluster cluster, final int count, final Histogram rfqToQuote, final Histogram acceptToTrade)
    {
        for (int i = 0; i < count; i++)
        {
            final long rfqId = i;
            rfqEncoder.wrapAndApplyHeader(sendBuffer, 0, headerEncoder)
                .rfqId(rfqId)
                .instrumentId(i % 5)
                .side((i & 1) == 0 ? Side.BUY : Side.SELL)
                .notional(5_000_000L);

            long start = System.nanoTime();
            send(cluster, MessageHeaderEncoder.ENCODED_LENGTH + rfqEncoder.encodedLength());
            if (await(cluster) != GOT_QUOTE)
            {
                continue;
            }
            record(rfqToQuote, start);

            acceptEncoder.wrapAndApplyHeader(sendBuffer, 0, headerEncoder)
                .rfqId(rfqId)
                .quoteId(lastQuoteId);

            start = System.nanoTime();
            send(cluster, MessageHeaderEncoder.ENCODED_LENGTH + acceptEncoder.encodedLength());
            if (await(cluster) == GOT_TRADE)
            {
                record(acceptToTrade, start);
            }
        }
    }

    private void send(final AeronCluster cluster, final int length)
    {
        response = NO_RESPONSE;
        idleStrategy.reset();
        while (cluster.offer(sendBuffer, 0, length) < 0)
        {
            if (cluster.isClosed())
            {
                throw new IllegalStateException("cluster session closed");
            }
            idleStrategy.idle(cluster.pollEgress());
        }
    }

    private int await(final AeronCluster cluster)
    {
        idleStrategy.reset();
        while (response == NO_RESPONSE)
        {
            idleStrategy.idle(cluster.pollEgress());
        }
        return response;
    }

    private static void record(final Histogram histogram, final long startNs)
    {
        if (null != histogram)
        {
            histogram.recordValue(System.nanoTime() - startNs);
        }
    }

    private static void print(final String label, final Histogram h)
    {
        System.out.printf("%-16s n=%d  p50=%.1fus  p99=%.1fus  p99.9=%.1fus  max=%.1fus%n",
            label,
            h.getTotalCount(),
            h.getValueAtPercentile(50) / 1000.0,
            h.getValueAtPercentile(99) / 1000.0,
            h.getValueAtPercentile(99.9) / 1000.0,
            h.getMaxValue() / 1000.0);
    }

    private static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }
}
