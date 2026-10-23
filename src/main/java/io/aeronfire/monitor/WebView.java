package io.aeronfire.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Web blotter: the JDK's built-in HTTP server, one static page, and a Server-Sent Events stream of the
 * model as JSON four times a second. No web framework, nothing to install.
 */
final class WebView implements AutoCloseable
{
    private static final long PUSH_INTERVAL_MS = 250;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Supplier<String> state;
    private volatile boolean running = true;

    WebView(final int port, final Supplier<String> state) throws IOException
    {
        this.state = state;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::page);
        server.createContext("/state", this::snapshot);
        server.createContext("/events", this::events);
        server.start();
    }

    private void page(final HttpExchange exchange) throws IOException
    {
        try (InputStream in = WebView.class.getResourceAsStream("/web/index.html"))
        {
            final byte[] body = null == in ? "index.html missing".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(null == in ? 500 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
    }

    private void snapshot(final HttpExchange exchange) throws IOException
    {
        final byte[] body = state.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /**
     * Virtual threads make one blocking writer per browser tab cheap.
     */
    private void events(final HttpExchange exchange) throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody())
        {
            while (running)
            {
                out.write(("data: " + state.get() + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(PUSH_INTERVAL_MS);
            }
        }
        catch (final IOException ex)
        {
            // browser went away
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close()
    {
        running = false;
        server.stop(0);
        executor.shutdownNow();
    }
}
