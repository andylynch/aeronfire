package io.aeronfire.posttrade;

import io.aeronfire.refdata.RefDataStore;
import io.aeronfire.refdata.Sources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostTradeTest
{
    private static final long T = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli(); // a Friday

    private final RefDataStore store = new RefDataStore();

    private PostTrade newPostTrade() throws IOException
    {
        store.load(Sources.snapshotClients(), Sources.snapshotLeis(), Sources.snapshotInstruments(),
            Sources.snapshotSanctions());
        return new PostTrade(RefLookup.of(store), new RecordKeeper(Files.createTempDirectory("pt").resolve("records.jsonl")));
    }

    private static TradeEvent trade(final long id, final int client, final int instrument, final boolean tvtic)
    {
        return new TradeEvent(id, T, id, id, id, client, instrument, true, 2_000_000, 98_500_000, tvtic ? "TW20261023" + id : "");
    }

    @Test
    void settlementSkipsWeekendsAndAccruesFromLastCoupon()
    {
        assertEquals(LocalDate.of(2026, 10, 27), Booking.addBusinessDays(LocalDate.of(2026, 10, 23), 2));
        final var bond = Sources.snapshotInstruments().get(0); // 7.25% annual on 15 June, 30/360
        // 15 Jun -> 27 Oct 2026 = 4 months + 12 days = 132 days of 360
        assertEquals(7.25 * 132 / 360, Booking.accruedPer100(bond, LocalDate.of(2026, 10, 27)), 1e-9);
    }

    @Test
    void cleanTradeIsBookedClearedReportedAndOnVenueNeedsNoApa() throws IOException
    {
        final PostTrade pt = newPostTrade();
        assertTrue(pt.process(trade(1, 0, 0, true)));
        assertFalse(pt.process(trade(1, 0, 0, true)), "idempotent");
        assertTrue(pt.hospital().isEmpty());
        final String json = pt.json();
        assertTrue(json.contains("\"report\":\"ACCEPTED\""));
        assertTrue(json.contains("\"clearing\":\"MATCHED\""));
        assertTrue(json.contains("\"apa\":0"));
        assertEquals(null, pt.keeper().verify());
    }

    @Test
    void offVenueTradeIsPublishedByApa() throws IOException
    {
        final PostTrade pt = newPostTrade();
        pt.process(trade(2, 0, 0, false));
        assertTrue(pt.json().contains("\"apa\":1"));
        assertTrue(pt.json().contains("\"transparency\":\"IMMEDIATE\"")); // 2mm is below the size threshold

        pt.process(new TradeEvent(9, T, 9, 9, 9, 0, 0, true, 10_000_000, 98_500_000, ""));
        assertTrue(pt.json().contains("\"transparency\":\"DEFERRED until 2026-10-24"));
    }

    @Test
    void venueMismatchGoesToHospitalAndIsRepairedAndResubmitted() throws IOException
    {
        final PostTrade pt = newPostTrade();
        pt.process(trade(3, 0, 2, true)); // Globex is not admitted to TREU but arrived with a TVTIC
        assertEquals(1, pt.hospital().size());
        assertTrue(pt.hospital().get(0).issues.get(0).contains("INSTRUMENT_NOT_ADMITTED"));

        assertEquals("REJECTED: NOT_ENTITLED", pt.repair("sales", 1, "venue", "XOFF", "x"));
        assertTrue(pt.repair("ops", 1, "venue", "XOFF", "").startsWith("REJECTED"));
        assertTrue(pt.repair("ops", 1, "venue", "XOFF", "not admitted to the venue per FIRDS").startsWith("APPLIED"));
        assertTrue(pt.resubmit("ops", 1).startsWith("STILL FAILING")); // TVTIC must go with it
        assertTrue(pt.repair("ops", 1, "tvtic", "", "off-venue trade").startsWith("APPLIED"));
        assertTrue(pt.resubmit("ops", 1).startsWith("APPLIED"));
        assertFalse(pt.hospital().get(0).open);
        assertTrue(pt.json().contains("\"apa\":1"), "now off-venue, so published");
        assertEquals(null, pt.keeper().verify());
    }

    @Test
    void missingLeiIsCaughtBeforeSubmissionAndLapsedLeiOnlyWarns() throws IOException
    {
        final PostTrade pt = newPostTrade();
        pt.process(trade(4, 9, 0, true)); // Sable: no LEI
        assertTrue(pt.hospital().get(0).issues.get(0).contains("MISSING_LEI"));
        pt.process(trade(5, 7, 0, true)); // Cedar Ridge: LEI lapsed
        assertEquals(1, pt.hospital().size());
        assertTrue(pt.json().contains("\"report\":\"ACCEPTED\""));
    }

    @Test
    void missingSsiBlocksBookingUntilReferenceDataIsRepaired() throws IOException
    {
        final PostTrade pt = newPostTrade();
        pt.process(trade(6, 5, 0, true));
        assertTrue(pt.hospital().get(0).issues.get(0).contains("MISSING_SSI"));
        assertTrue(pt.repair("ops", 1, "x", "y", "z").startsWith("REJECTED: BOOKING problems"));
        store.override("refdata", 5, "ssiBic", "ABCDGB2L", "SSI received");
        store.override("refdata", 5, "ssiAccount", "EC-5551234", "SSI received");
        assertTrue(pt.resubmit("ops", 1).startsWith("APPLIED"));
        assertTrue(pt.json().contains("\"clearing\":\"MATCHED\""));
    }

    @Test
    void replayIsIdempotentAndPicksUpMissedTrades() throws IOException
    {
        final PostTrade pt = newPostTrade();
        pt.process(trade(7, 0, 0, true));
        final String before = pt.json();
        assertEquals("REJECTED: NOT_ENTITLED", pt.replay("sales", List.of(trade(7, 0, 0, true))));
        final String result = pt.replay("ops", List.of(trade(7, 0, 0, true), trade(8, 1, 1, true)));
        assertEquals("APPLIED: replayed 2 trades (1 re-reported, 1 new)", result);
        assertTrue(pt.json().contains("\"arm\":2"));
        assertTrue(pt.json().contains("\"tradeId\":8"));
        assertFalse(before.contains("\"tradeId\":8"));
    }

    @Test
    void recordKeeperDetectsTampering() throws IOException
    {
        final Path file = Files.createTempDirectory("rk").resolve("r.jsonl");
        final RecordKeeper rk = new RecordKeeper(file);
        rk.append("A", 1, "system", "{\"x\":1}");
        rk.append("B", 1, "ops", "{\"x\":2}");
        rk.append("C", 2, "ops", "{\"x\":3}");
        assertEquals(null, rk.verify());
        assertEquals(3, new RecordKeeper(file).size(), "reopens and continues the chain");
        assertEquals(Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusYears(5).toString(), rk.read().get(0).retainUntil());

        Files.writeString(file, Files.readString(file).replace("\"x\":2", "\"x\":9"));
        assertTrue(rk.verify().contains("record 1"));
    }

    @Test
    void stateIsRebuiltFromTheRecordLogAfterARestart() throws IOException
    {
        store.load(Sources.snapshotClients(), Sources.snapshotLeis(), Sources.snapshotInstruments(),
            Sources.snapshotSanctions());
        final Path file = Files.createTempDirectory("restore").resolve("r.jsonl");
        final PostTrade first = new PostTrade(RefLookup.of(store), new RecordKeeper(file));
        first.process(trade(1, 0, 0, true));
        first.process(trade(2, 0, 2, true)); // venue mismatch -> hospital item 1
        first.process(trade(3, 5, 0, true)); // missing SSI -> hospital item 2
        first.repair("ops", 1, "venue", "XOFF", "not admitted");

        final PostTrade second = new PostTrade(RefLookup.of(store), new RecordKeeper(file));
        assertEquals(3, second.restore());
        assertEquals(2, second.hospital().size());
        assertTrue(second.hospital().get(0).open);
        assertEquals("MISSING_SSI", second.hospital().get(1).issues.get(0).substring(0, 11));
        assertFalse(second.process(trade(1, 0, 0, true)), "already processed before the restart");
        assertTrue(second.repair("ops", 1, "tvtic", "", "off-venue").startsWith("APPLIED"));
        assertTrue(second.resubmit("ops", 1).startsWith("APPLIED"), "earlier venue patch survived the restart");
        assertTrue(second.json().contains("\"report\":\"ACCEPTED\""));
        assertEquals(null, second.keeper().verify());
    }
}
