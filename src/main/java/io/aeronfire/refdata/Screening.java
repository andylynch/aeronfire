package io.aeronfire.refdata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Name screening against sanctions designations.
 * <p>
 * Names are normalised (case, punctuation, legal-form suffixes) and compared as token sets. A designated name
 * whose tokens are all contained in the screened name, or a high Jaccard similarity, is a potential match.
 * Real screening adds transliteration, fuzzy token matching, dates of birth and identifiers; the point here
 * is the workflow: a potential match blocks the client until compliance confirms or discounts it.
 */
public final class Screening
{
    public static final double THRESHOLD = 0.75;

    private static final Pattern NON_WORD = Pattern.compile("[^A-Z0-9 ]");
    private static final Set<String> LEGAL_FORMS = new HashSet<>(Arrays.asList(
        "LTD", "LIMITED", "PLC", "LLC", "LLP", "LP", "SA", "AG", "NV", "BV", "GMBH", "FZE", "FZCO", "JSC", "PJSC",
        "OJSC", "INC", "CORP", "CORPORATION", "CO", "COMPANY", "THE", "AND", "OF", "ICAV"));

    private Screening()
    {
    }

    public static Set<String> tokens(final String name)
    {
        final String cleaned = NON_WORD.matcher(name.toUpperCase(Locale.ROOT).replace('.', ' ')).replaceAll(" ");
        final Set<String> tokens = new HashSet<>();
        for (final String token : cleaned.split("\\s+"))
        {
            if (!token.isBlank() && !LEGAL_FORMS.contains(token))
            {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * @return similarity in [0, 1]; 1 if every token of the designated name appears in the screened name.
     */
    public static double score(final String screenedName, final String designatedName)
    {
        final Set<String> a = tokens(screenedName);
        final Set<String> b = tokens(designatedName);
        if (a.isEmpty() || b.isEmpty())
        {
            return 0.0;
        }
        if (a.containsAll(b))
        {
            return 1.0;
        }
        final Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        final Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double)intersection.size() / union.size();
    }

    public static List<Model.ScreeningHit> screen(
        final int clientId, final String name, final List<Model.Designation> designations)
    {
        final List<Model.ScreeningHit> hits = new ArrayList<>();
        if (null == name || name.isBlank())
        {
            return hits;
        }
        for (final Model.Designation d : designations)
        {
            final double score = score(name, d.name());
            if (score >= THRESHOLD)
            {
                hits.add(new Model.ScreeningHit(clientId, name, d.uniqueId(), d.name(), score, "POTENTIAL_MATCH"));
            }
        }
        return hits;
    }
}
