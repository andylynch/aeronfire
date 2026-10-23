package io.aeronfire.refdata;

import io.aeronfire.util.Json;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reference data providers. Each has a snapshot mode (checked-in extract, works offline and in CI) and, where
 * the data is public, a live mode against the open-data source:
 * <ul>
 *     <li>LEIs: GLEIF API ({@code api.gleif.org}), the global LEI index.</li>
 *     <li>Instruments: FCA FIRDS, the UK MiFIR reference data files (ISO 20022 XML in zips).</li>
 *     <li>Sanctions: the UK Sanctions List (FCDO), the single UK list since OFSI's consolidated list closed.</li>
 *     <li>Clients and KYC: in-house and private, so always simulated.</li>
 * </ul>
 */
public final class Sources
{
    public static final String GLEIF_API = "https://api.gleif.org/api/v1/lei-records";
    public static final String UK_SANCTIONS_XML = "https://sanctionslist.fcdo.gov.uk/docs/UK-Sanctions-List.xml";
    public static final String FCA_FIRDS_INDEX = "https://api.data.fca.org.uk/fca_data_firds_files";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private Sources()
    {
    }

    // ------------------------------------------------------------------ snapshot files

    /**
     * Pipe-separated rows, '#' comments, empty trailing fields kept.
     */
    public static List<String[]> rows(final String resource)
    {
        final InputStream in = Sources.class.getResourceAsStream(resource);
        if (null == in)
        {
            throw new IllegalStateException("missing resource " + resource);
        }
        final List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while (null != (line = reader.readLine()))
            {
                if (!line.isBlank() && !line.startsWith("#"))
                {
                    rows.add(line.split("\\|", -1));
                }
            }
        }
        catch (final IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
        return rows;
    }

    public static List<Model.Client> snapshotClients()
    {
        final Model.Provenance p = Model.Provenance.snapshot("in-house client master");
        final List<Model.Client> clients = new ArrayList<>();
        for (final String[] r : rows("/refdata/clients.csv"))
        {
            clients.add(new Model.Client(Integer.parseInt(r[0]), r[1], blankToNull(r[2]), r[3], r[4], r[5],
                r[6].isBlank() ? null : LocalDate.parse(r[6]), r[7], blankToNull(r[8]), blankToNull(r[9]),
                blankToNull(r[10]), p));
        }
        return clients;
    }

    public static List<Model.LeiRecord> snapshotLeis()
    {
        final Model.Provenance p = Model.Provenance.snapshot("GLEIF");
        final List<Model.LeiRecord> leis = new ArrayList<>();
        for (final String[] r : rows("/refdata/lei.csv"))
        {
            leis.add(new Model.LeiRecord(r[0], r[1], r[2], r[3], r[4], r[5], blankToNull(r[6]), p));
        }
        return leis;
    }

    public static List<Model.Instrument> snapshotInstruments()
    {
        final Model.Provenance p = Model.Provenance.snapshot("FCA FIRDS");
        final List<Model.Instrument> instruments = new ArrayList<>();
        int id = 0;
        for (final String[] r : rows("/refdata/instruments.csv"))
        {
            instruments.add(new Model.Instrument(id++, r[0], r[1], r[2], r[3], r[4], Double.parseDouble(r[5]),
                LocalDate.parse(r[6]), r[7], Integer.parseInt(r[8]), Integer.parseInt(r[9]),
                r[10].isBlank() ? List.of() : List.of(r[10].split(",")), p));
        }
        return instruments;
    }

    /**
     * A designation plus the delay before it "appears" (simulates a list update during the session).
     */
    public record TimedDesignation(Model.Designation designation, long effectiveAfterSeconds)
    {
    }

    public static List<TimedDesignation> snapshotSanctions()
    {
        final Model.Provenance p = Model.Provenance.snapshot("UK Sanctions List");
        final List<TimedDesignation> list = new ArrayList<>();
        for (final String[] r : rows("/refdata/sanctions.csv"))
        {
            list.add(new TimedDesignation(new Model.Designation(r[0], r[1], r[2], r[3], r[4], p), Long.parseLong(r[5])));
        }
        return list;
    }

    // ------------------------------------------------------------------ GLEIF (live)

    /**
     * Look up LEIs in batches via {@code filter[lei]=a,b,c}.
     */
    public static List<Model.LeiRecord> gleif(final Collection<String> leis) throws IOException, InterruptedException
    {
        final List<Model.LeiRecord> out = new ArrayList<>();
        final List<String> batch = new ArrayList<>();
        for (final String lei : leis)
        {
            batch.add(lei);
            if (batch.size() == 50)
            {
                out.addAll(parseGleif(get(gleifUrl(batch)), Instant.now()));
                batch.clear();
            }
        }
        if (!batch.isEmpty())
        {
            out.addAll(parseGleif(get(gleifUrl(batch)), Instant.now()));
        }
        return out;
    }

