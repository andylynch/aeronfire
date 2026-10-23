package io.aeronfire.tools;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.domain.Instruments;
import io.aeronfire.domain.Pricer;
import io.aeronfire.sbe.BookSnapshotDecoder;
import io.aeronfire.sbe.JournalEventSnapshotDecoder;
import io.aeronfire.sbe.MessageHeaderDecoder;
import org.agrona.concurrent.BackoffIdleStrategy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rebuilds positions and risk as of a past point by replaying a cluster snapshot from the Aeron Archive.
 * <p>
 * The cluster records its snapshots in the archive. This replays the latest snapshot that carries our book,
 * reads the positions and the journal inside it, and rolls positions back to any sequence or time within
 * that journal, then revalues with the pricer: mark, CS01, DV01 and an optional spread shock. Nothing touches
 * the running cluster.
 * <p>
 * Env: CLUSTER_HOSTS, PORT_BASE, ARCHIVE_NODE (member to read from, default 0), REPLAY_ENDPOINT
 * (default localhost:19911, where the replay is delivered), ASOF_SEQ or ASOF_TIME (ISO instant),
 * SHOCK_BPS (spread shock, default 0).
 */
public final class RiskAsOf
{
    private static final int SCHEMA_ID = 701;
    private static final int SNAPSHOT_STREAM_ID = 106; // service snapshot; the consensus module uses 107
    private static final long STALL_MS = 3_000;
    private static final int MAX_ATTEMPTS = 5;

