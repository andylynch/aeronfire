package io.aeronfire.demo;

import io.aeronfire.util.Json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What the panel did and what the system did about it, newest last. This is the story the audience reads:
 * "killed the leader" ... "new leader elected in 1.4s" ... "trading resumed".
 */
final class Timeline
{
    private static final int MAX_ENTRIES = 200;

    record Entry(long timeMs, String kind, String text)
    {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    synchronized void add(final String kind, final String text)
    {
        entries.addLast(new Entry(System.currentTimeMillis(), kind, text));
        while (entries.size() > MAX_ENTRIES)
        {
            entries.removeFirst();
        }
        System.out.printf("demo [%s] %s%n", kind, text);
    }

    synchronized String toJson(final int limit)
    {
        final Entry[] all = entries.toArray(new Entry[0]);
        final StringBuilder sb = new StringBuilder("[");
        for (int i = Math.max(0, all.length - limit); i < all.length; i++)
        {
            sb.append(sb.length() > 1 ? "," : "")
                .append("{\"t\":").append(all[i].timeMs())
                .append(",\"kind\":").append(Json.quote(all[i].kind()))
                .append(",\"text\":").append(Json.quote(all[i].text())).append('}');
        }
        return sb.append(']').toString();
    }
}
