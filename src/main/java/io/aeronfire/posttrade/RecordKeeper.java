package io.aeronfire.posttrade;

import io.aeronfire.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * MiFIR Article 25 / RTS 25 style record keeping for post-trade: an append-only, hash-chained log of every
 * capture, booking, report, acknowledgement, repair and replay, each with the acting user and a retention
 * date (5 years from the record). Tampering with or deleting a line breaks the chain, which {@link #verify}
 * detects. This is tamper-evident, not WORM storage: production copies the log to immutable storage.
 */
public final class RecordKeeper
{
    public static final int RETENTION_YEARS = 5;
    private static final String GENESIS = "0".repeat(64);

    public record Record(long n, String at, String type, long tradeId, String user, String data, String retainUntil,
        String prev, String hash)
    {
    }

    private final Path file;
    private long next;
    private String lastHash = GENESIS;

    public RecordKeeper(final Path file)
    {
        this.file = file;
        try
        {
            if (null != file.getParent())
            {
                Files.createDirectories(file.getParent());
            }
            if (Files.exists(file))
            {
                final List<Record> all = read();
                next = all.size();
                lastHash = all.isEmpty() ? GENESIS : all.getLast().hash();
            }
        }
        catch (final IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * @param dataJson a JSON value (object, string...) describing the event.
     */
    public synchronized Record append(final String type, final long tradeId, final String user, final String dataJson)
    {
        final Instant now = Instant.now();
        final String retain = now.atZone(ZoneOffset.UTC).toLocalDate().plusYears(RETENTION_YEARS).toString();
        final String body = "\"n\":" + next + ",\"at\":" + Json.quote(now.toString()) + ",\"type\":" + Json.quote(type) +
            ",\"trade\":" + tradeId + ",\"user\":" + Json.quote(user) + ",\"retainUntil\":" + Json.quote(retain) +
            ",\"data\":" + dataJson;
        final String hash = sha256(lastHash + "|{" + body + "}");
        final String line = "{" + body + ",\"prev\":\"" + lastHash + "\",\"hash\":\"" + hash + "\"}\n";
        try
        {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                StandardOpenOption.SYNC);
        }
        catch (final IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
        final Record r = new Record(next, now.toString(), type, tradeId, user, dataJson, retain, lastHash, hash);
        next++;
        lastHash = hash;
        return r;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Record> read()
    {
        final List<Record> out = new ArrayList<>();
        if (!Files.exists(file))
        {
            return out;
        }
        try
        {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                if (line.isBlank())
                {
                    continue;
                }
                final Map<String, Object> m = (Map<String, Object>)Json.parse(line);
                out.add(new Record(((Number)m.get("n")).longValue(), (String)m.get("at"), (String)m.get("type"),
                    ((Number)m.get("trade")).longValue(), (String)m.get("user"), rawData(line), (String)m.get("retainUntil"),
                    (String)m.get("prev"), (String)m.get("hash")));
            }
        }
        catch (final IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
        return out;
    }

    /**
     * The "data" value exactly as written: the hash covers the text, so it has to be recovered verbatim.
     */
    private static String rawData(final String line)
    {
        final int start = line.indexOf("\"data\":") + 7;
        final int end = line.lastIndexOf(",\"prev\":");
        return line.substring(start, end);
    }

    /**
     * @return null if the chain is intact, else a description of the first break.
     */
    public synchronized String verify()
    {
        String prev = GENESIS;
        long expected = 0;
        for (final Record r : read())
        {
            if (r.n() != expected)
            {
                return "record " + expected + " missing or out of order (found " + r.n() + ")";
            }
            if (!r.prev().equals(prev))
            {
                return "record " + r.n() + " does not chain to its predecessor";
            }
            final String body = "\"n\":" + r.n() + ",\"at\":" + Json.quote(r.at()) + ",\"type\":" + Json.quote(r.type()) +
                ",\"trade\":" + r.tradeId() + ",\"user\":" + Json.quote(r.user()) +
                ",\"retainUntil\":" + Json.quote(r.retainUntil()) + ",\"data\":" + r.data();
            if (!sha256(prev + "|{" + body + "}").equals(r.hash()))
            {
                return "record " + r.n() + " content does not match its hash";
            }
            prev = r.hash();
            expected++;
        }
        return null;
    }

    public synchronized long size()
    {
        return next;
    }

    /**
     * True if the retention period has passed (only then may a record be considered for deletion).
     */
    public static boolean expired(final Record r, final LocalDate today)
    {
        return LocalDate.parse(r.retainUntil()).isBefore(today);
    }

    private static String sha256(final String s)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        }
        catch (final NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException(ex);
        }
    }
}
