package io.aeronfire.refdata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefDataTest
{
    @Test
    void identifiersChecksum()
    {
        assertTrue(Identifiers.isValidLei("5493001KJTIIGC8Y1R12")); // a real, well-known checksum-valid LEI
        assertFalse(Identifiers.isValidLei("5493001KJTIIGC8Y1R13"));
        assertTrue(Identifiers.isValidIsin("US0378331005")); // Apple
        assertFalse(Identifiers.isValidIsin("US0378331006"));
    }

    @Test
    void snapshotsAreInternallyConsistent()
    {
        for (final Model.Client c : Sources.snapshotClients())
        {
            if (null != c.lei())
            {
                assertTrue(Identifiers.isValidLei(c.lei()), "client LEI " + c.lei());
            }
        }
        for (final Model.LeiRecord l : Sources.snapshotLeis())
        {
            assertTrue(Identifiers.isValidLei(l.lei()), "lei " + l.lei());
        }
        for (final Model.Instrument i : Sources.snapshotInstruments())
        {
            assertTrue(Identifiers.isValidIsin(i.isin()), "isin " + i.isin());
            assertTrue(Identifiers.isValidLei(i.issuerLei()), "issuer " + i.issuerLei());
        }
    }

    @Test
    void screeningMatchesNameVariantsButNotUnrelated()
    {
        assertEquals(1.0, Screening.score("Roshan Money Exchange FZE", "ROSHAN MONEY EXCHANGE"));
        assertTrue(Screening.score("Kestrel Capital LLP", "ROSHAN MONEY EXCHANGE") < Screening.THRESHOLD);
        assertTrue(Screening.score("Rahat Limited", "RAHAT LTD.") >= Screening.THRESHOLD);
    }

    @Test
    void midSessionListUpdateBlocksClient8AndKycBlocksClient6()
    {
        final RefDataStore store = new RefDataStore();
        store.load(Sources.snapshotClients(), Sources.snapshotLeis(), Sources.snapshotInstruments(),
            Sources.snapshotSanctions());
        final LocalDate today = LocalDate.of(2026, 10, 23);

        assertFalse(store.verdicts(today, 0).get(8).sanctioned());
        assertTrue(store.verdicts(today, 60).get(8).sanctioned());
        assertFalse(store.verdicts(today, 0).get(6).kycValid());
        assertTrue(store.verdicts(today, 0).get(0).kycValid());
        assertFalse(store.verdicts(today, 60).get(0).sanctioned());
        assertTrue(store.verdicts(today, 0).get(9).warnings().stream().anyMatch((w) -> w.contains("no LEI")));
        assertTrue(store.verdicts(today, 0).get(5).warnings().stream().anyMatch((w) -> w.contains("no SSI")));
        assertTrue(store.verdicts(today, 0).get(7).warnings().stream().anyMatch((w) -> w.contains("LAPSED")));
    }

    @Test
    void overridesAndDiscountsAreAudited()
    {
        final RefDataStore store = new RefDataStore();
        store.load(Sources.snapshotClients(), Sources.snapshotLeis(), Sources.snapshotInstruments(),
            Sources.snapshotSanctions());

        assertTrue(store.override("refdata", 5, "ssiBic", "ABCDGB2L", "").startsWith("REJECTED"));
        assertTrue(store.override("refdata", 5, "lei", "AF00BADBADBADBADBAD0", "typo fix").startsWith("REJECTED"));
        assertTrue(store.override("refdata", 5, "ssiBic", "ABCDGB2L", "SSI received").startsWith("APPLIED"));
        assertEquals("ABCDGB2L", store.client(5).ssiBic());
        assertTrue(store.discount("compliance", 8, "AFG0002", "different entity, DoB checked").startsWith("APPLIED"));
        assertFalse(store.verdicts(LocalDate.of(2026, 10, 23), 60).get(8).sanctioned());
        assertEquals(2, store.audit().size());
        assertEquals("OVERRIDE", store.audit().get(0).action());
    }

    @Test
    void parsesGleifResponse()
    {
        final String json = """
            {"data":[{"type":"lei-records","attributes":{"lei":"5493001KJTIIGC8Y1R12",
            "entity":{"legalName":{"name":"Bloomberg Finance L.P."},"status":"ACTIVE","legalAddress":{"country":"US"}},
            "registration":{"status":"ISSUED","nextRenewalDate":"2027-01-01T00:00:00Z"}}}]}""";
        final List<Model.LeiRecord> out = Sources.parseGleif(json, Instant.EPOCH);
        assertEquals(1, out.size());
        assertEquals("Bloomberg Finance L.P.", out.get(0).legalName());
        assertEquals("ISSUED", out.get(0).registrationStatus());
        assertEquals("2027-01-01", out.get(0).nextRenewalDate());
    }

    @Test
    void parsesUkSanctionsXmlIncludingAliases()
    {
        final String xml = """
            <?xml version="1.0"?><Designations><DateGenerated>2026-10-01</DateGenerated>
            <Designation><UniqueID>AFG0002</UniqueID><RegimeName>Afghanistan</RegimeName>
            <IndividualEntityShip>Entity</IndividualEntityShip><DateDesignated>29/06/2012</DateDesignated>
            <Names><Name><Name1>ROSHAN MONEY EXCHANGE</Name1><NameType>Primary Name</NameType></Name>
            <Name><Name1>ROSHAN</Name1><Name2>SARAFI</Name2><NameType>Alias</NameType></Name></Names></Designation>
            </Designations>""";
        final List<Model.Designation> out = Sources.parseUkSanctions(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Instant.EPOCH);
        assertEquals(2, out.size());
        assertEquals("AFG0002", out.get(0).uniqueId());
        assertEquals("ROSHAN SARAFI", out.get(1).name());
        assertEquals("Entity", out.get(0).type());
    }

    @Test
    void parsesFirdsRecordsForWatchedIsinsOnly()
    {
        final String xml = """
            <Document><FinInstrmRptgRefDataDltaRpt><RefData>
            <FinInstrmGnlAttrbts><Id>XS2847193054</Id><FullNm>ACME 7.25% 2031</FullNm><ClssfctnTp>DBFTFB</ClssfctnTp>
            <NtnlCcy>EUR</NtnlCcy></FinInstrmGnlAttrbts><Issr>AF00Q9GNPW77XVWWK588</Issr>
            <TradgVnRltdAttrbts><Id>TREU</Id></TradgVnRltdAttrbts>
            <DbtInstrmAttrbts><MtrtyDt>2031-06-15</MtrtyDt></DbtInstrmAttrbts></RefData>
            <RefData><FinInstrmGnlAttrbts><Id>GB0000000009</Id></FinInstrmGnlAttrbts></RefData>
            </FinInstrmRptgRefDataDltaRpt></Document>""";
        final List<Map<String, String>> out = new ArrayList<>();
        Sources.parseFirds(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
            Set.of("XS2847193054"), out::add);
        assertEquals(1, out.size());
        assertEquals("EUR", out.get(0).get("currency"));
        assertEquals("TREU", out.get(0).get("venues"));
        assertEquals("2031-06-15", out.get(0).get("maturity"));
        assertEquals("AF00Q9GNPW77XVWWK588", out.get(0).get("issuerLei"));
    }
}
