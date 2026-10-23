package io.aeronfire.refdata;

/**
 * Check-digit validation for the identifiers regulators validate reports against.
 */
public final class Identifiers
{
    private Identifiers()
    {
    }

    /**
     * ISO 17442 LEI: 20 alphanumerics, ISO 7064 MOD 97-10 checksum.
     */
    public static boolean isValidLei(final String lei)
    {
        if (null == lei || lei.length() != 20 || !lei.chars().allMatch((c) -> Character.isDigit(c) || (c >= 'A' && c <= 'Z')))
        {
            return false;
        }
        int remainder = 0;
        for (int i = 0; i < lei.length(); i++)
        {
            final int value = Character.digit(lei.charAt(i), 36);
            remainder = value > 9 ? (remainder * 100 + value) % 97 : (remainder * 10 + value) % 97;
        }
        return remainder == 1;
    }

    /**
     * ISO 6166 ISIN: 2-letter country, 9 alphanumerics, Luhn check digit over the letter-expanded body.
     */
    public static boolean isValidIsin(final String isin)
    {
        if (null == isin || isin.length() != 12 || !Character.isLetter(isin.charAt(0)) || !Character.isLetter(isin.charAt(1)))
        {
            return false;
        }
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 11; i++)
        {
            final int value = Character.digit(isin.charAt(i), 36);
            if (value < 0)
            {
                return false;
            }
            digits.append(value);
        }
        int sum = 0;
        for (int i = digits.length() - 1, n = 0; i >= 0; i--, n++)
        {
            int d = digits.charAt(i) - '0';
            if (n % 2 == 0)
            {
                d *= 2;
                if (d > 9)
                {
                    d -= 9;
                }
            }
            sum += d;
        }
        return Character.isDigit(isin.charAt(11)) && (10 - sum % 10) % 10 == isin.charAt(11) - '0';
    }
}
