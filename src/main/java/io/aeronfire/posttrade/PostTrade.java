package io.aeronfire.posttrade;

import io.aeronfire.control.Users;
import io.aeronfire.domain.Entitlements;
import io.aeronfire.refdata.Model;
import io.aeronfire.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-trade pipeline: every trade the cluster executes is captured, booked, sent to clearing, reported to
 * the regulator via an ARM and (if off-venue) published through our APA. Anything that can't complete goes to
 * the hospital with a reason; entitled users repair and resubmit. Every step is written to the record keeper.
 * <p>
 * Processing is idempotent per trade id, so replaying the cluster's journal is always safe.
 */
public final class PostTrade
{
    public enum Stage
    {
        BOOKING, CLEARING, REPORT
    }

    public static final class HospitalItem
    {
        public final long id;
        public final long tradeId;
        public final Stage stage;
        public List<String> issues;
        public boolean open = true;
        public String resolvedBy = "";

        HospitalItem(final long id, final long tradeId, final Stage stage, final List<String> issues)
        {
            this.id = id;
            this.tradeId = tradeId;
            this.stage = stage;
            this.issues = issues;
        }
    }

    static final class TradeState
    {
        final TradeEvent event;
        Booking.Booked booked;
        String clearing = "-";
        String report = "-";
        String transparency = "-";
        final Map<String, String> patches = new LinkedHashMap<>();
        Map<String, String> draft;

        TradeState(final TradeEvent event)
        {
            this.event = event;
        }
    }

    private final RefLookup ref;
    private final RecordKeeper keeper;
    private final Simulators.Clearer clearer = new Simulators.Clearer();
    private final Simulators.Arm arm = new Simulators.Arm();
    private final Simulators.Apa apa = new Simulators.Apa();
    private final Entitlements entitlements = Entitlements.withDemoUsers();
    private final Map<Long, TradeState> trades = new LinkedHashMap<>();
    private final Map<Long, HospitalItem> hospital = new LinkedHashMap<>();
    private long nextHospitalId = 1;

    public PostTrade(final RefLookup ref, final RecordKeeper keeper)
    {
        this.ref = ref;
        this.keeper = keeper;
    }

