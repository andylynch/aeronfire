package io.aeronfire.control;

import io.aeronfire.domain.Entitlements;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * Names for the entitlement permission bits, for JMX and the admin page.
 */
public final class Permissions
{
    private static final String[] NAMES = {
        "CREDIT", "INVENTORY", "POSITION_ADJUST", "ALGO", "KILL_SWITCH", "ENTITLEMENTS", "APPROVE", "HOSPITAL",
        "REPLAY", "VIEW", "REFDATA" };

    private Permissions()
    {
    }

    public static String describe(final int bits)
    {
        if (bits == Entitlements.ALL)
        {
            return "ALL";
        }
        final StringJoiner joiner = new StringJoiner(",");
        for (int i = 0; i < NAMES.length; i++)
        {
            if ((bits & (1 << i)) != 0)
            {
                joiner.add(NAMES[i]);
            }
        }
        return joiner.toString();
    }

    /**
     * Parse "CREDIT,APPROVE" or "ALL".
     */
    public static int parse(final String csv)
    {
        if (null == csv || csv.isBlank())
        {
            return 0;
        }
        if (csv.trim().equalsIgnoreCase("ALL"))
        {
            return Entitlements.ALL;
        }
        int bits = 0;
        for (final String token : csv.split(","))
        {
            final String name = token.trim().toUpperCase(Locale.ROOT);
            boolean found = false;
            for (int i = 0; i < NAMES.length; i++)
            {
                if (NAMES[i].equals(name))
                {
                    bits |= 1 << i;
                    found = true;
                }
            }
            if (!found)
            {
                throw new IllegalArgumentException("unknown permission: " + token);
            }
        }
        return bits;
    }
}
