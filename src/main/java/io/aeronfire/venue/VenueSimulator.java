package io.aeronfire.venue;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeronfire.artio.ArtioRuntime;
import io.aeronfire.util.ShutdownFlag;
import io.aeronfire.domain.Instruments;
import io.aeronfire.fix.ExecType;
import io.aeronfire.fix.FixDictionaryImpl;
import io.aeronfire.fix.QuoteRespType;
import io.aeronfire.fix.builder.QuoteRequestEncoder;
import io.aeronfire.fix.builder.QuoteResponseEncoder;
import io.aeronfire.fix.decoder.ExecutionReportDecoder;
import io.aeronfire.fix.decoder.QuoteDecoder;
import io.aeronfire.fix.decoder.QuoteRequestRejectDecoder;
import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;
import org.agrona.collections.Object2LongHashMap;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.File;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * A Tradeweb-style RFQ venue, simulated: clients send RFQs, several dealers compete, the client trades
 * with the best price.
 * <p>
 * It connects to the dealer gateway as a FIX initiator and plays every role on the venue side:
 * <ul>
 *   <li>Picks a client (account 0..9, mixed tiers), a loan, a side and a size.</li>
 *   <li>Sends a QuoteRequest and waits for our firm Quote.</li>
 *   <li>Invents competing dealers' prices around ours ("comp"), and lifts or hits us only if we're best.</li>
 *   <li>Counts trades, passes and rejects by reason, and records RFQ-to-quote latency.</li>
 * </ul>
 * Sizes include some above tier-3's maximum ticket, so tier and credit rejects show up in the flow.
 */
public final class VenueSimulator
{
    public static final String VENUE_COMP_ID = "TWSIM";

    private static final long[] SIZES = { 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L, 25_000_000L };
    private static final int CLIENTS = 10;

    private final QuoteRequestEncoder quoteRequest = new QuoteRequestEncoder();
    private final QuoteResponseEncoder quoteResponse = new QuoteResponseEncoder();
    private final QuoteDecoder quote = new QuoteDecoder();
    private final ExecutionReportDecoder executionReport = new ExecutionReportDecoder();
    private final QuoteRequestRejectDecoder quoteRequestReject = new QuoteRequestRejectDecoder();
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    private final Object2LongHashMap<String> sentAtNs = new Object2LongHashMap<>(-1L);
    private final Histogram rfqToQuote = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    private final SplittableRandom random;
    private final int competingDealers;
    private final double compNoisePoints;

    private Session session;
    private long nextQuoteReqId = 1;
    private long nextQuoteRespId = 1;
    private long rfqs;
    private long quotes;
    private long weWon;
    private long weLost;
    private long trades;
    private long tradeRejects;
    private long rfqRejects;
    private final java.util.TreeMap<String, Long> rejectReasons = new java.util.TreeMap<>();

    VenueSimulator(final long seed, final int competingDealers, final double compNoisePoints)
    {
        this.random = new SplittableRandom(seed);
        this.competingDealers = competingDealers;
        this.compNoisePoints = compNoisePoints;
    }

    public static void main(final String[] args)
    {
        final String gatewayHost = env("GATEWAY_HOST", "localhost");
        final int gatewayPort = Integer.parseInt(env("FIX_PORT", "9880"));
        final int rfqsPerSecond = Integer.parseInt(env("RFQ_RATE", "50"));
        final long durationSeconds = Long.parseLong(env("DURATION_SECONDS", "0")); // 0: run until Ctrl-C
        final File workDir = new File(env("WORK_DIR", "build"));

        final VenueSimulator venue = new VenueSimulator(
            Long.parseLong(env("SEED", "42")),
            Integer.parseInt(env("COMP_DEALERS", "3")),
            Double.parseDouble(env("COMP_NOISE_POINTS", "0.15")));
        final ShutdownFlag running = ShutdownFlag.register();

        try (ArtioRuntime artio = ArtioRuntime.launch("venue", workDir, (configuration) -> { });
             FixLibrary library = artio.connectLibrary(new LibraryConfiguration()
                .sessionAcquireHandler((session, info) -> venue.handler())))
        {
            venue.session = venue.connect(library, gatewayHost, gatewayPort);
            System.out.printf("venue connected to %s:%d as %s, %d RFQs/s%n",
                gatewayHost, gatewayPort, VENUE_COMP_ID, rfqsPerSecond);
            venue.run(library, running, rfqsPerSecond, durationSeconds);
            venue.printStats();
        }
        running.closed();
    }

