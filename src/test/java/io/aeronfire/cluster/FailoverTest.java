package io.aeronfire.cluster;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.domain.AlgoControls;
import io.aeronfire.sbe.AlgoControlsEncoder;
import io.aeronfire.sbe.BooleanType;
import io.aeronfire.sbe.CreditResetEncoder;
import io.aeronfire.sbe.EventFeedBatchEndDecoder;
import io.aeronfire.sbe.EventFeedRequestEncoder;
import io.aeronfire.sbe.EventRecordDecoder;
import io.aeronfire.sbe.EventType;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.QuoteAcceptEncoder;
import io.aeronfire.sbe.QuoteDecoder;
import io.aeronfire.sbe.RfqRequestEncoder;
import io.aeronfire.sbe.Side;
import io.aeronfire.sbe.TradeDecoder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failover test: three real cluster members in separate JVMs, a client trading continuously, and the leader
 * killed with SIGKILL part-way through. Afterwards the new leader's journal is read back and checked against
 * what the client saw: every trade the client was told about exists exactly once, no quote was filled twice,
 * and the journal has no gaps. Slow (about a minute), so it runs as its own Gradle task, not with {@code test}.
 */
@Tag("failover")
class FailoverTest
{
    private static final int PORT_BASE = 19000;
    private static final int CYCLES = 300;
    private static final int KILL_AT = 120;
    private static final long STEP_TIMEOUT_MS = 4_000;
    private static final List<String> JVM_ARGS = List.of(
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.net.preferIPv4Stack=true");

    /**
     * What the client has seen on egress, keyed so late replies to a resent request can't be mistaken.
     */
    private static final class Listener implements EgressListener
    {
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final QuoteDecoder quote = new QuoteDecoder();
        final TradeDecoder trade = new TradeDecoder();
        final EventRecordDecoder record = new EventRecordDecoder();
        final EventFeedBatchEndDecoder batchEnd = new EventFeedBatchEndDecoder();
        final Map<Long, Long> quoteByRfq = new HashMap<>();
        final Map<Long, Long> tradeByQuote = new HashMap<>();
        final io.aeronfire.sbe.RejectDecoder reject = new io.aeronfire.sbe.RejectDecoder();
        final Set<String> rejected = new HashSet<>();
        final List<long[]> journalTrades = new ArrayList<>(); // seq, tradeId, quoteId
        final List<Long> journalSeqs = new ArrayList<>();
        long batchNext = -1;
        int batchCount = -1;

        @Override
        public void onMessage(
            final long sessionId, final long timestamp, final DirectBuffer buffer, final int offset, final int length,
            final Header h)
        {
            header.wrap(buffer, offset);
            final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
            final int bl = header.blockLength();
            final int v = header.version();
            switch (header.templateId())
            {
                case QuoteDecoder.TEMPLATE_ID ->
                {
                    quote.wrap(buffer, body, bl, v);
                    quoteByRfq.put(quote.rfqId(), quote.quoteId());
                }
                case TradeDecoder.TEMPLATE_ID ->
                {
                    trade.wrap(buffer, body, bl, v);
                    tradeByQuote.put(trade.quoteId(), trade.tradeId());
                }
                case io.aeronfire.sbe.RejectDecoder.TEMPLATE_ID ->
                {
                    reject.wrap(buffer, body, bl, v);
                    rejected.add((reject.rejectedTemplateId() == io.aeronfire.sbe.QuoteAcceptDecoder.TEMPLATE_ID ? "acc" : "rfq") +
                        reject.correlationId());
                }
                case EventRecordDecoder.TEMPLATE_ID ->
                {
                    record.wrap(buffer, body, bl, v);
                    journalSeqs.add(record.seq());
                    if (record.eventTypeRaw() == EventType.TRADE.value())
                    {
                        journalTrades.add(new long[]{record.seq(), record.tradeId(), record.quoteId()});
                    }
                }
                case EventFeedBatchEndDecoder.TEMPLATE_ID ->
                {
                    batchEnd.wrap(buffer, body, bl, v);
                    batchCount = batchEnd.count();
                    batchNext = batchEnd.fromSeq() + batchEnd.count();
                }
                default ->
                {
                }
            }
        }
    }

    private final Listener listener = new Listener();
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private AeronCluster cluster;
    private MediaDriver driver;

    @Test
    void killingTheLeaderMidFlowLosesAndDuplicatesNothing() throws Exception
    {
        final Path dir = Files.createTempDirectory("aeronfire-failover");
        final List<Process> nodes = new ArrayList<>();
        try
        {
            for (int i = 0; i < 3; i++)
            {
                nodes.add(startNode(i, dir));
            }
            driver = MediaDriver.launchEmbedded(new MediaDriver.Context().threadingMode(ThreadingMode.SHARED)
                .aeronDirectoryName(dir.resolve("client-driver").toString()).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
            connect(60_000);
            lift();

            final Set<Long> ambiguous = new HashSet<>(); // accept sent, outcome unknown across the failover
            final Set<Long> acked = new HashSet<>();     // quote ids the client was told were filled
            final Map<Long, Long> rfqOfQuote = new HashMap<>();
            boolean killed = false;
            int leaderKilled = -1;

            for (int i = 0; i < CYCLES; i++)
            {
                if (i % 40 == 0)
                {
                    resetCredit();
                }
                final long rfqId = 1_000 + i;
                final Long quoteId = quote(rfqId, i);
                if (null == quoteId)
                {
                    continue; // rejected (tier/credit) or no reply even after resends: nothing was sent to accept
                }
                final boolean kill = i == KILL_AT;
                final int[] killedLeader = {-1};
                final int outcome = accept(rfqId, quoteId, kill ? () ->
                {
                    // Mid-flow: the accept is on the wire, the reply not yet read.
                    killedLeader[0] = cluster.leaderMemberId();
                    nodes.get(killedLeader[0]).destroyForcibly();
                } : null);
                if (kill)
                {
                    leaderKilled = killedLeader[0];
                    killed = true;
                }
                if (outcome == 1)
                {
                    acked.add(quoteId);
                    rfqOfQuote.put(quoteId, rfqId);
                }
                else if (outcome == 0)
                {
                    ambiguous.add(quoteId);
                }
            }
            assertTrue(killed && leaderKilled >= 0, "the leader was killed mid-flow");
            assertTrue(acked.size() > 50, "trading made progress across the failover: " + acked.size());

            readJournal();

            final Set<Long> filledQuotes = new HashSet<>();
            final Set<Long> tradeIds = new HashSet<>();
            for (final long[] t : listener.journalTrades)
            {
                assertTrue(filledQuotes.add(t[2]), "quote " + t[2] + " filled twice");
                assertTrue(tradeIds.add(t[1]), "trade id " + t[1] + " duplicated");
            }
            for (final long q : acked)
            {
                assertTrue(filledQuotes.contains(q), "client was told quote " + q + " traded but the journal has no trade");
            }
            final Set<Long> unexplained = new HashSet<>(filledQuotes);
            unexplained.removeAll(acked);
            unexplained.removeAll(ambiguous);
            // Every journal trade is one the client was told about, or one whose reply never came at all.
            assertTrue(unexplained.isEmpty(), "trades nobody asked for: " + unexplained);

            // Idempotency: resending an accept for a filled quote replays the same trade and executes nothing new.
            final long replayQuote = acked.iterator().next();
            final long firstTradeId = listener.tradeByQuote.get(replayQuote);
            final int tradesBefore = listener.journalTrades.size();
            listener.tradeByQuote.remove(replayQuote);
            assertEquals(1, accept(rfqOfQuote.get(replayQuote), replayQuote, null), "resent accept is answered with the trade");
            assertEquals(firstTradeId, (long)listener.tradeByQuote.get(replayQuote), "same trade id replayed");
            listener.journalTrades.clear();
            listener.journalSeqs.clear();
            readJournal();
            assertEquals(tradesBefore, listener.journalTrades.size(), "the replay did not execute a second trade");

            for (int i = 1; i < listener.journalSeqs.size(); i++)
            {
                assertEquals((long)listener.journalSeqs.get(i - 1) + 1, (long)listener.journalSeqs.get(i), "journal gap at index " + i);
            }
            assertEquals(0L, (long)listener.journalSeqs.get(0), "journal starts at sequence 0");
            System.out.printf("failover ok: leader %d killed at cycle %d, %d acked, %d ambiguous, %d trades in journal%n",
                leaderKilled, KILL_AT, acked.size(), ambiguous.size(), listener.journalTrades.size());
        }
        finally
        {
            if (null != cluster)
            {
                cluster.close();
            }
            if (null != driver)
            {
                driver.close();
            }
            nodes.forEach(Process::destroyForcibly);
        }
    }

    // ------------------------------------------------------------------ cluster plumbing

    private static Process startNode(final int id, final Path dir) throws IOException
    {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(JVM_ARGS);
        if (Runtime.version().feature() >= 24)
        {
            command.add("--sun-misc-unsafe-memory-access=allow");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("io.aeronfire.cluster.ClusterNode");
        final ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
            .redirectErrorStream(true).redirectOutput(new File(dir.toFile(), "node" + id + ".log"));
        pb.environment().put("NODE_ID", Integer.toString(id));
        pb.environment().put("PORT_BASE", Integer.toString(PORT_BASE));
        return pb.start();
    }

    private void connect(final long timeoutMs) throws InterruptedException
    {
        final String ingress = ClusterConfig.ingressEndpoints(
            List.of("localhost", "localhost", "localhost"), PORT_BASE, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        final long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                cluster = AeronCluster.connect(new AeronCluster.Context()
                    .egressListener(listener)
                    .egressChannel("aeron:udp?endpoint=localhost:0")
                    .ingressChannel("aeron:udp")
                    .ingressEndpoints(ingress)
                    .messageTimeoutNs(java.util.concurrent.TimeUnit.SECONDS.toNanos(5))
                    .aeronDirectoryName(driver.aeronDirectoryName())
                    .credentialsSupplier(ClusterAuthenticator.controlCredentialsSupplier(ClusterAuthenticator.DEFAULT_SECRET)));
                return;
            }
            catch (final RuntimeException ex)
            {
                last = ex;
                Thread.sleep(1_000);
            }
        }
        throw new IllegalStateException("could not connect to the cluster", last);
    }

    private boolean send(final int length)
    {
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (cluster.isClosed())
            {
                try
                {
                    connect(30_000);
                }
                catch (final InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (cluster.offer(buffer, 0, length) > 0)
            {
                return true;
            }
            cluster.pollEgress();
            Thread.onSpinWait();
        }
        return false;
    }

    private interface Done
    {
        boolean test();
    }

    private boolean await(final Done done)
    {
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            cluster.pollEgress();
            if (done.test())
            {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private void lift()
    {
        final AlgoControlsEncoder e = new AlgoControlsEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .killSwitch(BooleanType.FALSE).priceCollarBps(AlgoControls.DEFAULT_PRICE_COLLAR_BPS).maxRfqsPerSecond(0).userId(0);
        send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    private void resetCredit()
    {
        final CreditResetEncoder e = new CreditResetEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder).clientId(-1).userId(0);
        send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    /**
     * Send an RFQ, resending the same RFQ id if no answer arrives (as a real client would after a leader
     * change). Returns the quote id, or null if none came.
     */
    private Long quote(final long rfqId, final int i)
    {
        for (int attempt = 0; attempt < 4; attempt++)
        {
            final RfqRequestEncoder e = new RfqRequestEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
                .rfqId(rfqId).clientId(i % 5).instrumentId(i % 5)
                .side((i & 1) == 0 ? Side.BUY : Side.SELL).notional(2_000_000L);
            if (send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()))
            {
                if (await(() -> listener.quoteByRfq.containsKey(rfqId) || listener.rejected.contains("rfq" + rfqId)))
                {
                    if (listener.rejected.contains("rfq" + rfqId))
                    {
                        return null;
                    }
                    return listener.quoteByRfq.get(rfqId);
                }
                if (listener.rejected.contains("rfq" + rfqId))
                {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * @return 1 traded, 0 unknown (no answer after resends), -1 definitely not traded (reply was a reject).
     */
    private int accept(final long rfqId, final long quoteId, final Runnable afterFirstSend)
    {
        for (int attempt = 0; attempt < 5; attempt++)
        {
            final QuoteAcceptEncoder e = new QuoteAcceptEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
                .rfqId(rfqId).quoteId(quoteId);
            if (send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()))
            {
                if (attempt == 0 && null != afterFirstSend)
                {
                    afterFirstSend.run();
                }
                if (await(() -> listener.tradeByQuote.containsKey(quoteId) || listener.rejected.contains("acc" + quoteId)))
                {
                    if (listener.tradeByQuote.containsKey(quoteId))
                    {
                        return 1;
                    }
                    // The cluster replays the Trade for an already-filled quote, so a reject really means no fill.
                    return -1;
                }
            }
        }
        return 0;
    }

    /**
     * Read the whole journal from sequence 0 through the (new) leader.
     */
    private void readJournal() throws InterruptedException
    {
        long from = 0;
        for (int guard = 0; guard < 200; guard++)
        {
            listener.batchNext = -1;
            final EventFeedRequestEncoder e = new EventFeedRequestEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
                .fromSeq(from).maxEvents(512);
            send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
            if (!await(() -> listener.batchNext >= 0))
            {
                continue; // resend
            }
            if (listener.batchCount == 0)
            {
                return;
            }
            from = listener.batchNext;
        }
        throw new IllegalStateException("journal read did not complete");
    }
}