    static String gleifUrl(final List<String> leis)
    {
        return GLEIF_API + "?page%5Bsize%5D=" + leis.size() + "&filter%5Blei%5D=" +
            URLEncoder.encode(String.join(",", leis), StandardCharsets.UTF_8);
    }

    public static List<Model.LeiRecord> parseGleif(final String json, final Instant retrievedAt)
    {
        final Object root = Json.parse(json);
        final List<Model.LeiRecord> out = new ArrayList<>();
        if (!(Json.path(root, "data") instanceof List<?> data))
        {
            return out;
        }
        for (int i = 0; i < data.size(); i++)
        {
            final String base = "data." + i + ".attributes.";
            out.add(new Model.LeiRecord(
                Json.string(root, base + "lei"),
                Json.string(root, base + "entity.legalName.name"),
                Json.string(root, base + "entity.status"),
                Json.string(root, base + "registration.status"),
                Json.string(root, base + "entity.legalAddress.country"),
                dateOnly(Json.string(root, base + "registration.nextRenewalDate")),
                null,
                new Model.Provenance("GLEIF API", dateOnly(Json.string(root, base + "registration.lastUpdateDate")),
                    retrievedAt)));
        }
        return out;
    }

    // ------------------------------------------------------------------ UK Sanctions List (live)

    public static List<Model.Designation> ukSanctionsList() throws IOException, InterruptedException
    {
        final HttpResponse<InputStream> response = HTTP.send(request(UK_SANCTIONS_XML), HttpResponse.BodyHandlers.ofInputStream());
        check(response);
        try (InputStream in = response.body())
        {
            return parseUkSanctions(in, Instant.now());
        }
    }

