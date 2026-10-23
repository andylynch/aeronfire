package io.aeronfire.monitor;

import io.aeronfire.domain.Tier;
import io.aeronfire.sbe.RejectReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorModelTest
{
    @Test
    void tradesMovePositionsAndCredit()
    {
        final MonitorModel model = new MonitorModel();
        model.onCredit(3, Tier.TIER_2, 200_000_000L, 0);
        model.onQuote(1_000, 3, 1, true, 5_000_000L, 95_000_000L, 560);
        model.onTrade(1_001, 3, 1, true, 5_000_000L, 95_000_000L, 5_000_000L);

        assertEquals(-5_000_000L, model.positions[1], "client bought, so we're short");
        assertEquals(5_000_000L, model.utilised[3]);
        assertEquals(1.0, model.hitRatio());
        assertTrue(model.cs01(1) > 0, "short credit gains when spreads widen");
    }

    @Test
    void rejectsAreCountedByReason()
    {
        final MonitorModel model = new MonitorModel();
        model.onReject(1, RejectReason.CREDIT_LIMIT, true);
        model.onReject(2, RejectReason.CREDIT_LIMIT, true);
        model.onReject(3, RejectReason.QUOTE_EXPIRED, false);

        assertEquals(3, model.rejects());
        assertEquals(2, model.rejectsByReason[RejectReason.CREDIT_LIMIT.value()]);
    }

    @Test
    void recentEventsAreBounded()
    {
        final MonitorModel model = new MonitorModel();
        for (int i = 0; i < MonitorModel.RECENT_EVENTS + 10; i++)
        {
            model.onQuote(i, 0, 0, true, 1, 1, 1);
        }
        assertEquals(MonitorModel.RECENT_EVENTS, model.recent.size());
    }

    @Test
    void jsonCarriesEachSection()
    {
        final MonitorModel model = new MonitorModel();
        model.onCredit(0, Tier.TIER_1, 500_000_000L, 0);
        final String json = model.toJson(10_000);
        for (final String key : new String[] { "\"cluster\"", "\"flow\"", "\"positions\"", "\"credit\"", "\"recent\"" })
        {
            assertTrue(json.contains(key), key);
        }
        assertTrue(json.startsWith("{") && json.endsWith("}"));
    }

    @Test
    void rateCountsCompleteSecondsOnly()
    {
        final MonitorModel.RateMeter meter = new MonitorModel.RateMeter();
        for (int s = 0; s < 4; s++)
        {
            for (int i = 0; i < 10; i++)
            {
                meter.mark(s * 1_000L + i);
            }
        }
        assertEquals(10.0, meter.perSecond(4_000), 1e-9);
    }
}
