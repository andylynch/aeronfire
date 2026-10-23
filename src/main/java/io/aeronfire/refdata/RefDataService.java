package io.aeronfire.refdata;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aeronfire.control.Users;
import io.aeronfire.util.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reference data service: loads snapshot (default) or live sources into the golden copy, serves it over HTTP
 * (TSV for machines, JSON for the UI), and keeps the cluster's client eligibility in step by calling the
 * control plane as user {@code refdata}. The cluster never calls out: reference data is pushed in as ordinary
 * commands, so it's journalled, replayed and audited like everything else.
 * <p>
 * Env: REFDATA_MODE=snapshot|live, REFDATA_HTTP_PORT=8095, CONTROL_URL=http://localhost:8090,
 * REFDATA_PASSWORD (control plane password for user refdata; defaults to the user name),
 * REFDATA_INTERVAL_S=10, USERS spec via CONTROL_USERS.
 */
public final class RefDataService
{
    private static final Set<String> EDITORS = Set.of("root", "refdata", "compliance");
    private static final Set<String> COMPLIANCE = Set.of("root", "compliance");

    private final RefDataStore store = new RefDataStore();
    private final long startMs = System.currentTimeMillis();
    private final Map<Integer, String> pushed = new HashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String controlUrl;
    private final String authHeader;
    private volatile String lastPush = "not yet";

    RefDataService(final String controlUrl, final String password)
    {
        this.controlUrl = controlUrl;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(("refdata:" + password).getBytes(StandardCharsets.UTF_8));
    }

    public RefDataStore store()
    {
        return store;
    }

    long elapsedSeconds()
    {
        return (System.currentTimeMillis() - startMs) / 1000;
    }

    // ------------------------------------------------------------------ loading

    void loadSnapshots()
    {
        store.load(Sources.snapshotClients(), Sources.snapshotLeis(), Sources.snapshotInstruments(),
            Sources.snapshotSanctions());
    }

