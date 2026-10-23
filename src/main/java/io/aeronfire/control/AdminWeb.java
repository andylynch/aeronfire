package io.aeronfire.control;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Admin web page for the control plane. HTTP basic auth binds every action to the logged-in user, then calls
 * the same MBean operations Jolokia exposes, so there is exactly one path into the cluster.
 * Demo only: put TLS in front of it (or bind to localhost) for anything real.
 */
final class AdminWeb implements AutoCloseable
{
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ControlPlane control;
    private final Mbeans beans;

    AdminWeb(final int port, final ControlPlane control, final Users users) throws IOException
    {
        this.control = control;
        this.beans = new Mbeans(control, "");
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        final BasicAuthenticator auth = new BasicAuthenticator("aeronfire control")
        {
            @Override
            public boolean checkCredentials(final String user, final String password)
            {
                return users.check(user, password);
            }
        };
        server.createContext("/", this::page).setAuthenticator(auth);
        server.createContext("/api/state", this::state).setAuthenticator(auth);
        server.createContext("/api/do", this::act).setAuthenticator(auth);
        server.start();
    }

    private void page(final HttpExchange exchange) throws IOException
    {
        try (InputStream in = AdminWeb.class.getResourceAsStream("/web/admin.html"))
        {
            final byte[] body = null == in ? "admin.html missing".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
            send(exchange, null == in ? 500 : 200, "text/html; charset=utf-8", body);
        }
    }

    private void state(final HttpExchange exchange) throws IOException
    {
        final String json = control.view().toJson();
        final String me = exchange.getPrincipal().getUsername();
        send(exchange, 200, "application/json",
            ("{\"me\":\"" + me + "\"," + json.substring(1)).getBytes(StandardCharsets.UTF_8));
    }

    private void act(final HttpExchange exchange) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            send(exchange, 405, "text/plain", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }

        final String user = exchange.getPrincipal().getUsername();
        final Map<String, String> p = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String result;
        try
        {
            result = switch (p.getOrDefault("op", ""))
            {
                case "creditLine" -> beans.setCreditLine(user, i(p, "clientId"), i(p, "tier"), d(p, "limitMm"));
                case "creditReset" -> beans.resetUtilisation(user, i(p, "clientId"));
                case "inventoryLimits" -> beans.setLimits(user, i(p, "instrumentId"), d(p, "maxLongMm"), d(p, "maxShortMm"));
                case "halt" -> beans.halt(user, i(p, "instrumentId"));
                case "resume" -> beans.resume(user, i(p, "instrumentId"));
                case "adjust" -> beans.adjustPosition(user, i(p, "instrumentId"), d(p, "deltaMm"), p.get("reason"));
                case "engageKillSwitch" -> beans.engageKillSwitch(user);
                case "releaseKillSwitch" -> beans.releaseKillSwitch(user);
                case "algo" -> beans.setControls(user, i(p, "collarBps"), i(p, "maxRfqs"));
                case "grant" -> beans.grant(user, p.get("target"), p.get("perms"), p.getOrDefault("clients", "ALL"),
                    p.getOrDefault("instruments", "ALL"), d(p, "maxCreditMm"), d(p, "maxAdjustMm"), d(p, "maxInventoryMm"));
                case "approve" -> beans.approve(user, Long.parseLong(p.get("id")));
                case "reject" -> beans.reject(user, Long.parseLong(p.get("id")));
                case "eligibility" -> beans.setEligibility(user, i(p, "clientId"),
                    Boolean.parseBoolean(p.get("kycValid")), Boolean.parseBoolean(p.get("sanctioned")));
                default -> "REJECTED: unknown operation";
            };
        }
        catch (final RuntimeException ex)
        {
            result = "REJECTED: " + ex.getMessage();
        }
        send(exchange, 200, "application/json",
            ("{\"result\":\"" + result.replace("\\", "\\\\").replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static int i(final Map<String, String> p, final String key)
    {
        return Integer.parseInt(p.get(key).trim());
    }

    private static double d(final Map<String, String> p, final String key)
    {
        final String v = p.get(key);
        return null == v || v.isBlank() ? 0.0 : Double.parseDouble(v.trim());
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

    private static void send(final HttpExchange exchange, final int status, final String type, final byte[] body)
        throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }
}
