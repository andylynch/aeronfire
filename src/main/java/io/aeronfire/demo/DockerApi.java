package io.aeronfire.demo;

import io.aeronfire.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Just enough of the Docker Engine API over its Unix socket to inject faults: list the compose project's
 * containers, kill/start/stop/pause/unpause/restart one, and run a fixed command inside one.
 * <p>
 * This is deliberately not a general client. Callers pass container ids that came from {@link #containers},
 * the id is re-validated here, and there is no way to create containers, mount anything or run a caller-chosen
 * command through the demo panel's HTTP surface. Mounting the Docker socket is still powerful, so the demo
 * service sits behind a password and is meant for a private host.
 */
final class DockerApi
{
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");
    private static final Pattern ACTION = Pattern.compile("kill|start|stop|pause|unpause|restart");
    private static final long TIMEOUT_SECONDS = 15;
    private static final String PROJECT_LABEL = "com.docker.compose.project";
    private static final String SERVICE_LABEL = "com.docker.compose.service";
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor((r) ->
    {
        final Thread t = new Thread(r, "docker-watchdog");
        t.setDaemon(true);
        return t;
    });

    record Container(String id, String service, String state, String status)
    {
    }

    record Result(int status, String body)
    {
        boolean ok()
        {
            return status >= 200 && status < 300;
        }
    }

    private final String socketPath;
    private final String project;

    DockerApi(final String socketPath, final String project)
    {
        this.socketPath = socketPath;
        this.project = project;
    }

    /**
     * The compose project this process runs in, read from its own container's labels (the container's hostname
     * is its short id), so the panel only ever touches its own project's containers.
     */
    static String detectProject(final String socketPath, final String hostname)
    {
        if (null == hostname || !CONTAINER_ID.matcher(hostname).matches())
        {
            return null;
        }
        try
        {
            final Result r = new DockerApi(socketPath, null).request("GET", "/containers/" + hostname + "/json", null);
            if (r.ok() && Json.path(Json.parse(r.body()), "Config.Labels") instanceof Map<?, ?> labels
                && labels.get(PROJECT_LABEL) instanceof String name)
            {
                return name;
            }
            return null;
        }
        catch (final IOException | RuntimeException ex)
        {
            return null;
        }
    }

    boolean available()
    {
        try
        {
            return request("GET", "/_ping", null).ok();
        }
        catch (final IOException ex)
        {
            return false;
        }
    }

    List<Container> containers(final Set<String> services) throws IOException
    {
        final String filters = null == project
            ? "{\"label\":[\"" + SERVICE_LABEL + "\"]}"
            : "{\"label\":[\"" + PROJECT_LABEL + "=" + project + "\"]}";
        final Result r = request("GET",
            "/containers/json?all=true&filters=" + URLEncoder.encode(filters, StandardCharsets.UTF_8), null);
        if (!r.ok())
        {
            throw new IOException("docker list failed: " + r.status());
        }
        final List<Container> out = new ArrayList<>();
        if (Json.parse(r.body()) instanceof List<?> list)
        {
            for (final Object o : list)
            {
                if (o instanceof Map<?, ?> m && m.get("Labels") instanceof Map<?, ?> labels
                    && labels.get(SERVICE_LABEL) instanceof String service && services.contains(service))
                {
                    out.add(new Container(String.valueOf(m.get("Id")), service,
                        String.valueOf(m.get("State")), String.valueOf(m.get("Status"))));
                }
            }
        }
        return out;
    }

    Result action(final String id, final String action) throws IOException
    {
        requireId(id);
        if (!ACTION.matcher(action).matches())
        {
            throw new IllegalArgumentException("action not allowed: " + action);
        }
        final String query = switch (action)
        {
            case "kill" -> "?signal=SIGKILL";
            case "stop", "restart" -> "?t=3";
            default -> "";
        };
        return request("POST", "/containers/" + id + "/" + action + query, null);
    }

    /**
     * Run a command inside a running container and return its exit code. Callers must pass a fixed command
     * template with validated numbers only; this method does no shell parsing and never builds a shell string.
     */
    int exec(final String id, final List<String> command) throws IOException
    {
        requireId(id);
        final StringBuilder cmd = new StringBuilder("[");
        for (int i = 0; i < command.size(); i++)
        {
            cmd.append(i == 0 ? "" : ",").append(Json.quote(command.get(i)));
        }
        cmd.append(']');
        final Result created = request("POST", "/containers/" + id + "/exec",
            "{\"AttachStdout\":false,\"AttachStderr\":false,\"Cmd\":" + cmd + "}");
        if (!created.ok())
        {
            throw new IOException("exec create failed: " + created.status() + " " + created.body());
        }
        final String execId = Json.string(Json.parse(created.body()), "Id");
        if (null == execId || !CONTAINER_ID.matcher(execId).matches())
        {
            throw new IOException("exec create returned no id");
        }
        final Result started = request("POST", "/exec/" + execId + "/start", "{\"Detach\":true}");
        if (!started.ok())
        {
            throw new IOException("exec start failed: " + started.status());
        }
        for (int i = 0; i < 50; i++)
        {
            final Result info = request("GET", "/exec/" + execId + "/json", null);
            final Object parsed = info.ok() ? Json.parse(info.body()) : null;
            if (null != parsed && Boolean.FALSE.equals(Json.path(parsed, "Running")))
            {
                return ((Number)Json.path(parsed, "ExitCode")).intValue();
            }
            try
            {
                Thread.sleep(100);
            }
            catch (final InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for exec", ex);
            }
        }
        throw new IOException("exec did not finish");
    }

    private static void requireId(final String id)
    {
        if (!CONTAINER_ID.matcher(id).matches())
        {
            throw new IllegalArgumentException("bad container id");
        }
    }

    @SuppressWarnings("try")
    Result request(final String method, final String path, final String body) throws IOException
    {
        final byte[] payload = null == body ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        final StringBuilder head = new StringBuilder()
            .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            .append("Host: docker\r\nConnection: close\r\nAccept: application/json\r\n");
        if (null != body)
        {
            head.append("Content-Type: application/json\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX))
        {
            // A wedged daemon must not wedge the panel: closing the channel unblocks any read or write.
            final var timer = WATCHDOG.schedule(() ->
            {
                try
                {
                    channel.close();
                }
                catch (final IOException ignored)
                {
                    // already closed
                }
            }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try
            {
                channel.connect(UnixDomainSocketAddress.of(socketPath));
                final ByteBuffer out = ByteBuffer.wrap(concat(head.toString().getBytes(StandardCharsets.US_ASCII), payload));
                while (out.hasRemaining())
                {
                    channel.write(out);
                }
                final InputStream in = Channels.newInputStream(channel);
                return parse(in.readAllBytes());
            }
            finally
            {
                timer.cancel(false);
            }
        }
    }

    static Result parse(final byte[] raw) throws IOException
    {
        final String text = new String(raw, StandardCharsets.ISO_8859_1);
        final int split = text.indexOf("\r\n\r\n");
        if (split < 0 || !text.startsWith("HTTP/1."))
        {
            throw new IOException("malformed docker response");
        }
        final String headers = text.substring(0, split);
        final int status = Integer.parseInt(headers.substring(9, 12));
        final byte[] body = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
        final boolean chunked = headers.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked");
        return new Result(status, new String(chunked ? dechunk(body) : body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(final byte[] body) throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length)
        {
            int eol = pos;
            while (eol + 1 < body.length && !(body[eol] == '\r' && body[eol + 1] == '\n'))
            {
                eol++;
            }
            final String sizeLine = new String(body, pos, eol - pos, StandardCharsets.US_ASCII).trim();
            final int semicolon = sizeLine.indexOf(';');
            final int size;
            try
            {
                size = Integer.parseInt(semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon), 16);
            }
            catch (final NumberFormatException ex)
            {
                throw new IOException("bad chunk size", ex);
            }
            if (size == 0)
            {
                break;
            }
            pos = eol + 2;
            if (pos + size > body.length)
            {
                throw new IOException("truncated chunk");
            }
            out.write(body, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    private static byte[] concat(final byte[] a, final byte[] b)
    {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
