package io.aeronfire.control;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-plane user directory: maps authenticated names to the numeric user ids the cluster's
 * entitlements use. Demo users match {@code Entitlements.withDemoUsers()}.
 * <p>
 * Passwords come from {@code CONTROL_USERS} ({@code name:password,...}); the demo default makes each
 * password the user name. A real deployment would delegate to the firm's directory (LDAP/OIDC) instead.
 */
public final class Users
{
    private static final Map<String, Integer> IDS = new LinkedHashMap<>();

    static
    {
        IDS.put("root", 0);
        IDS.put("risk", 1);
        IDS.put("ops", 2);
        IDS.put("deskhead", 3);
        IDS.put("compliance", 4);
        IDS.put("sales", 5);
        IDS.put("refdata", 6);
    }

    private final Map<String, String> passwords = new LinkedHashMap<>();

    public Users(final String spec)
    {
        if (null == spec || spec.isBlank())
        {
            IDS.keySet().forEach((name) -> passwords.put(name, name));
        }
        else
        {
            for (final String entry : spec.split(","))
            {
                final String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2 && IDS.containsKey(parts[0]))
                {
                    passwords.put(parts[0], parts[1]);
                }
            }
        }
    }

    public static int idOf(final String name)
    {
        final Integer id = IDS.get(name);
        if (null == id)
        {
            throw new IllegalArgumentException("unknown user: " + name);
        }
        return id;
    }

    public static String nameOf(final int id)
    {
        for (final Map.Entry<String, Integer> e : IDS.entrySet())
        {
            if (e.getValue() == id)
            {
                return e.getKey();
            }
        }
        return "user" + id;
    }

    public static Iterable<String> names()
    {
        return IDS.keySet();
    }

    public boolean check(final String name, final String password)
    {
        final String expected = passwords.get(name);
        return null != expected && java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            null == password ? new byte[0] : password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