    private RiskAsOf()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));
        final int node = Integer.parseInt(env("ARCHIVE_NODE", "0"));
        final String replayEndpoint = env("REPLAY_ENDPOINT", "localhost:19911");
        final int shock = Integer.parseInt(env("SHOCK_BPS", "0"));
        final int archivePort = ClusterConfig.calculatePort(node, portBase, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET);

        try (MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
             AeronArchive archive = AeronArchive.connect(new AeronArchive.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .controlRequestChannel("aeron:udp?endpoint=" + hosts.get(node) + ":" + archivePort)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0")))
        {
            final List<long[]> recordings = new ArrayList<>(); // recordingId, stopPosition
            archive.listRecordings(0, 1_000,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp, startPosition,
                 stopPosition, initialTermId, segmentFileLength, termBufferLength, mtuLength, sessionId, streamId,
                 strippedChannel, originalChannel, sourceIdentity) ->
                {
                    if (System.getenv("DEBUG") != null)
                    {
                        System.out.println("rec " + recordingId + " stream " + streamId + " " + originalChannel + " stop " + stopPosition);
                    }
                    if (streamId == SNAPSHOT_STREAM_ID)
                    {
                        recordings.add(new long[]{recordingId, stopPosition});
                    }
                });
            System.out.println(recordings.size() + " snapshot recordings in the archive of member " + node);

            // Newest first: the service snapshot is the one carrying our schema.
            PositionHistory history = null;
            long usedRecording = -1;
            for (int i = recordings.size() - 1; i >= 0 && null == history; i--)
            {
                final PositionHistory h = replay(aeron, archive, recordings.get(i)[0], recordings.get(i)[1], replayEndpoint);
                if (h.nextSeq() > 0 || h.oldestSeq() > 0)
                {
                    history = h;
                    usedRecording = recordings.get(i)[0];
                }
            }
            if (null == history)
            {
                System.out.println("no snapshot with the aeronfire book found; take one first (control: takeSnapshot)");
                return;
            }

            long asOfSeq = history.nextSeq();
            final String seq = System.getenv("ASOF_SEQ");
            final String time = System.getenv("ASOF_TIME");
            if (null != seq && !seq.isBlank())
            {
                asOfSeq = Long.parseLong(seq.trim());
            }
            else if (null != time && !time.isBlank())
            {
                asOfSeq = history.seqAtTime(Instant.parse(time.trim()).toEpochMilli());
            }

            System.out.printf(Locale.ROOT, "snapshot recording %d, journal seq %d..%d; risk as of just before seq %d%s%n",
                usedRecording, history.oldestSeq(), history.nextSeq() - 1, asOfSeq, shock != 0 ? ", spread shock " + shock + "bp" : "");
            print(history.positionsBefore(asOfSeq), shock);
        }
    }

    /**
     * Replays a snapshot recording over UDP. A replay can stall part-way (flow control on a cold stream), so a
     * replay that makes no progress for a few seconds is stopped and started again.
     */
    private static PositionHistory replay(
        final Aeron aeron, final AeronArchive archive, final long recordingId, final long stopPosition,
        final String endpoint)
    {
        for (int attempt = 1; ; attempt++)
        {
            final PositionHistory history = tryReplay(aeron, archive, recordingId, stopPosition, endpoint);
            if (null != history)
            {
                return history;
            }
            if (attempt == MAX_ATTEMPTS)
            {
                throw new IllegalStateException("replay of recording " + recordingId + " stalled " + MAX_ATTEMPTS + " times");
            }
        }
    }

    private static PositionHistory tryReplay(
        final Aeron aeron, final AeronArchive archive, final long recordingId, final long stopPosition,
        final String endpoint)
    {
        final String channel = "aeron:udp?endpoint=" + endpoint;
        final PositionHistory history = new PositionHistory();
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final BookSnapshotDecoder book = new BookSnapshotDecoder();
        final JournalEventSnapshotDecoder event = new JournalEventSnapshotDecoder();
        final long[] consumed = {0};

        final FragmentHandler handler = (buffer, offset, length, h) ->
        {
            consumed[0] = Math.max(consumed[0], h.position());
            header.wrap(buffer, offset);
            if (header.schemaId() != SCHEMA_ID)
            {
                return; // consensus module snapshot frames use another schema
            }
            final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
            switch (header.templateId())
            {
                case BookSnapshotDecoder.TEMPLATE_ID ->
                {
                    book.wrap(buffer, body, header.blockLength(), header.version());
                    for (final BookSnapshotDecoder.PositionsDecoder p : book.positions())
                    {
                        if (Instruments.isValid(p.instrumentId()))
                        {
                            history.setSnapshotPosition(p.instrumentId(), p.netNotional());
                        }
                    }
                }
                case JournalEventSnapshotDecoder.TEMPLATE_ID ->
                {
                    event.wrap(buffer, body, header.blockLength(), header.version());
                    history.addEvent(event.seq(), event.timestamp(), (byte)event.eventTypeRaw(), event.instrumentId(),
                        event.sideRaw(), event.notional());
                }
                default ->
                {
                }
            }
        };

        try (Subscription subscription = aeron.addSubscription(channel, SNAPSHOT_STREAM_ID))
        {
            final long replaySession = archive.startReplay(recordingId, 0, AeronArchive.NULL_LENGTH, channel, SNAPSHOT_STREAM_ID);
            final BackoffIdleStrategy idle = new BackoffIdleStrategy();
            long lastProgress = consumed[0];
            long lastProgressAt = System.currentTimeMillis();
            while (consumed[0] < stopPosition)
            {
                idle.idle(subscription.poll(handler, 100));
                final long now = System.currentTimeMillis();
                if (consumed[0] != lastProgress)
                {
                    lastProgress = consumed[0];
                    lastProgressAt = now;
                }
                else if (now - lastProgressAt > STALL_MS)
                {
                    try
                    {
                        archive.stopReplay(replaySession);
                    }
                    catch (final RuntimeException ignore)
                    {
                        // already ended
                    }
                    return null;
                }
            }
            return history;
        }
    }

    static void print(final long[] net, final int shockBps)
    {
        final Pricer pricer = Pricer.defaults();
        System.out.printf(Locale.ROOT, "%-22s %14s %16s %12s %12s%s%n", "instrument", "net", "mark", "CS01", "DV01",
            shockBps != 0 ? "   shock P&L" : "");
        double totalMark = 0;
        double totalCs01 = 0;
        double totalDv01 = 0;
        double totalShock = 0;
        for (int i = 0; i < Instruments.COUNT; i++)
        {
            final int base = Instruments.baseSpreadBps(i);
            final double mark = pricer.mark(i, net[i]);
            final double cs01 = pricer.cs01(i, base, net[i]);
            final double dv01 = pricer.dv01(i, base, net[i]);
            final double shockPnl = (pricer.price(i, base + shockBps) - pricer.price(i, base)) / 100.0 * net[i];
            totalMark += mark;
            totalCs01 += cs01;
            totalDv01 += dv01;
            totalShock += shockPnl;
            System.out.printf(Locale.ROOT, "%-22s %14d %16.0f %12.0f %12.0f%s%n", Instruments.name(i), net[i], mark, cs01,
                dv01, shockBps != 0 ? String.format(Locale.ROOT, "   %10.0f", shockPnl) : "");
        }
        System.out.printf(Locale.ROOT, "%-22s %14s %16.0f %12.0f %12.0f%s%n", "total", "", totalMark, totalCs01, totalDv01,
            shockBps != 0 ? String.format(Locale.ROOT, "   %10.0f", totalShock) : "");
    }

    private static String env(final String name, final String def)
    {
        final String v = System.getenv(name);
        return null == v || v.isBlank() ? def : v;
    }
}
