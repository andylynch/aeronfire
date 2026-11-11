package io.aeronfire.venue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runtime knobs for the venue simulator, for the demo panel: read the latest stats, change the RFQ rate.
 * <p>
 * The simulator's loop owns all the histograms, so it publishes an immutable JSON string about once a second
 * and this server only hands that string out. The rate is one volatile int the loop reads. Not authenticated:
 * keep the port on the compose network, never publish it.
 */
final class VenueControl implements AutoCloseable
{
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final int maxRate;
    private volatile int rate;
    private volatile String stats = "{}";

    VenueControl(final int port, final int initialRate, final int maxRate) throws IOException
    {
        this.rate = initialRate;
        this.maxRate = maxRate;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/stats", this::stats);
        server.createContext("/rate", this::setRate);
        server.start();
    }

    /**
     * Start a control server if {@code VENUE_CONTROL_PORT} is set; otherwise return null (resource-friendly).
     */
    static VenueControl startIfConfigured(final int initialRate)
    {
        final String port = System.getenv("VENUE_CONTROL_PORT");
        if (null == port || port.isBlank() || "0".equals(port.trim()))
        {
            return null;
        }
        try
        {
            final String max = System.getenv("VENUE_MAX_RATE");
            return new VenueControl(Integer.parseInt(port.trim()), initialRate,
                null == max || max.isBlank() ? 500 : Integer.parseInt(max.trim()));
        }
        catch (final IOException ex)
        {
            throw new IllegalStateException("venue control server failed to start", ex);
        }
    }

    int rate()
    {
        return rate;
    }

    void publish(final String json)
    {
        stats = json;
    }

    private void stats(final HttpExchange exchange) throws IOException
    {
        reply(exchange, 200, "{\"rate\":" + rate + ",\"maxRate\":" + maxRate + ",\"stats\":" + stats + "}");
    }

    private void setRate(final HttpExchange exchange) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            reply(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        final String query = exchange.getRequestURI().getQuery();
        try
        {
            if (null == query || !query.startsWith("rps="))
            {
                throw new NumberFormatException("expected rps=N");
            }
            final int requested = Integer.parseInt(query.substring(4).trim());
            rate = Math.max(0, Math.min(requested, maxRate));
            reply(exchange, 200, "{\"rate\":" + rate + "}");
        }
        catch (final NumberFormatException ex)
        {
            reply(exchange, 400, "{\"error\":\"rps must be an integer\"}");
        }
    }

    private static void reply(final HttpExchange exchange, final int status, final String body) throws IOException
    {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(bytes);
        }
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }
}
