package io.aeronfire.posttrade;

import io.aeronfire.util.Json;

import java.util.Map;

/**
 * A TRADE event from the cluster's journal, as the post-trade side sees it. Side is the client's side:
 * BUY means the client bought and we (the dealer) sold.
 */
public record TradeEvent(
    long seq,
    long timestampMs,
    long tradeId,
    long rfqId,
    long quoteId,
    int clientId,
    int instrumentId,
    boolean clientBuys,
    long notional,
    long priceE6,
    String venueTxnId)
{
    public double price()
    {
        return priceE6 / 1_000_000.0;
    }

    public String toJson()
    {
        return "{\"seq\":" + seq + ",\"ts\":" + timestampMs + ",\"tradeId\":" + tradeId + ",\"rfqId\":" + rfqId +
            ",\"quoteId\":" + quoteId + ",\"clientId\":" + clientId + ",\"instrumentId\":" + instrumentId +
            ",\"clientBuys\":" + clientBuys + ",\"notional\":" + notional + ",\"priceE6\":" + priceE6 +
            ",\"tvtic\":" + Json.quote(venueTxnId) + "}";
    }

    @SuppressWarnings("unchecked")
    public static TradeEvent fromJson(final Object o)
    {
        final Map<String, Object> m = (Map<String, Object>)o;
        return new TradeEvent(l(m, "seq"), l(m, "ts"), l(m, "tradeId"), l(m, "rfqId"), l(m, "quoteId"),
            (int)l(m, "clientId"), (int)l(m, "instrumentId"), Boolean.TRUE.equals(m.get("clientBuys")),
            l(m, "notional"), l(m, "priceE6"), (String)m.get("tvtic"));
    }

    private static long l(final Map<String, Object> m, final String key)
    {
        return ((Number)m.get(key)).longValue();
    }
}
