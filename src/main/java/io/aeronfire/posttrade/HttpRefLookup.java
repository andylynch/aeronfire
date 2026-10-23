package io.aeronfire.posttrade;

import io.aeronfire.refdata.Model;
import io.aeronfire.refdata.Sources;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference data over the reference data service's TSV API (clients, so SSI and LEI repairs are seen quickly),
 * with instruments and LEI status from the checked-in snapshots. Falls back to the snapshot client master if
 * the service is unreachable, and says so once.
 */
public final class HttpRefLookup implements RefLookup
{
    private static final long MAX_AGE_MS = 1_000;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String auth;
    private final List<Model.Instrument> instruments = Sources.snapshotInstruments();
    private final Map<String, Model.LeiRecord> leis = new HashMap<>();
    private volatile Map<Integer, Model.Client> clients = new HashMap<>();
    private volatile long fetchedAt;
    private boolean warned;

    public HttpRefLookup(final String baseUrl, final String password)
    {
        this.baseUrl = baseUrl;
        this.auth = "Basic " + Base64.getEncoder().encodeToString(("refdata:" + password).getBytes(StandardCharsets.UTF_8));
        Sources.snapshotLeis().forEach((l) -> leis.put(l.lei(), l));
        Sources.snapshotClients().forEach((c) -> clients.put(c.clientId(), c));
    }

    @Override
    public void refreshNow()
    {
        fetchedAt = 0;
    }

    private void refresh()
    {
        final long now = System.currentTimeMillis();
        if (now - fetchedAt < MAX_AGE_MS)
        {
            return;
        }
        fetchedAt = now;
        try
        {
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/clients.tsv"))
                .timeout(Duration.ofSeconds(3)).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
            {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            final Map<Integer, Model.Client> fresh = new HashMap<>();
            final String[] lines = response.body().split("\n");
            for (int i = 1; i < lines.length; i++)
            {
                final String[] f = lines[i].split("\t", -1);
                final Model.Client old = clients.get(Integer.parseInt(f[0]));
                fresh.put(Integer.parseInt(f[0]), new Model.Client(Integer.parseInt(f[0]), f[1], blank(f[2]),
                    null == old ? "" : old.country(), null == old ? "" : old.mifidClass(), f[3],
                    "null".equals(f[4]) ? null : LocalDate.parse(f[4]), null == old ? "" : old.riskRating(),
                    blank(f[5]), blank(f[6]), null == old ? null : old.parentName(),
                    new Model.Provenance(f[7], "", java.time.Instant.now())));
            }
            clients = fresh;
            warned = false;
        }
        catch (final Exception ex)
        {
            if (!warned)
            {
                System.out.println("reference data service unreachable, using snapshot clients: " + ex.getMessage());
                warned = true;
            }
        }
    }

    private static String blank(final String s)
    {
        return s.isBlank() ? null : s;
    }

    @Override
    public Model.Client client(final int clientId)
    {
        refresh();
        return clients.get(clientId);
    }

    @Override
    public Model.Instrument instrument(final int instrumentId)
    {
        return instrumentId >= 0 && instrumentId < instruments.size() ? instruments.get(instrumentId) : null;
    }

    @Override
    public Model.LeiRecord lei(final String lei)
    {
        return leis.get(lei);
    }
}