    /**
     * One {@link Model.Designation} per name (primary names and aliases): screening needs all of them.
     */
    public static List<Model.Designation> parseUkSanctions(final InputStream in, final Instant retrievedAt)
    {
        final List<Model.Designation> out = new ArrayList<>();
        try
        {
            final XMLStreamReader xml = xmlFactory().createXMLStreamReader(in);
            final Map<String, String> fields = new HashMap<>();
            final List<String> names = new ArrayList<>();
            final String[] nameParts = new String[6];
            String asOf = null;
            boolean inDesignation = false;
            boolean inName = false;
            final StringBuilder text = new StringBuilder();

            while (xml.hasNext())
            {
                switch (xml.next())
                {
                    case XMLStreamConstants.START_ELEMENT ->
                    {
                        text.setLength(0);
                        final String el = xml.getLocalName();
                        if ("Designation".equals(el))
                        {
                            inDesignation = true;
                            fields.clear();
                            names.clear();
                        }
                        else if (inDesignation && "Name".equals(el))
                        {
                            inName = true;
                            java.util.Arrays.fill(nameParts, null);
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(xml.getText());
                    case XMLStreamConstants.END_ELEMENT ->
                    {
                        final String el = xml.getLocalName();
                        final String value = text.toString().trim();
                        text.setLength(0);
                        if ("DateGenerated".equals(el) && !inDesignation)
                        {
                            asOf = value;
                        }
                        else if (inName && el.startsWith("Name") && el.length() == 5 && Character.isDigit(el.charAt(4)))
                        {
                            nameParts[el.charAt(4) - '1'] = value;
                        }
                        else if (inName && "Name".equals(el))
                        {
                            inName = false;
                            final StringBuilder full = new StringBuilder();
                            for (final String part : nameParts)
                            {
                                if (null != part && !part.isEmpty())
                                {
                                    full.append(full.isEmpty() ? "" : " ").append(part);
                                }
                            }
                            if (!full.isEmpty())
                            {
                                names.add(full.toString());
                            }
                        }
                        else if (inDesignation && !inName && !value.isEmpty())
                        {
                            fields.putIfAbsent(el, value);
                        }
                        if ("Designation".equals(el))
                        {
                            inDesignation = false;
                            final Model.Provenance p = new Model.Provenance("UK Sanctions List", asOf, retrievedAt);
                            for (final String name : names)
                            {
                                out.add(new Model.Designation(fields.get("UniqueID"), name,
                                    fields.get("IndividualEntityShip"), fields.get("RegimeName"),
                                    fields.get("DateDesignated"), p));
                            }
                        }
                    }
                    default ->
                    {
                    }
                }
            }
        }
        catch (final XMLStreamException ex)
        {
            throw new IllegalStateException("UK Sanctions List XML: " + ex.getMessage(), ex);
        }
        return out;
    }

    // ------------------------------------------------------------------ FCA FIRDS (live)

    /**
     * Latest FIRDS file URLs of a type (DLTINS = daily delta, FULINS = full), newest first.
     */
    public static List<String> firdsFiles(final String fileType, final int count) throws IOException, InterruptedException
    {
        final String url = FCA_FIRDS_INDEX + "?q=file_type:" + fileType + "&sort=publication_date:desc&size=" + count;
        final Object root = Json.parse(get(url));
        final List<String> links = new ArrayList<>();
        if (Json.path(root, "hits.hits") instanceof List<?> hits)
        {
            for (int i = 0; i < hits.size(); i++)
            {
                final String link = Json.string(root, "hits.hits." + i + "._source.download_link");
                if (null != link)
                {
                    links.add(link);
                }
            }
        }
        return links;
    }

    /**
     * Stream a FIRDS zip and keep the records for the ISINs we care about; the files are far too big to load.
     */
    public static void firds(final String zipUrl, final Set<String> isins, final Consumer<Map<String, String>> onRecord)
        throws IOException, InterruptedException
    {
        final HttpResponse<InputStream> response = HTTP.send(request(zipUrl), HttpResponse.BodyHandlers.ofInputStream());
        check(response);
        try (ZipInputStream zip = new ZipInputStream(response.body()))
        {
            ZipEntry entry;
            while (null != (entry = zip.getNextEntry()))
            {
                if (entry.getName().endsWith(".xml"))
                {
                    parseFirds(zip, isins, onRecord);
                }
            }
        }
    }

    /**
     * ISO 20022 auth.017 (FULINS) / auth.036 (DLTINS): each instrument is a {@code RefData} element (inside
     * {@code NewRcrd}/{@code ModfdRcrd}/... in deltas). Emits a flat map of the fields we use.
     */
    public static void parseFirds(final InputStream in, final Set<String> isins, final Consumer<Map<String, String>> onRecord)
    {
        try
        {
            final XMLStreamReader xml = xmlFactory().createXMLStreamReader(in);
            final Map<String, String> record = new HashMap<>();
            final List<String> path = new ArrayList<>();
            final StringBuilder text = new StringBuilder();
            int depth = -1;
            String wrapper = null;

            while (xml.hasNext())
            {
                switch (xml.next())
                {
                    case XMLStreamConstants.START_ELEMENT ->
                    {
                        final String el = xml.getLocalName();
                        path.add(el);
                        text.setLength(0);
                        if (depth < 0 && ("RefData".equals(el) || "TermntdRcrd".equals(el) || "NewRcrd".equals(el) ||
                            "ModfdRcrd".equals(el) || "CancRcrd".equals(el)))
                        {
                            depth = path.size();
                            wrapper = el;
                            record.clear();
                        }
                    }
                    case XMLStreamConstants.CHARACTERS -> text.append(xml.getText());
                    case XMLStreamConstants.END_ELEMENT ->
                    {
                        if (depth > 0)
                        {
                            final String value = text.toString().trim();
                            if (!value.isEmpty())
                            {
                                firdsField(path, value, record);
                            }
                            if (path.size() == depth)
                            {
                                record.put("recordType", wrapper);
                                if (isins.contains(record.get("isin")))
                                {
                                    onRecord.accept(Map.copyOf(record));
                                }
                                depth = -1;
                            }
                        }
                        text.setLength(0);
                        path.removeLast();
                    }
                    default ->
                    {
                    }
                }
            }
        }
        catch (final XMLStreamException ex)
        {
            throw new IllegalStateException("FIRDS XML: " + ex.getMessage(), ex);
        }
    }

    private static void firdsField(final List<String> path, final String value, final Map<String, String> record)
    {
        final String el = path.getLast();
        final String parent = path.size() > 1 ? path.get(path.size() - 2) : "";
        switch (el)
        {
            case "Id" ->
            {
                if ("FinInstrmGnlAttrbts".equals(parent))
                {
                    record.put("isin", value);
                }
                else if ("TradgVnRltdAttrbts".equals(parent))
                {
                    record.merge("venues", value, (a, b) -> a.contains(b) ? a : a + "," + b);
                }
            }
            case "FullNm" -> record.put("fullName", value);
            case "ClssfctnTp" -> record.put("cfi", value);
            case "NtnlCcy" -> record.put("currency", value);
            case "Issr" -> record.put("issuerLei", value);
            case "MtrtyDt" -> record.put("maturity", value);
            case "Fxd" -> record.put("couponPct", value);
            case "TermntnDt" -> record.put("terminationDate", value);
            default ->
            {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static XMLInputFactory xmlFactory()
    {
        final XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false); // no XXE from downloaded files
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    private static HttpRequest request(final String url)
    {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .header("Accept", "application/vnd.api+json, application/json, application/xml, */*")
            .header("User-Agent", "aeronfire-refdata/0.1 (demo)")
            .build();
    }

    private static String get(final String url) throws IOException, InterruptedException
    {
        final HttpResponse<String> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
        check(response);
        return response.body();
    }

    private static void check(final HttpResponse<?> response) throws IOException
    {
        if (response.statusCode() / 100 != 2)
        {
            throw new IOException("HTTP " + response.statusCode() + " from " + response.uri());
        }
    }

    private static String dateOnly(final String isoDateTime)
    {
        return null == isoDateTime || isoDateTime.length() < 10 ? isoDateTime : isoDateTime.substring(0, 10);
    }

    private static String blankToNull(final String s)
    {
        return null == s || s.isBlank() ? null : s;
    }
}
