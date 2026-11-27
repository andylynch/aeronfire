package io.aeronfire.demo;

import io.aeronfire.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to the control plane's admin API as named users, exactly as a browser on the admin page would. The
 * demo's risk scenarios therefore go through the real entitlement checks, audit trail and four-eyes approvals;
 * the panel has no privileged path into the cluster.
 */
final class ControlClient
{
    private static final String[] USERS = { "root", "risk", "ops", "deskhead", "compliance", "sales", "refdata" };

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;
    private final Map<String, String> passwords = new LinkedHashMap<>();

    /**
     * @param usersSpec same format as the control plane's {@code CONTROL_USERS} ({@code name:password,...});
     *                  blank means the demo default, where each password is the user name.
     */
    ControlClient(final String baseUrl, final String usersSpec)
    {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (null == usersSpec || usersSpec.isBlank())
        {
            for (final String user : USERS)
            {
                passwords.put(user, user);
            }
        }
        else
        {
            for (final String entry : usersSpec.split(","))
            {
                final String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2)
                {
                    passwords.put(parts[0], parts[1]);
                }
            }
        }
    }

    /**
     * Run one admin operation as {@code user}; {@code keyValues} are alternating form keys and values.
     * Returns the control plane's result text (it begins with REJECTED when it refused).
     */
    String act(final String user, final String op, final String... keyValues) throws IOException
    {
        final StringBuilder form = new StringBuilder("op=").append(encode(op));
        for (int i = 0; i + 1 < keyValues.length; i += 2)
        {
            form.append('&').append(encode(keyValues[i])).append('=').append(encode(keyValues[i + 1]));
        }
        final HttpRequest request = request(user, "/api/do")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build();
        final Object parsed = Json.parse(send(request));
        final String result = Json.string(parsed, "result");
        return null == result ? "REJECTED: no result" : result;
    }

    Object state(final String user) throws IOException
    {
        return Json.parse(send(request(user, "/api/state").GET().build()));
    }

    private HttpRequest.Builder request(final String user, final String path)
    {
        final String password = passwords.get(user);
        if (null == password)
        {
            throw new IllegalArgumentException("no password configured for control user " + user);
        }
        final String token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Basic " + token);
    }

    private String send(final HttpRequest request) throws IOException
    {
        try
        {
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
            {
                throw new IOException("control plane returned " + response.statusCode() + " for " + request.uri().getPath());
            }
            return response.body();
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        }
    }

    private static String encode(final String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