    public RecordKeeper keeper()
    {
        return keeper;
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Rebuild trade and hospital state from the record log after a restart. The log is the source of truth:
     * statuses, open hospital items (with their ids) and field repairs all come back. The simulated clearer,
     * ARM and APA are in-memory stand-ins and start empty, so a resubmit after a restart simply re-sends.
     */
    @SuppressWarnings("unchecked")
    public synchronized int restore()
    {
        int restored = 0;
        for (final RecordKeeper.Record r : keeper.read())
        {
            final Object data = Json.parse(r.data());
            final TradeState s = trades.get(r.tradeId());
            switch (r.type())
            {
                case "TRADE_CAPTURED" ->
                {
                    final TradeEvent ev = TradeEvent.fromJson(data);
                    trades.put(ev.tradeId(), new TradeState(ev));
                    restored++;
                }
                case "BOOKED" ->
                {
                    final Model.Instrument bond = ref.instrument(s.event.instrumentId());
                    s.booked = null == bond ? null : Booking.book(s.event, bond);
                }
                case "CLEARING_ACK", "CLEARING_NACK" -> s.clearing = Json.string(data, "status");
                case "REPORT_ACK" -> s.report = Json.string(data, "status");
                case "REPORT_NACK" -> s.report = "HOSPITAL (ARM NACK)";
                case "APA_PUBLISHED" -> s.transparency = Json.string(data, "mode");
                case "REPAIR" -> s.patches.put(Json.string(data, "field"), Json.string(data, "after"));
                case "HOSPITALISED" ->
                {
                    final long id = ((Number)Json.path(data, "item")).longValue();
                    final List<String> issues = new ArrayList<>();
                    ((List<Object>)Json.path(data, "issues")).forEach((o) -> issues.add(o.toString()));
                    hospital.put(id, new HospitalItem(id, r.tradeId(), Stage.valueOf(Json.string(data, "stage")), issues));
                    nextHospitalId = Math.max(nextHospitalId, id + 1);
                }
                case "STILL_IN_HOSPITAL" ->
                {
                    final HospitalItem item = hospital.get(((Number)Json.path(data, "item")).longValue());
                    final List<String> issues = new ArrayList<>();
                    ((List<Object>)Json.path(data, "issues")).forEach((o) -> issues.add(o.toString()));
                    item.issues = issues;
                }
                case "HOSPITAL_RESOLVED" ->
                {
                    final HospitalItem item = hospital.get(((Number)Json.path(data, "item")).longValue());
                    item.open = false;
                    item.resolvedBy = r.user();
                }
                default ->
                {
                }
            }
        }
        return restored;
    }

    // ------------------------------------------------------------------ pipeline

    public synchronized boolean process(final TradeEvent ev)
    {
        if (trades.containsKey(ev.tradeId()))
        {
            return false;
        }
        final TradeState s = new TradeState(ev);
        trades.put(ev.tradeId(), s);
        keeper.append("TRADE_CAPTURED", ev.tradeId(), "system", ev.toJson());
        book(s, "system");
        report(s, "system");
        return true;
    }

    private void book(final TradeState s, final String user)
    {
        final TradeEvent ev = s.event;
        final Model.Client client = ref.client(ev.clientId());
        final Model.Instrument bond = ref.instrument(ev.instrumentId());
        final List<String> issues = Booking.bookingIssues(client, bond);
        if (!issues.isEmpty())
        {
            hospitalise(s, Stage.BOOKING, issues, user);
            return;
        }
        s.booked = Booking.book(ev, bond);
        keeper.append("BOOKED", ev.tradeId(), user, "{\"settle\":\"" + s.booked.settlementDate() + "\",\"accrued\":" +
            s.booked.accruedAmount() + ",\"principal\":" + s.booked.principal() + ",\"consideration\":" +
            s.booked.consideration() + ",\"ccy\":\"" + s.booked.currency() + "\"}");
        resolve(s, Stage.BOOKING, user);

        final Booking.Instruction instruction = Booking.instruction(ev, s.booked, client, bond);
        final Simulators.Reply reply = clearer.submit(instruction);
        keeper.append("CLEARING_" + (reply.accepted() ? "ACK" : "NACK"), ev.tradeId(), user,
            "{\"instruction\":" + instruction.toJson() + ",\"status\":" + Json.quote(reply.status()) + "}");
        s.clearing = reply.status();
        if (reply.accepted())
        {
            resolve(s, Stage.CLEARING, user);
        }
        else
        {
            hospitalise(s, Stage.CLEARING, reply.detail(), user);
        }
    }

    private void report(final TradeState s, final String user)
    {
        final TradeEvent ev = s.event;
        final Model.Client client = ref.client(ev.clientId());
        final Model.Instrument bond = ref.instrument(ev.instrumentId());
        if (null == bond)
        {
            hospitalise(s, Stage.REPORT, List.of("UNKNOWN_INSTRUMENT"), user);
            return;
        }
        final Map<String, String> draft = Reporting.build(ev, client, bond);
        draft.putAll(s.patches);
        s.draft = draft;

        final List<Reporting.Issue> pre = Reporting.precheck(draft, ref);
        if (Reporting.hasErrors(pre))
        {
            s.report = "HOSPITAL (precheck)";
            hospitalise(s, Stage.REPORT, pre.stream().map(Reporting.Issue::toString).toList(), user);
            return;
        }
        final Simulators.Reply reply = arm.receive(draft, ref, bond);
        keeper.append(reply.accepted() ? "REPORT_ACK" : "REPORT_NACK", ev.tradeId(), user,
            "{\"report\":" + Reporting.json(draft) + ",\"status\":" + Json.quote(reply.status()) + ",\"detail\":" +
            array(reply.detail()) + "}");
        if (reply.accepted())
        {
            s.report = reply.status();
            resolve(s, Stage.REPORT, user);
            if (Reporting.OFF_VENUE.equals(draft.get("venue")) && "-".equals(s.transparency))
            {
                final Reporting.Publication p = apa.publish(ev.tradeId(), Reporting.transparency(ev, bond));
                s.transparency = p.mode() + (p.mode().equals("DEFERRED") ? " until " + java.time.Instant.ofEpochMilli(p.deferredUntilMs()) : "");
                keeper.append("APA_PUBLISHED", ev.tradeId(), user, "{\"mode\":" + Json.quote(p.mode()) +
                    ",\"deferredUntilMs\":" + p.deferredUntilMs() + ",\"detail\":" + Json.quote(p.detail()) + "}");
            }
        }
        else
        {
            s.report = "HOSPITAL (ARM NACK)";
            hospitalise(s, Stage.REPORT, reply.detail(), user);
        }
    }

    // ------------------------------------------------------------------ hospital

    private void hospitalise(final TradeState s, final Stage stage, final List<String> issues, final String user)
    {
        HospitalItem item = open(s.event.tradeId(), stage);
        final boolean isNew = null == item;
        if (isNew)
        {
            item = new HospitalItem(nextHospitalId++, s.event.tradeId(), stage, issues);
            hospital.put(item.id, item);
        }
        else
        {
            item.issues = issues;
        }
        keeper.append(isNew ? "HOSPITALISED" : "STILL_IN_HOSPITAL", s.event.tradeId(), user,
            "{\"item\":" + item.id + ",\"stage\":\"" + stage + "\",\"issues\":" + array(issues) + "}");
    }

    private void resolve(final TradeState s, final Stage stage, final String user)
    {
        final HospitalItem item = open(s.event.tradeId(), stage);
        if (null != item)
        {
            item.open = false;
            item.resolvedBy = user;
            keeper.append("HOSPITAL_RESOLVED", s.event.tradeId(), user, "{\"item\":" + item.id + ",\"stage\":\"" + stage + "\"}");
        }
    }

    private HospitalItem open(final long tradeId, final Stage stage)
    {
        for (final HospitalItem i : hospital.values())
        {
            if (i.open && i.tradeId == tradeId && i.stage == stage)
            {
                return i;
            }
        }
        return null;
    }

    private String authorise(final String user, final int permission)
    {
        return entitlements.has(Users.idOf(user), permission) ? null : "REJECTED: NOT_ENTITLED";
    }

    /**
     * Patch one field of the report behind a REPORT hospital item. Booking and clearing problems are repaired
     * in reference data (e.g. the SSI), then resubmitted.
     */
    public synchronized String repair(
        final String user, final long itemId, final String field, final String value, final String reason)
    {
        final String denied = authorise(user, Entitlements.HOSPITAL);
        if (null != denied)
        {
            return denied;
        }
        final HospitalItem item = hospital.get(itemId);
        if (null == item || !item.open)
        {
            return "REJECTED: no open hospital item " + itemId;
        }
        if (item.stage != Stage.REPORT)
        {
            return "REJECTED: " + item.stage + " problems are fixed in reference data; then resubmit";
        }
        if (null == reason || reason.isBlank())
        {
            return "REJECTED: a reason is required";
        }
        final TradeState s = trades.get(item.tradeId);
        if (null == s.draft)
        {
            s.draft = Reporting.build(s.event, ref.client(s.event.clientId()), ref.instrument(s.event.instrumentId()));
            s.draft.putAll(s.patches);
        }
        if (!s.draft.containsKey(field))
        {
            return "REJECTED: not a report field: " + field;
        }
        final String before = s.draft.get(field);
        s.patches.put(field, value);
        s.draft.put(field, value);
        keeper.append("REPAIR", item.tradeId, user, "{\"item\":" + itemId + ",\"field\":" + Json.quote(field) +
            ",\"before\":" + Json.quote(before) + ",\"after\":" + Json.quote(value) + ",\"reason\":" + Json.quote(reason) + "}");
        return "APPLIED: " + field + " '" + before + "' -> '" + value + "'";
    }

    public synchronized String resubmit(final String user, final long itemId)
    {
        final String denied = authorise(user, Entitlements.HOSPITAL);
        if (null != denied)
        {
            return denied;
        }
        final HospitalItem item = hospital.get(itemId);
        if (null == item || !item.open)
        {
            return "REJECTED: no open hospital item " + itemId;
        }
        ref.refreshNow();
        final TradeState s = trades.get(item.tradeId);
        keeper.append("RESUBMIT", item.tradeId, user, "{\"item\":" + itemId + ",\"stage\":\"" + item.stage + "\"}");
        if (item.stage == Stage.REPORT)
        {
            report(s, user);
        }
        else
        {
            book(s, user);
        }
        return item.open ? "STILL FAILING: " + item.issues : "APPLIED: item " + itemId + " resolved";
    }

    /**
     * Re-run reporting for trades from a journal range (e.g. after an ARM outage). Trades never seen before
     * (service was down) are processed in full. The ARM treats identical resends as duplicates.
     */
    public synchronized String replay(final String user, final List<TradeEvent> events)
    {
        final String denied = authorise(user, Entitlements.REPLAY);
        if (null != denied)
        {
            return denied;
        }
        ref.refreshNow();
        int known = 0;
        int fresh = 0;
        keeper.append("REPLAY_REQUESTED", -1, user, "{\"trades\":" + events.size() + "}");
        for (final TradeEvent ev : events)
        {
            final TradeState s = trades.get(ev.tradeId());
            if (null == s)
            {
                process(ev);
                fresh++;
            }
            else
            {
                report(s, user);
                known++;
            }
        }
        return "APPLIED: replayed " + events.size() + " trades (" + known + " re-reported, " + fresh + " new)";
    }

    // ------------------------------------------------------------------ views

    public synchronized List<HospitalItem> hospital()
    {
        return new ArrayList<>(hospital.values());
    }

    public synchronized String json()
    {
        final StringBuilder sb = new StringBuilder("{\"trades\":[");
        boolean first = true;
        for (final TradeState s : trades.values())
        {
            final TradeEvent e = s.event;
            sb.append(first ? "" : ",").append("{\"tradeId\":").append(e.tradeId()).append(",\"seq\":").append(e.seq())
                .append(",\"client\":").append(e.clientId()).append(",\"instrument\":").append(e.instrumentId())
                .append(",\"side\":\"").append(e.clientBuys() ? "BUY" : "SELL").append("\",\"notional\":").append(e.notional())
                .append(",\"price\":").append(e.price()).append(",\"tvtic\":").append(Json.quote(e.venueTxnId()))
                .append(",\"settle\":").append(Json.quote(null == s.booked ? null : s.booked.settlementDate().toString()))
                .append(",\"consideration\":").append(null == s.booked ? 0 : s.booked.consideration())
                .append(",\"clearing\":").append(Json.quote(s.clearing))
                .append(",\"report\":").append(Json.quote(s.report))
                .append(",\"transparency\":").append(Json.quote(s.transparency)).append('}');
            first = false;
        }
        sb.append("],\"hospital\":[");
        first = true;
        for (final HospitalItem i : hospital.values())
        {
            sb.append(first ? "" : ",").append("{\"id\":").append(i.id).append(",\"trade\":").append(i.tradeId)
                .append(",\"stage\":\"").append(i.stage).append("\",\"open\":").append(i.open)
                .append(",\"resolvedBy\":").append(Json.quote(i.resolvedBy)).append(",\"issues\":").append(array(i.issues)).append('}');
            first = false;
        }
        return sb.append("],\"clearer\":").append(clearer.count()).append(",\"arm\":").append(arm.count())
            .append(",\"apa\":").append(apa.count()).append(",\"records\":").append(keeper.size()).append('}').toString();
    }

    private static String array(final List<String> items)
    {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++)
        {
            sb.append(i > 0 ? "," : "").append(Json.quote(items.get(i)));
        }
        return sb.append(']').toString();
    }
}
