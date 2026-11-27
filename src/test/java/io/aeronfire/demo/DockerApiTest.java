package io.aeronfire.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerApiTest
{
    private static byte[] http(final String text)
    {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void parsesPlainResponse() throws IOException
    {
        final DockerApi.Result r = DockerApi.parse(http("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]"));
        assertEquals(200, r.status());
        assertEquals("[]", r.body());
        assertTrue(r.ok());
    }

    @Test
    void parsesChunkedResponse() throws IOException
    {
        final DockerApi.Result r = DockerApi.parse(http(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"));
        assertEquals("hello world", r.body());
    }

    @Test
    void errorStatusIsNotOk() throws IOException
    {
        final DockerApi.Result r = DockerApi.parse(http("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"));
        assertEquals(404, r.status());
        assertFalse(r.ok());
    }

    @Test
    void rejectsGarbage()
    {
        assertThrows(IOException.class, () -> DockerApi.parse(http("not http")));
    }

    @Test
    void refusesUnsafeIdsAndActions()
    {
        final DockerApi docker = new DockerApi("/nonexistent.sock", null);
        assertThrows(IllegalArgumentException.class, () -> docker.action("../../etc", "kill"));
        assertThrows(IllegalArgumentException.class, () -> docker.action("0123456789ab", "rm"));
        assertThrows(IllegalArgumentException.class, () -> docker.exec("0123456789ab; ls", java.util.List.of("true")));
    }
}
