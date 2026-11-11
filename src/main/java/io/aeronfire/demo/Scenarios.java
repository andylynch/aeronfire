package io.aeronfire.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Risk-control scenarios a presenter can trigger and undo. Each one makes a real change through the control
 * plane (so entitlements, four-eyes approval and the audit trail all apply) and names the reject reason the
 * flow should start showing. Anything switched on is switched off again after a while, so a self-serve visitor
 * can't leave the demo broken for the next one.
 */
final class Scenarios
{
    record Def(String id, String title, String story, String expect)
    {
    }

    /** Told when a scenario starts, so the panel can time how fast the control bites. */
    interface Observer
    {
        void onScenario(String id, String title, String expectedReason);
    }

    interface Undo
    {
        String run() throws IOException;
    }

    private static final String RISK = "risk";
    private static final String COMPLIANCE = "compliance";
    private static final int[] TIER3_CLIENTS = { 6, 7, 8, 9 };
    private static final int SANCTIONED_CLIENT = 3;

    private static final List<Def> DEFS = List.of(
        new Def("credit", "Credit breach",
            "Cut the credit lines of the four tier-3 clients to 1mm. Their next RFQs are refused before a quote is "
                + "priced, and the same check runs again on accept.", "CREDIT_LIMIT"),
        new Def("halt", "Halt an instrument",
            "Halt trading in one loan. Quotes stop for it; the other four loans keep flowing.", "INSTRUMENT_HALTED"),
        new Def("sanctions", "Sanctions hit",
            "Flag a client as sanctioned. Blocking is instant; lifting it needs a second person to approve.", "SANCTIONS"),
        new Def("kill-switch", "Kill switch",
            "Stop all quoting at once. Releasing it is four-eyes: one user requests, a different user approves.",
            "KILL_SWITCH"),
        new Def("throttle", "Throttle runaway flow",
            "Cap the cluster at 3 RFQs a second. Everything above that is rejected as THROTTLED.", "THROTTLED"),
        new Def("collar", "Tight price collar",
            "Squeeze the price collar to 1bp so any quote that moves further from reference is refused.",
            "PRICE_COLLAR"));

    private final ControlClient control;
    private final Timeline timeline;
    private final ScheduledExecutorService scheduler;
    private final Observer observer;
    private final long autoRevertSeconds;
    private final Map<String, Undo> active = new LinkedHashMap<>();
    private final Map<String, ScheduledFuture<?>> reverts = new HashMap<>();
    private final Map<String, Long> revertAtMs = new HashMap<>();
    /** Read by every state poll without taking this object's lock, which is held while control-plane calls run. */
    private volatile Map<String, Long> activeSnapshot = Map.of();
    private int baselineCollar = -1;
    private int baselineMaxRfqs = -1;

    Scenarios(
        final ControlClient control,
        final Timeline timeline,
        final ScheduledExecutorService scheduler,
        final Observer observer,
        final long autoRevertSeconds)
    {
        this.control = control;
        this.timeline = timeline;
        this.scheduler = scheduler;
        this.observer = observer;
        this.autoRevertSeconds = autoRevertSeconds;
    }

    static List<Def> defs()
    {
        return DEFS;
    }

    Map<String, Long> activeWithRevertTime()
    {
        return activeSnapshot;
    }

    private void publishSnapshot()
    {
        final Map<String, Long> out = new LinkedHashMap<>();
        for (final String id : active.keySet())
        {
            out.put(id, revertAtMs.getOrDefault(id, 0L));
        }
        activeSnapshot = Map.copyOf(out);
    }

    synchronized String run(final String id) throws IOException
    {
        final Def def = find(id);
        if (active.containsKey(id))
        {
            return def.title() + " is already on";
        }

        final Undo undo = switch (id)
        {
            case "credit" -> creditBreach();
            case "halt" -> haltInstrument();
            case "sanctions" -> sanctionsHit();
            case "kill-switch" -> killSwitch();
            case "throttle" -> algo(id);
            case "collar" -> algo(id);
            default -> throw new IllegalArgumentException("unknown scenario: " + id);
        };
        active.put(id, undo);
        timeline.add("scenario", def.title() + " ON: " + def.story());
        observer.onScenario(id, def.title(), def.expect());
        if (autoRevertSeconds > 0)
        {
            revertAtMs.put(id, System.currentTimeMillis() + autoRevertSeconds * 1_000);
            reverts.put(id, scheduler.schedule(() -> autoRevert(id), autoRevertSeconds, TimeUnit.SECONDS));
        }
        publishSnapshot();
        return def.title() + " on";
    }

    synchronized String undo(final String id) throws IOException
    {
        final Def def = find(id);
        final Undo undo = active.get(id);
        if (null == undo)
        {
            return def.title() + " is not on";
        }
        final String how = undo.run();
        active.remove(id);
        revertAtMs.remove(id);
        final ScheduledFuture<?> pending = reverts.remove(id);
        if (null != pending)
        {
            pending.cancel(false);
        }
        publishSnapshot();
        timeline.add("heal", def.title() + " OFF" + (how.isEmpty() ? "" : ": " + how));
        return def.title() + " off";
    }

