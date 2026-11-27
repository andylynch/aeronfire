package io.aeronfire.demo;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aeronfire.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Demo panel: one page and a small API to exercise the system and break it on purpose.
 * <ul>
 *   <li><b>Infrastructure faults</b> (crash, stop, freeze, degrade the network, wipe a member) through Docker.</li>
 *   <li><b>Risk-control scenarios</b> (credit, halt, sanctions, kill switch, throttle, collar) through the
 *       control plane, so entitlements and four-eyes approval apply.</li>
 *   <li><b>Load</b>: the venue's RFQ rate, with live latency percentiles.</li>
 *   <li><b>Measurement</b>: how long failover took, how fast a control bit, written to a timeline.</li>
 * </ul>
 * Built for visitors to drive themselves, so it is password-gated, rate-limited, refuses cross-site posts,
 * clamps every input, and puts everything back by itself after a while.
 */
public final class DemoPanel
{
    private static final String USER = "demo";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, (r) ->
    {
        final Thread t = new Thread(r, "demo-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Timeline timeline = new Timeline();
    private final Bucket bucket = new Bucket(4, 1.0);
    private final String venueUrl;
    private final int maxRate;
    private final int defaultRate;
    private final long autoHealSeconds;
    private final String blotterUrl;
    private final DemoState state;
    private final Faults faults;
    private final Scenarios scenarios;
    private final HttpServer server;

    private DemoPanel() throws IOException
    {
        final int port = Integer.parseInt(env("DEMO_HTTP_PORT", "8100"));
        final String monitorUrl = env("MONITOR_URL", "http://monitor:8080");
        venueUrl = env("VENUE_URL", "http://venue-live:8099");
        final String controlUrl = env("CONTROL_URL", "http://control:8090");
        maxRate = Integer.parseInt(env("DEMO_MAX_RPS", "200"));
        defaultRate = Integer.parseInt(env("DEMO_DEFAULT_RPS", "20"));
        autoHealSeconds = Long.parseLong(env("DEMO_AUTO_HEAL_S", "60"));
        final long autoRevertSeconds = Long.parseLong(env("DEMO_AUTO_REVERT_S", "120"));
        blotterUrl = env("DEMO_BLOTTER_URL", "");
        final List<String> nodes = Arrays.asList(env("DEMO_NODES", "node0,node1,node2").split(","));

        final String socket = env("DOCKER_SOCKET", "/var/run/docker.sock");
        DockerApi docker = null;
        if (Files.exists(Path.of(socket)))
        {
            final String project = DockerApi.detectProject(socket, System.getenv("HOSTNAME"));
            docker = new DockerApi(socket, null == project ? env("DOCKER_PROJECT", null) : project);
        }

        state = new DemoState(monitorUrl, venueUrl, docker, nodes, timeline);
        faults = new Faults(docker, nodes, timeline, state, env("DEMO_NET_IFACE", "eth0"));
        scenarios = new Scenarios(new ControlClient(controlUrl, System.getenv("CONTROL_USERS")), timeline, scheduler,
            state, autoRevertSeconds);

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/healthz", (x) -> reply(x, 200, "text/plain", "ok"));
        final var auth = authenticator(password());
        server.createContext("/", this::page).setAuthenticator(auth);
        server.createContext("/api/state", this::state).setAuthenticator(auth);
        server.createContext("/api/fault", (x) -> post(x, this::fault)).setAuthenticator(auth);
        server.createContext("/api/scenario", (x) -> post(x, this::scenario)).setAuthenticator(auth);
        server.createContext("/api/load", (x) -> post(x, this::load)).setAuthenticator(auth);
        server.createContext("/api/heal", (x) -> post(x, this::heal)).setAuthenticator(auth);
        server.start();

        state.start(scheduler);
        if (autoHealSeconds > 0)
        {
            scheduler.scheduleWithFixedDelay(this::autoHeal, 5, 5, TimeUnit.SECONDS);
        }
        timeline.add("info", "Demo panel up on :" + port + (null == docker
            ? " (no Docker socket: infrastructure faults are disabled)" : ""));
    }

    public static void main(final String[] args) throws Exception
    {
        new DemoPanel();
        Thread.currentThread().join();
    }

    // ---- actions -----------------------------------------------------------------------------------------

    private String fault(final Map<String, String> p) throws IOException
    {
        return faults.apply(p.getOrDefault("node", ""), p.getOrDefault("op", ""),
            integer(p, "delay"), integer(p, "jitter"), integer(p, "loss"));
    }

    private String scenario(final Map<String, String> p) throws IOException
    {
        return switch (p.getOrDefault("action", ""))
        {
            case "run" -> scenarios.run(p.getOrDefault("id", ""));
            case "undo" -> scenarios.undo(p.getOrDefault("id", ""));
            default -> throw new IllegalArgumentException("action must be run or undo");
        };
    }

    private String load(final Map<String, String> p) throws IOException
    {
        final int requested = integer(p, "rps");
        final int rps = Math.max(0, Math.min(requested, maxRate));
        setRate(rps);
        timeline.add("load", rps == 0 ? "RFQ flow paused" : "RFQ rate set to " + rps + "/s"
            + (requested > maxRate ? " (capped at " + maxRate + ")" : ""));
        return "rate " + rps;
    }

    private String heal(final Map<String, String> p) throws IOException
    {
        final List<String> failures = scenarios.undoAll();
        final List<String> changes = faults.healAll();
        setRate(defaultRate);
        timeline.add("heal", "Reset: all scenarios off, members up and unfrozen, network normal, load back to "
            + defaultRate + "/s" + (changes.isEmpty() ? "" : " (" + String.join(", ", changes) + ")"));
        if (!failures.isEmpty())
        {
            throw new IOException("some scenarios did not undo: " + failures);
        }
        return "reset";
    }

    private void setRate(final int rps) throws IOException
    {
        try
        {
            final HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(venueUrl + "/rate?rps=" + rps)).timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200)
            {
                throw new IOException("venue refused the rate change: " + r.statusCode());
            }
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        }
    }

