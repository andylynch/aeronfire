package io.aeronfire.posttrade;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeronfire.control.Users;
import io.aeronfire.sbe.EventFeedBatchEndDecoder;
import io.aeronfire.sbe.EventFeedRequestEncoder;
import io.aeronfire.sbe.EventRecordDecoder;
import io.aeronfire.sbe.EventType;
import io.aeronfire.sbe.MessageHeaderDecoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.RejectDecoder;
import io.aeronfire.sbe.RejectReason;
import io.aeronfire.sbe.Side;
import io.aeronfire.util.Json;
import io.aeronfire.util.ShutdownFlag;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Post-trade service. Pulls the cluster's guaranteed event feed by sequence number (so nothing is missed
 * across restarts or failovers), checkpoints the next sequence, and feeds trades to {@link PostTrade}.
 * Operators repair, resubmit and replay over HTTP, bound to their logged-in user.
 * <p>
 * Env: CLUSTER_HOSTS, PORT_BASE, EGRESS_HOST as for the other clients; DATA_DIR (./data);
 * REFDATA_URL (http://localhost:8095); POSTTRADE_HTTP_PORT (8096).
 */
public final class PostTradeService implements EgressListener
{
    private static final int BATCH = 512;

    private record ReplayRequest(String user, long from, long to, CompletableFuture<String> result)
    {
    }

    private final PostTrade postTrade;
    private final Path checkpointFile;
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final EventRecordDecoder record = new EventRecordDecoder();
    private final EventFeedBatchEndDecoder batchEnd = new EventFeedBatchEndDecoder();
    private final RejectDecoder reject = new RejectDecoder();
    private final UnsafeBuffer sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final List<TradeEvent> batch = new ArrayList<>();
    private final ConcurrentLinkedQueue<ReplayRequest> replays = new ConcurrentLinkedQueue<>();

    private long nextSeq;
    private boolean outstanding;
    private long lastRequestMs;
    private ReplayRequest replay;
    private long replayNext;
    private final List<TradeEvent> replayed = new ArrayList<>();
    private volatile long feedLag = -1;

    PostTradeService(final PostTrade postTrade, final Path checkpointFile) throws IOException
    {
        this.postTrade = postTrade;
        this.checkpointFile = checkpointFile;
        nextSeq = Files.exists(checkpointFile) ? Long.parseLong(Files.readString(checkpointFile).trim()) : 0;
    }

    // ------------------------------------------------------------------ egress

    @Override
    public void onMessage(
        final long clusterSessionId, final long timestamp, final DirectBuffer buffer, final int offset, final int length,
        final Header h)
    {
        header.wrap(buffer, offset);
        final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int bl = header.blockLength();
        final int v = header.version();
        switch (header.templateId())
        {
            case EventRecordDecoder.TEMPLATE_ID ->
            {
                record.wrap(buffer, body, bl, v);
                if (record.eventTypeRaw() == EventType.TRADE.value())
                {
                    final byte[] tvtic = new byte[EventRecordDecoder.venueTxnIdLength()];
                    record.getVenueTxnId(tvtic, 0);
                    batch.add(new TradeEvent(record.seq(), record.timestamp(), record.tradeId(), record.rfqId(),
                        record.quoteId(), record.clientId(), record.instrumentId(), record.sideRaw() == Side.BUY.value(),
                        record.notional(), record.price(),
                        new String(tvtic, StandardCharsets.US_ASCII).replace("\0", "").trim()));
                }
            }
            case EventFeedBatchEndDecoder.TEMPLATE_ID ->
            {
                batchEnd.wrap(buffer, body, bl, v);
                onBatchEnd(batchEnd.fromSeq(), batchEnd.count(), batchEnd.nextSeq());
            }
            case RejectDecoder.TEMPLATE_ID ->
            {
                reject.wrap(buffer, body, bl, v);
                if (reject.reason() == RejectReason.JOURNAL_GAP)
                {
                    onGap(reject.correlationId());
                }
            }
            default ->
            {
            }
        }
    }

    private void onBatchEnd(final long fromSeq, final int count, final long clusterNext)
    {
        outstanding = false;
        feedLag = clusterNext - (fromSeq + count);
        if (null != replay)
        {
            replayed.addAll(batch.stream().filter((t) -> t.seq() <= replay.to()).toList());
            replayNext = fromSeq + count;
            if (replayNext > replay.to() || count == 0)
            {
                replay.result().complete(postTrade.replay(replay.user(), new ArrayList<>(replayed)));
                replay = null;
                replayed.clear();
            }
        }
        else
        {
            for (final TradeEvent t : batch)
            {
                postTrade.process(t);
            }
            nextSeq = fromSeq + count;
            checkpoint();
        }
        batch.clear();
    }

    private void onGap(final long oldest)
    {
        outstanding = false;
        batch.clear();
        if (null != replay)
        {
            replay.result().complete("REJECTED: range starts before the cluster's retained journal (oldest " + oldest + ")");
            replay = null;
            replayed.clear();
            return;
        }
        // Events between our checkpoint and the oldest retained one are gone from the cluster. That's a
        // recordable incident: the missing trades have to be recovered from the cluster's snapshots/archive.
        postTrade.keeper().append("JOURNAL_GAP", -1, "system", "{\"wantedFrom\":" + nextSeq + ",\"oldest\":" + oldest + "}");
        System.out.println("JOURNAL GAP: wanted " + nextSeq + ", oldest available " + oldest);
        nextSeq = oldest;
        checkpoint();
    }

    private void checkpoint()
    {
        try
        {
            Files.writeString(checkpointFile, Long.toString(nextSeq));
        }
        catch (final IOException ex)
        {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    // ------------------------------------------------------------------ loop

    private void run(final AeronCluster cluster, final ShutdownFlag running)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        long lastKeepAlive = 0;
        while (running.isRunning() && !cluster.isClosed())
        {
            int work = cluster.pollEgress();
            final long now = System.currentTimeMillis();
            if (null == replay && !replays.isEmpty())
            {
                replay = replays.poll();
                replayNext = replay.from();
                replayed.clear();
                outstanding = false;
            }
            if (!outstanding && now - lastRequestMs >= (feedLag > 0 || null != replay ? 0 : 250))
            {
                final long from = null != replay ? replayNext : nextSeq;
                final EventFeedRequestEncoder e = new EventFeedRequestEncoder()
                    .wrapAndApplyHeader(sendBuffer, 0, new MessageHeaderEncoder()).fromSeq(from).maxEvents(BATCH);
                final int len = MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
                if (cluster.offer(sendBuffer, 0, len) > 0)
                {
                    outstanding = true;
                    lastRequestMs = now;
                    work++;
                }
            }
            if (outstanding && now - lastRequestMs > 5_000)
            {
                outstanding = false; // reply lost (failover): ask again from the same sequence
            }
            if (now - lastKeepAlive >= 1_000)
            {
                cluster.sendKeepAlive();
                lastKeepAlive = now;
            }
            idle.idle(work);
        }
    }

    // ------------------------------------------------------------------ HTTP

    private HttpServer serve(final int port, final Users users) throws IOException
    {
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        final BasicAuthenticator auth = new BasicAuthenticator("aeronfire post-trade")
        {
            @Override
            public boolean checkCredentials(final String user, final String password)
            {
                return users.check(user, password);
            }
        };
        server.createContext("/api/state", (ex) -> send(ex, 200, "application/json",
            "{\"nextSeq\":" + nextSeq + ",\"feedLag\":" + feedLag + "," + postTrade.json().substring(1))).setAuthenticator(auth);
        server.createContext("/api/records", this::records).setAuthenticator(auth);
        server.createContext("/api/verify", (ex) ->
        {
            final String broken = postTrade.keeper().verify();
            send(ex, 200, "application/json", "{\"intact\":" + (null == broken) + ",\"records\":" +
                postTrade.keeper().size() + ",\"detail\":" + Json.quote(broken) + "}");
        }).setAuthenticator(auth);
        server.createContext("/api/repair", (ex) ->
        {
            final Map<String, String> p = form(ex);
            send(ex, 200, "text/plain", postTrade.repair(user(ex), Long.parseLong(p.getOrDefault("item", "-1")),
                p.getOrDefault("field", ""), p.getOrDefault("value", ""), p.get("reason")));
        }).setAuthenticator(auth);
        server.createContext("/api/resubmit", (ex) ->
            send(ex, 200, "text/plain", postTrade.resubmit(user(ex), Long.parseLong(form(ex).getOrDefault("item", "-1")))))
            .setAuthenticator(auth);
        server.createContext("/api/replay", (ex) ->
        {
            final Map<String, String> p = form(ex);
            final ReplayRequest r = new ReplayRequest(user(ex), Long.parseLong(p.getOrDefault("from", "0")),
                Long.parseLong(p.getOrDefault("to", "0")), new CompletableFuture<>());
            replays.add(r);
            String result;
            try
            {
                result = r.result().get(15, TimeUnit.SECONDS);
            }
            catch (final Exception e)
            {
                result = "UNKNOWN: replay did not finish in 15s";
            }
            send(ex, 200, "text/plain", result);
        }).setAuthenticator(auth);
        server.start();
        return server;
    }

    private void records(final HttpExchange ex) throws IOException
    {
        final StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        final var all = postTrade.keeper().read();
        for (final var r : all.subList(Math.max(0, all.size() - 200), all.size()))
        {
            sb.append(first ? "" : ",").append("{\"n\":").append(r.n()).append(",\"at\":").append(Json.quote(r.at()))
                .append(",\"type\":").append(Json.quote(r.type())).append(",\"trade\":").append(r.tradeId())
                .append(",\"user\":").append(Json.quote(r.user())).append(",\"retainUntil\":").append(Json.quote(r.retainUntil()))
                .append(",\"data\":").append(r.data()).append('}');
            first = false;
        }
        send(ex, 200, "application/json", sb.append(']').toString());
    }

    private static String user(final HttpExchange ex)
    {
        return ex.getPrincipal().getUsername();
    }

    private static Map<String, String> form(final HttpExchange ex) throws IOException
    {
        final Map<String, String> params = new HashMap<>();
        for (final String pair : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&"))
        {
            final int eq = pair.indexOf('=');
            if (eq > 0)
            {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static void send(final HttpExchange ex, final int status, final String type, final String body) throws IOException
    {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody())
        {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(final String[] args) throws Exception
    {
        final List<String> hosts = List.of(env("CLUSTER_HOSTS", "localhost,localhost,localhost").split(","));
        final int portBase = Integer.parseInt(env("PORT_BASE", "9000"));
        final String egressHost = env("EGRESS_HOST", "localhost");
        final Path dataDir = Path.of(env("DATA_DIR", "data"));
        Files.createDirectories(dataDir);

        final PostTrade pt = new PostTrade(new HttpRefLookup(env("REFDATA_URL", "http://localhost:8095"),
            env("REFDATA_PASSWORD", "refdata")), new RecordKeeper(dataDir.resolve("records.jsonl")));
        System.out.println("restored " + pt.restore() + " trades from the record log");
        final PostTradeService service = new PostTradeService(pt, dataDir.resolve("checkpoint"));
        final ShutdownFlag running = ShutdownFlag.register();

        try (MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             AeronCluster cluster = AeronCluster.connect(new AeronCluster.Context()
                .egressListener(service)
                .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ClusterConfig.ingressEndpoints(hosts, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                .aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final HttpServer http = service.serve(Integer.parseInt(env("POSTTRADE_HTTP_PORT", "8096")),
                new Users(System.getenv("CONTROL_USERS")));
            System.out.println("post-trade up: resuming feed at seq " + service.nextSeq + ", API http://localhost:8096/api/state");
            service.run(cluster, running);
            http.stop(0);
        }
        running.closed();
    }

    private static String env(final String key, final String def)
    {
        final String v = System.getenv(key);
        return null == v || v.isBlank() ? def : v;
    }
}
