package io.aeronfire.demo;

import io.aeronfire.util.Json;

import java.util.List;
import java.util.Map;

/**
 * Writes a tree parsed by {@link Json} back out, so the panel can pass parts of the monitor's state straight
 * through without modelling every field.
 */
final class JsonOut
{
    private JsonOut()
    {
    }

    static String of(final Object value)
    {
        final StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    static void write(final StringBuilder sb, final Object value)
    {
        if (null == value)
        {
            sb.append("null");
        }
        else if (value instanceof Map<?, ?> map)
        {
            sb.append('{');
            boolean first = true;
            for (final Map.Entry<?, ?> e : map.entrySet())
            {
                sb.append(first ? "" : ",").append(Json.quote(String.valueOf(e.getKey()))).append(':');
                write(sb, e.getValue());
                first = false;
            }
            sb.append('}');
        }
        else if (value instanceof List<?> list)
        {
            sb.append('[');
            for (int i = 0; i < list.size(); i++)
            {
                sb.append(i == 0 ? "" : ",");
                write(sb, list.get(i));
            }
            sb.append(']');
        }
        else if (value instanceof Double d)
        {
            if (d == Math.rint(d) && Math.abs(d) < 1e15)
            {
                sb.append((long)d.doubleValue());
            }
            else
            {
                sb.append(d);
            }
        }
        else if (value instanceof Boolean || value instanceof Number)
        {
            sb.append(value);
        }
        else
        {
            sb.append(Json.quote(value.toString()));
        }
    }
}