    /**
     * Visitors will break things and walk away. Anything still down, frozen or degraded after a while is fixed.
     */
    private void autoHeal()
    {
        try
        {
            final long ms = autoHealSeconds * 1_000;
            for (final DockerApi.Container c : state.unhealthyFor(ms))
            {
                timeline.add("info", "Auto-heal: " + c.service() + " has been " + c.state() + " for over "
                    + autoHealSeconds + "s");
                faults.apply(c.service(), "start", 0, 0, 0);
            }
            for (final String node : faults.nodes())
            {
                if (null != faults.netemOf(node) && System.currentTimeMillis() - faults.netemSinceMs(node) > ms)
                {
                    timeline.add("info", "Auto-heal: " + node + " network fault has been on for over " + autoHealSeconds + "s");
                    faults.apply(node, "netem-clear", 0, 0, 0);
                }
            }
        }
        catch (final IOException | RuntimeException ex)
        {
            timeline.add("error", "auto-heal failed: " + ex.getMessage());
        }
    }

    // ---- HTTP --------------------------------------------------------------------------------------------

    private interface Action
    {
        String run(Map<String, String> params) throws IOException;
    }

    private void post(final HttpExchange exchange, final Action action) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            reply(exchange, 405, "application/json", result(false, "POST only"));
            return;
        }
        // Browsers send Basic credentials on cross-site requests, so insist on a header a foreign page can't set.
        if (null == exchange.getRequestHeaders().getFirst("X-Demo-Request"))
        {
            reply(exchange, 403, "application/json", result(false, "missing X-Demo-Request header"));
            return;
        }
        if (!bucket.tryAcquire())
        {
            reply(exchange, 429, "application/json", result(false, "Easy there: one change per second, please."));
            return;
        }
        final Map<String, String> params = form(new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8));
        try
        {
            reply(exchange, 200, "application/json", result(true, action.run(params)));
        }
        catch (final IllegalArgumentException ex)
        {
            reply(exchange, 400, "application/json", result(false, ex.getMessage()));
        }
        catch (final IOException ex)
        {
            timeline.add("error", ex.getMessage());
            reply(exchange, 502, "application/json", result(false, ex.getMessage()));
        }
    }

    private void page(final HttpExchange exchange) throws IOException
    {
        if (!"/".equals(exchange.getRequestURI().getPath()))
        {
            reply(exchange, 404, "text/plain", "not found");
            return;
        }
        try (InputStream in = DemoPanel.class.getResourceAsStream("/web/demo.html"))
        {
            if (null == in)
            {
                reply(exchange, 500, "text/plain", "demo.html missing");
                return;
            }
            reply(exchange, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void state(final HttpExchange exchange) throws IOException
    {
        reply(exchange, 200, "application/json", state.toJson(faults, scenarios, maxRate, autoHealSeconds, blotterUrl));
    }

    private static BasicAuthenticator authenticator(final String password)
    {
        return new BasicAuthenticator("aeronfire demo")
        {
            @Override
            public boolean checkCredentials(final String user, final String given)
            {
                // Both compared in constant time, and always both, so timing says nothing about which was wrong.
                final boolean userOk = MessageDigest.isEqual(bytes(USER), bytes(user));
                final boolean passwordOk = MessageDigest.isEqual(bytes(password), bytes(given));
                return userOk & passwordOk;
            }
        };
    }

    private static String password()
    {
        final String configured = System.getenv("DEMO_PASSWORD");
        if (null != configured && !configured.isBlank())
        {
            return configured;
        }
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        final SecureRandom random = new SecureRandom();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++)
        {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        System.out.printf("DEMO_PASSWORD not set. Generated one for this run: user '%s', password '%s'%n", USER, sb);
        return sb.toString();
    }

    private static byte[] bytes(final String s)
    {
        return null == s ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String result(final boolean ok, final String message)
    {
        return "{\"ok\":" + ok + ",\"message\":" + Json.quote(message) + "}";
    }

    private static void reply(final HttpExchange exchange, final int status, final String type, final String body)
        throws IOException
    {
        final byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream stream = exchange.getResponseBody())
        {
            stream.write(out);
        }
    }

    private static int integer(final Map<String, String> p, final String key)
    {
        final String value = p.get(key);
        if (null == value || value.isBlank())
        {
            return 0;
        }
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (final NumberFormatException ex)
        {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
    }

    private static Map<String, String> form(final String body)
    {
        final Map<String, String> params = new HashMap<>();
        for (final String pair : body.split("&"))
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

    private static String env(final String name, final String defaultValue)
    {
        final String value = System.getenv(name);
        return null == value || value.isBlank() ? defaultValue : value;
    }

    /**
     * Token bucket: a burst of a few clicks, then one change a second.
     */
    private static final class Bucket
    {
        private final double capacity;
        private final double perSecond;
        private double tokens;
        private long lastNs = System.nanoTime();

        Bucket(final double capacity, final double perSecond)
        {
            this.capacity = capacity;
            this.perSecond = perSecond;
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire()
        {
            final long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastNs) / 1e9 * perSecond);
            lastNs = now;
            if (tokens >= 1.0)
            {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