    private Session connect(final FixLibrary library, final String host, final int port)
    {
        final SessionConfiguration configuration = SessionConfiguration.builder()
            .address(host, port)
            .senderCompId(VENUE_COMP_ID)
            .targetCompId(io.aeronfire.gateway.FixGateway.GATEWAY_COMP_ID)
            .fixDictionary(FixDictionaryImpl.class)
            .resetSeqNum(true)
            .build();

        final IdleStrategy idle = new BackoffIdleStrategy();
        for (int attempt = 1; attempt <= 30; attempt++)
        {
            final Reply<Session> reply = library.initiate(configuration);
            while (reply.isExecuting())
            {
                idle.idle(library.poll(10));
            }

            if (reply.hasCompleted())
            {
                final Session session = reply.resultIfPresent();
                while (!session.isActive())
                {
                    idle.idle(library.poll(10));
                }
                return session;
            }

            System.out.printf("connect attempt %d failed: %s%n", attempt,
                reply.hasErrored() ? reply.error() : reply.state());
            final long until = System.currentTimeMillis() + 1_000;
            while (System.currentTimeMillis() < until)
            {
                idle.idle(library.poll(10));
            }
        }
        throw new IllegalStateException("could not connect to gateway at " + host + ":" + port);
    }

    private void run(
        final FixLibrary library, final ShutdownFlag running, final int rfqsPerSecond, final long durationSeconds)
    {
        final IdleStrategy idle = new BackoffIdleStrategy();
        final long intervalNs = TimeUnit.SECONDS.toNanos(1) / Math.max(1, rfqsPerSecond);
        final long endNs = durationSeconds > 0 ? System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds) : Long.MAX_VALUE;
        long nextRfqNs = System.nanoTime();
        long nextStatsNs = nextRfqNs + TimeUnit.SECONDS.toNanos(5);

        while (running.isRunning() && System.nanoTime() < endNs && session.isActive())
        {
            int work = library.poll(10);

            final long now = System.nanoTime();
            if (now >= nextRfqNs)
            {
                sendRfq();
                nextRfqNs += intervalNs;
                work++;
            }
            if (now >= nextStatsNs)
            {
                printStats();
                nextStatsNs += TimeUnit.SECONDS.toNanos(5);
            }

            idle.idle(work);
        }