    synchronized List<String> undoAll()
    {
        final List<String> failures = new ArrayList<>();
        for (final String id : new ArrayList<>(active.keySet()))
        {
            try
            {
                undo(id);
            }
            catch (final IOException | RuntimeException ex)
            {
                failures.add(id + ": " + ex.getMessage());
            }
        }
        return failures;
    }

    private void autoRevert(final String id)
    {
        try
        {
            timeline.add("info", "Auto-revert: " + find(id).title() + " has been on for " + autoRevertSeconds + "s");
            undo(id);
        }
        catch (final IOException | RuntimeException ex)
        {
            timeline.add("error", "auto-revert of " + id + " failed: " + ex.getMessage());
        }
    }

    private Undo creditBreach() throws IOException
    {
        final Object state = control.state(RISK);
        final Map<Integer, Double> original = new HashMap<>();
        if (io.aeronfire.util.Json.path(state, "credit") instanceof List<?> lines)
        {
            for (final Object line : lines)
            {
                if (line instanceof Map<?, ?> m && m.get("client") instanceof Number client && m.get("limit") instanceof Number limit)
                {
                    original.put(client.intValue(), limit.doubleValue());
                }
            }
        }
        for (final int client : TIER3_CLIENTS)
        {
            ok(control.act(RISK, "creditLine", "clientId", Integer.toString(client), "tier", "3", "limitMm", "1"));
        }
        return () ->
        {
            for (final int client : TIER3_CLIENTS)
            {
                final double limitMm = original.getOrDefault(client, 50_000_000.0) / 1_000_000.0;
                ok(control.act(RISK, "creditLine", "clientId", Integer.toString(client), "tier", "3",
                    "limitMm", Double.toString(limitMm)));
            }
            return "tier-3 credit lines restored";
        };
    }

    private Undo haltInstrument() throws IOException
    {
        ok(control.act(RISK, "halt", "instrumentId", "0"));
        return () ->
        {
            ok(control.act(RISK, "resume", "instrumentId", "0"));
            return "trading resumed";
        };
    }

    private Undo sanctionsHit() throws IOException
    {
        ok(control.act(COMPLIANCE, "eligibility", "clientId", Integer.toString(SANCTIONED_CLIENT),
            "kycValid", "true", "sanctioned", "true"));
        return () ->
        {
            ok(control.act(COMPLIANCE, "eligibility", "clientId", Integer.toString(SANCTIONED_CLIENT),
                "kycValid", "true", "sanctioned", "false"));
            return approvePending(COMPLIANCE);
        };
    }

    private Undo killSwitch() throws IOException
    {
        ok(control.act(RISK, "engageKillSwitch"));
        return () ->
        {
            ok(control.act(RISK, "releaseKillSwitch"));
            return approvePending(RISK);
        };
    }

    /**
     * Throttle and collar share one control, so each change is recomputed from the original values and
     * whichever other scenario is still on.
     */
    private Undo algo(final String id) throws IOException
    {
        if (!active.containsKey("throttle") && !active.containsKey("collar"))
        {
            final Object state = control.state(RISK);
            baselineCollar = number(state, "priceCollarBps", 0);
            baselineMaxRfqs = number(state, "maxRfqsPerSecond", 0);
        }
        setAlgo(id, true);
        return () ->
        {
            setAlgo(id, false);
            return "limits restored";
        };
    }

    private void setAlgo(final String id, final boolean on) throws IOException
    {
        final boolean throttle = "throttle".equals(id) ? on : active.containsKey("throttle");
        final boolean collar = "collar".equals(id) ? on : active.containsKey("collar");
        ok(control.act(RISK, "algo",
            "collarBps", Integer.toString(collar ? 1 : baselineCollar),
            "maxRfqs", Integer.toString(throttle ? 3 : baselineMaxRfqs)));
    }

    /**
     * Four-eyes: approve, as a different user, whatever the requester has waiting.
     */
    private String approvePending(final String requester) throws IOException
    {
        final String approvingUser = RISK.equals(requester) ? COMPLIANCE : RISK;
        final Object state = control.state(approvingUser);
        final List<String> approved = new ArrayList<>();
        if (io.aeronfire.util.Json.path(state, "pending") instanceof List<?> pending)
        {
            for (final Object p : pending)
            {
                if (p instanceof Map<?, ?> m && requester.equals(m.get("by")) && m.get("id") instanceof Number n)
                {
                    ok(control.act(approvingUser, "approve", "id", Long.toString(n.longValue())));
                    approved.add("#" + n.longValue() + " " + m.get("what"));
                }
            }
        }
        if (approved.isEmpty())
        {
            return "";
        }
        timeline.add("info", "Four-eyes: " + requester + " requested, " + approvingUser + " approved " + approved);
        return "approved by " + approvingUser;
    }

    private static Def find(final String id)
    {
        for (final Def def : DEFS)
        {
            if (def.id().equals(id))
            {
                return def;
            }
        }
        throw new IllegalArgumentException("unknown scenario: " + id);
    }

    private static int number(final Object state, final String path, final int fallback)
    {
        return io.aeronfire.util.Json.path(state, path) instanceof Number n ? n.intValue() : fallback;
    }

    private static void ok(final String result) throws IOException
    {
        if (result.startsWith("REJECTED"))
        {
            throw new IOException(result);
        }
    }
}