    /**
     * Live refresh of what's public. Falls back to the snapshot per source, loudly, so the demo still runs
     * behind a firewall.
     */
    void loadLive()
    {
        loadSnapshots();
        try
        {
            final Set<String> leis = new HashSet<>();
            store.clients().forEach((c) -> leis.add(c.lei()));
            leis.remove(null);
            // Fictional AF00* LEIs won't be in GLEIF; real ones (if you swap them in) will.
            final List<Model.LeiRecord> fresh = Sources.gleif(leis);
            store.updateLeis(fresh);
            System.out.println("GLEIF live: " + fresh.size() + " of " + leis.size() + " LEIs found");
        }
        catch (final Exception ex)
        {
            System.out.println("GLEIF live unavailable, keeping snapshot: " + ex.getMessage());
        }
        try
        {
            final List<Model.Designation> live = Sources.ukSanctionsList();
            System.out.println("UK Sanctions List live: " + live.size() + " names");
            final List<Sources.TimedDesignation> merged = new java.util.ArrayList<>();
            live.forEach((d) -> merged.add(new Sources.TimedDesignation(d, 0)));
            // keep the demo's timed entry so the mid-session update still happens
            Sources.snapshotSanctions().stream().filter((t) -> t.effectiveAfterSeconds() > 0).forEach(merged::add);
            store.load(List.of(), List.of(), store.instruments(), merged);
        }
        catch (final Exception ex)
        {
            System.out.println("UK Sanctions List live unavailable, keeping snapshot: " + ex.getMessage());
        }
        try
        {
            final List<String> files = Sources.firdsFiles("DLTINS", 1);
            System.out.println("FIRDS latest delta file: " + files);
        }
        catch (final Exception ex)
        {
            System.out.println("FIRDS live unavailable, keeping snapshot: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ pushing eligibility

    /**
     * Evaluate and push any changes to the cluster.
     */
    synchronized void evaluateAndPush()
    {
        for (final RefDataStore.Verdict v : store.verdicts(LocalDate.now(), elapsedSeconds()))
        {
            final String key = v.kycValid() + "/" + v.sanctioned();
            if (key.equals(pushed.get(v.clientId())))
            {
                continue;
            }
            // Everyone starts eligible in the cluster, so only push when something differs from that.
            if (v.kycValid() && !v.sanctioned() && !pushed.containsKey(v.clientId()))
            {
                pushed.put(v.clientId(), key);
                continue;
            }
            final String result = push(v.clientId(), v.kycValid(), v.sanctioned());
            lastPush = "client " + v.clientId() + " kyc=" + v.kycValid() + " sanctioned=" + v.sanctioned() + ": " + result;
            System.out.println("eligibility push: " + lastPush + " " + v.reasons());
            if (result.startsWith("APPLIED") || result.startsWith("PENDING"))
            {
                // a pending un-sanction needs four-eyes in the cluster; remember we asked so we don't re-queue it
                pushed.put(v.clientId(), key);
            }
        }
    }

    private String push(final int clientId, final boolean kycValid, final boolean sanctioned)
    {
        final String body = "op=eligibility&clientId=" + clientId + "&kycValid=" + kycValid + "&sanctioned=" + sanctioned;
        try
        {
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(controlUrl + "/api/do"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Json.string(Json.parse(response.body()), "result")
                : "REJECTED: control plane HTTP " + response.statusCode();
        }
        catch (final Exception ex)
        {
            return "UNREACHABLE: " + ex.getMessage();
        }
    }

    // ------------------------------------------------------------------ HTTP API

    HttpServer serve(final int port, final Users users) throws IOException
    {
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        final BasicAuthenticator auth = new BasicAuthenticator("aeronfire refdata")
        {
            @Override
            public boolean checkCredentials(final String user, final String password)
            {
                return users.check(user, password);
            }
        };
        server.createContext("/clients.tsv", this::clientsTsv).setAuthenticator(auth);
        server.createContext("/instruments.tsv", this::instrumentsTsv).setAuthenticator(auth);
        server.createContext("/api/state", this::state).setAuthenticator(auth);
        server.createContext("/api/override", this::override).setAuthenticator(auth);
        server.createContext("/api/discount", this::discount).setAuthenticator(auth);
        server.start();
        return server;
    }

    private void clientsTsv(final HttpExchange ex) throws IOException
    {
        final StringBuilder sb = new StringBuilder("clientId\tname\tlei\tkycStatus\tkycReviewDue\tssiBic\tssiAccount\tsource\n");
        for (final Model.Client c : store.clients())
        {
            sb.append(c.clientId()).append('\t').append(c.name()).append('\t').append(nz(c.lei())).append('\t')
                .append(c.kycStatus()).append('\t').append(c.kycReviewDue()).append('\t').append(nz(c.ssiBic()))
                .append('\t').append(nz(c.ssiAccount())).append('\t').append(c.provenance().source()).append('\n');
        }
        send(ex, 200, "text/tab-separated-values", sb.toString());
    }

    private void instrumentsTsv(final HttpExchange ex) throws IOException
    {
        final StringBuilder sb = new StringBuilder("instrumentId\tisin\tname\tcfi\tcurrency\tissuerLei\tmaturity\tsettleDays\tcategory\tvenues\n");
        for (final Model.Instrument i : store.instruments())
        {
            sb.append(i.instrumentId()).append('\t').append(i.isin()).append('\t').append(i.fullName()).append('\t')
                .append(i.cfi()).append('\t').append(i.currency()).append('\t').append(i.issuerLei()).append('\t')
                .append(i.maturity()).append('\t').append(i.settlementDays()).append('\t')
                .append(i.transparencyCategory()).append('\t').append(String.join(",", i.venues())).append('\n');
        }
        send(ex, 200, "text/tab-separated-values", sb.toString());
    }

    private void state(final HttpExchange ex) throws IOException
    {
        final StringBuilder sb = new StringBuilder("{\"elapsedSeconds\":").append(elapsedSeconds())
            .append(",\"lastPush\":").append(Json.quote(lastPush)).append(",\"clients\":[");
        boolean first = true;
        for (final RefDataStore.Verdict v : store.verdicts(LocalDate.now(), elapsedSeconds()))
        {
            final Model.Client c = store.client(v.clientId());
            sb.append(first ? "" : ",").append("{\"id\":").append(c.clientId()).append(",\"name\":").append(Json.quote(c.name()))
                .append(",\"lei\":").append(Json.quote(c.lei())).append(",\"kycValid\":").append(v.kycValid())
                .append(",\"sanctioned\":").append(v.sanctioned()).append(",\"reasons\":").append(array(v.reasons()))
                .append(",\"warnings\":").append(array(v.warnings())).append('}');
            first = false;
        }
        sb.append("],\"audit\":[");
        first = true;
        for (final RefDataStore.Audit a : store.audit())
        {
            sb.append(first ? "" : ",").append("{\"at\":").append(Json.quote(a.at().toString()))
                .append(",\"user\":").append(Json.quote(a.user())).append(",\"action\":").append(Json.quote(a.action()))
                .append(",\"subject\":").append(Json.quote(a.subject())).append(",\"before\":").append(Json.quote(a.before()))
                .append(",\"after\":").append(Json.quote(a.after())).append(",\"reason\":").append(Json.quote(a.reason()))
                .append('}');
            first = false;
        }
        send(ex, 200, "application/json", sb.append("]}").toString());
    }

    private void override(final HttpExchange ex) throws IOException
    {
        final String user = ex.getPrincipal().getUsername();
        if (!EDITORS.contains(user))
        {
            send(ex, 403, "text/plain", "NOT_ENTITLED");
            return;
        }
        final Map<String, String> p = form(ex);
        final String result = store.override(user, Integer.parseInt(p.getOrDefault("clientId", "-1")),
            p.getOrDefault("field", ""), p.getOrDefault("value", ""), p.get("reason"));
        evaluateAndPush();
        send(ex, 200, "text/plain", result);
    }

    private void discount(final HttpExchange ex) throws IOException
    {
        final String user = ex.getPrincipal().getUsername();
        if (!COMPLIANCE.contains(user))
        {
            send(ex, 403, "text/plain", "NOT_ENTITLED: compliance only");
            return;
        }
        final Map<String, String> p = form(ex);
        final String result = store.discount(user, Integer.parseInt(p.getOrDefault("clientId", "-1")),
            p.getOrDefault("designationId", ""), p.get("reason"));
        evaluateAndPush();
        send(ex, 200, "text/plain", result);
    }

    // ------------------------------------------------------------------ main

    public static void main(final String[] args) throws Exception
    {
        final String mode = env("REFDATA_MODE", "snapshot");
        final int port = Integer.parseInt(env("REFDATA_HTTP_PORT", "8095"));
        final long intervalS = Long.parseLong(env("REFDATA_INTERVAL_S", "10"));
        final RefDataService service = new RefDataService(env("CONTROL_URL", "http://localhost:8090"),
            env("REFDATA_PASSWORD", "refdata"));
        if ("live".equals(mode))
        {
            service.loadLive();
        }
        else
        {
            service.loadSnapshots();
        }
        service.serve(port, new Users(System.getenv("CONTROL_USERS")));
        System.out.printf("refdata up (%s): http://localhost:%d/api/state, /clients.tsv, /instruments.tsv%n", mode, port);

        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(() ->
        {
            try
            {
                service.evaluateAndPush();
            }
            catch (final RuntimeException ex)
            {
                System.out.println("evaluate failed: " + ex);
            }
        }, 2, intervalS, TimeUnit.SECONDS);
        Thread.currentThread().join();
    }

    // ------------------------------------------------------------------ helpers

    private static String env(final String key, final String def)
    {
        final String v = System.getenv(key);
        return null == v || v.isBlank() ? def : v;
    }

    private static String nz(final String s)
    {
        return null == s ? "" : s;
    }

    private static String array(final List<String> items)
    {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++)
        {
            sb.append(i > 0 ? "," : "").append(Json.quote(items.get(i)));
        }
        return sb.append(']').toString();
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

    @SuppressWarnings("unused")
    private static String enc(final String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