        // Let in-flight responses land before the final report.
        final long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < drainUntil)
        {
            idle.idle(library.poll(10));
        }
    }

    private void sendRfq()
    {
        final String quoteReqId = "Q" + nextQuoteReqId++;
        final int instrumentId = random.nextInt(Instruments.COUNT);

        quoteRequest.reset();
        quoteRequest
            .quoteReqID(quoteReqId)
            .account(Integer.toString(random.nextInt(CLIENTS)))
            .side(random.nextBoolean() ? '1' : '2')
            .orderQty(SIZES[random.nextInt(SIZES.length)], 0);
        quoteRequest.instrument()
            .securityID(Integer.toString(instrumentId))
            .securityIDSource("8")
            .symbol(Instruments.name(instrumentId));

        sentAtNs.put(quoteReqId, System.nanoTime());
        if (send(quoteRequest))
        {
            rfqs++;
        }
    }

    private SessionHandler handler()
    {
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
                if (messageType == QuoteDecoder.MESSAGE_TYPE)
                {
                    quote.reset();
                    quote.decode(asciiBuffer, 0, length);
                    onQuote();
                }
                else if (messageType == ExecutionReportDecoder.MESSAGE_TYPE)
                {
                    executionReport.reset();
                    executionReport.decode(asciiBuffer, 0, length);
                    onExecutionReport();
                }
                else if (messageType == QuoteRequestRejectDecoder.MESSAGE_TYPE)
                {
                    quoteRequestReject.reset();
                    quoteRequestReject.decode(asciiBuffer, 0, length);
                    rfqRejects++;
                    final String quoteReqId = quoteRequestReject.quoteReqIDAsString();
                    sentAtNs.removeKey(quoteReqId);
                    countReason("rfq " + quoteRequestReject.textAsString());
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
                System.out.printf("disconnected from gateway: %s%n", reason);
                return Action.CONTINUE;
            }

            @Override
            public void onSessionStart(final Session session)
            {
            }
        };
    }

    private void onQuote()
    {
        quotes++;
        final String quoteReqId = quote.quoteReqIDAsString();
        final long sentNs = sentAtNs.removeKey(quoteReqId);
        if (sentNs > 0)
        {
            rfqToQuote.recordValue(Math.min(System.nanoTime() - sentNs, rfqToQuote.getHighestTrackableValue()));
        }

        // Comp: other dealers price around us. The client buys at the lowest offer, sells at the highest bid.
        final double ourPrice = quote.price().toDouble();
        final boolean clientBuys = quote.side() == '1';
        boolean weAreBest = true;
        for (int dealer = 0; dealer < competingDealers; dealer++)
        {
            final double comp = ourPrice + random.nextGaussian() * compNoisePoints;
            if (clientBuys ? comp < ourPrice : comp > ourPrice)
            {
                weAreBest = false;
                break;
            }
        }

        quoteResponse.reset();
        quoteResponse
            .quoteRespID("QR" + nextQuoteRespId++)
            .quoteID(quote.quoteID(), quote.quoteIDLength())
            .quoteReqID(quote.quoteReqID(), quote.quoteReqIDLength())
            .quoteRespType(weAreBest ? QuoteRespType.HIT_LIFT : QuoteRespType.PASS)
            .account(quote.account(), quote.accountLength());
        if (send(quoteResponse))
        {
            if (weAreBest)
            {
                weWon++;
            }
            else
            {
                weLost++;
            }
        }
    }

    private void onExecutionReport()
    {
        if (executionReport.execType() == ExecType.TRADE.representation())
        {
            trades++;
        }
        else
        {
            tradeRejects++;
            countReason("trade " + executionReport.textAsString());
        }
    }

    private void countReason(final String reason)
    {
        rejectReasons.merge(reason, 1L, Long::sum);
    }

    private boolean send(final Encoder encoder)
    {
        return session.trySend(encoder) >= 0;
    }

    private void printStats()
    {
        final long decided = weWon + weLost;
        System.out.printf(
            "venue: rfqs %d  quotes %d  won %d (%.0f%%)  trades %d  rfq rejects %d  trade rejects %d%n",
            rfqs, quotes, weWon, decided == 0 ? 0.0 : 100.0 * weWon / decided, trades, rfqRejects, tradeRejects);
        if (rfqToQuote.getTotalCount() > 0)
        {
            System.out.printf("venue: RFQ -> quote over FIX  p50=%.0fus  p99=%.0fus  max=%.0fus%n",
                rfqToQuote.getValueAtPercentile(50) / 1000.0,
                rfqToQuote.getValueAtPercentile(99) / 1000.0,
                rfqToQuote.getMaxValue() / 1000.0);
        }
        if (!rejectReasons.isEmpty())
        {
            System.out.printf("venue: rejects %s%n", rejectReasons);
        }
    }

    private static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }
}
