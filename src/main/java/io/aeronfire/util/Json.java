package io.aeronfire.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the open-data APIs (GLEIF, FCA): objects become {@code Map}, arrays {@code List},
 * numbers {@code Double}, plus {@code String}, {@code Boolean} and {@code null}. Enough for reading API
 * responses; not a general-purpose library.
 */
public final class Json
{
    private final String text;
    private int pos;

    private Json(final String text)
    {
        this.text = text;
    }

    public static Object parse(final String text)
    {
        final Json json = new Json(text);
        final Object value = json.value();
        json.skipWhitespace();
        if (json.pos != text.length())
        {
            throw json.error("trailing characters");
        }
        return value;
    }

    /**
     * Walk a path like {@code "data.0.attributes.lei"}; returns null if any step is missing.
     */
    @SuppressWarnings("unchecked")
    public static Object path(final Object root, final String path)
    {
        Object current = root;
        for (final String step : path.split("\\."))
        {
            if (current instanceof Map<?, ?> map)
            {
                current = ((Map<String, Object>)map).get(step);
            }
            else if (current instanceof List<?> list && step.chars().allMatch(Character::isDigit))
            {
                final int index = Integer.parseInt(step);
                current = index < list.size() ? list.get(index) : null;
            }
            else
            {
                return null;
            }
        }
        return current;
    }

    public static String string(final Object root, final String path)
    {
        final Object value = path(root, path);
        return null == value ? null : value.toString();
    }

    private Object value()
    {
        skipWhitespace();
        if (pos >= text.length())
        {
            throw error("unexpected end");
        }
        final char c = text.charAt(pos);
        return switch (c)
        {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object()
    {
        final Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}')
        {
            pos++;
            return map;
        }
        while (true)
        {
            skipWhitespace();
            final String key = string();
            skipWhitespace();
            expect(':');
            map.put(key, value());
            skipWhitespace();
            if (peek() == ',')
            {
                pos++;
            }
            else
            {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array()
    {
        final List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']')
        {
            pos++;
            return list;
        }
        while (true)
        {
            list.add(value());
            skipWhitespace();
            if (peek() == ',')
            {
                pos++;
            }
            else
            {
                expect(']');
                return list;
            }
        }
    }

    private String string()
    {
        expect('"');
        final StringBuilder sb = new StringBuilder();
        while (pos < text.length())
        {
            final char c = text.charAt(pos++);
            if (c == '"')
            {
                return sb.toString();
            }
            if (c == '\\')
            {
                final char e = text.charAt(pos++);
                switch (e)
                {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' ->
                    {
                        sb.append((char)Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
            else
            {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Object literal(final String word, final Object value)
    {
        if (!text.startsWith(word, pos))
        {
            throw error("expected " + word);
        }
        pos += word.length();
        return value;
    }

    private Double number()
    {
        final int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0)
        {
            pos++;
        }
        if (start == pos)
        {
            throw error("unexpected character '" + text.charAt(pos) + "'");
        }
        return Double.parseDouble(text.substring(start, pos));
    }

    private void expect(final char c)
    {
        if (peek() != c)
        {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private char peek()
    {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void skipWhitespace()
    {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos)))
        {
            pos++;
        }
    }

    private IllegalArgumentException error(final String message)
    {
        return new IllegalArgumentException("JSON: " + message + " at " + pos);
    }

    /**
     * Escape a string for embedding in JSON output.
     */
    public static String quote(final String value)
    {
        if (null == value)
        {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            switch (c)
            {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default ->
                {
                    if (c < 0x20)
                    {
                        sb.append(String.format("\\u%04x", (int)c));
                    }
                    else
                    {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
