package io.lumnik.hub.ask;

import io.lumnik.common.text.Text;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does the question write a date (#1330)? Only then does the SQL prompt carry the date rule. The prompt's "never guess
 * literal values to filter on" made the 7B abstain on every question that names a day — "les commandes expédiées le
 * 2026-07-02" — whatever the column's type: a date the asker wrote read as a value the model would invent. Like
 * {@link ContainmentIntent}, the rule is read only where it is needed, and every other question keeps its prompt.
 *
 * <p>The signal is closed: an ISO date ({@code 2026-07-02}, a time after it allowed), or a month named with its year
 * ("juillet 2026", "1er juillet 2026", "July 2026", "July 2, 2026", "4th of July 2026"), accents and case ignored, and
 * a date the calendar has: {@code 2026-02-31} would make the model write a literal PostgreSQL refuses. A number
 * alone, a year alone, a code built from a date ({@code SO-2026-07-02}), an abbreviated month ("juil. 2026") or
 * {@code 01/07/2026} (first of July or seventh of January?) is not it: the model then chooses as it did before.
 */
final class DateIntent {

    /**
     * What may stand around a written date: the start or a space, an opening bracket or quote, before it; the end, a
     * space, punctuation or a time ({@code T08:00}, folded to lower case) after it. Anything else makes it part of a
     * code — {@code SO-2026-07-02}, {@code SO/2026-07-02}, {@code SO_2026-07-02}, {@code 2026-07-02.3} — a document
     * number an ERP built from a date, which the question asks for by equality.
     */
    private static final String BEFORE = "(?<![^\\s(\\[«\"'])";
    private static final String AFTER = "(?=$|[\\s?!,;:)\\]»\"']|\\.(?!\\d)|t\\d)";

    private static final Pattern ISO_DATE = Pattern.compile(BEFORE + "(\\d{4})-(\\d{2})-(\\d{2})" + AFTER);

    /** French then English, in calendar order: a name's index is its month. */
    private static final List<String> MONTHS = List.of(
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre",
        "decembre", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december");

    /**
     * "juillet 2026", "1er juillet 2026", "4 juillet 2026", "July 2026", "July 2, 2026", "July 2nd 2026", "2nd July
     * 2026", "4th of July 2026".
     */
    private static final Pattern NAMED_MONTH = Pattern.compile("\\b(?:(\\d{1,2})(?:er|st|nd|rd|th)?\\s+(?:of\\s+)?)?("
        + String.join("|", MONTHS) + ")\\s+(?:(\\d{1,2})(?:st|nd|rd|th)?,?\\s+)?(\\d{4})\\b");

    private DateIntent() {}

    /** True when the question writes a date the calendar has: {@code 2026-02-31} is not one, and gets no rule. */
    static boolean in(String question) {
        if (Text.blank(question)) return false;
        String folded = QuestionWords.fold(question);
        Matcher iso = ISO_DATE.matcher(folded);
        while (iso.find()) {
            if (exists(iso.group(1), iso.group(2), iso.group(3))) return true;
        }
        Matcher named = NAMED_MONTH.matcher(folded);
        while (named.find()) {
            String day = named.group(1) != null ? named.group(1) : named.group(3);
            String month = String.valueOf(monthOf(named));
            if (day == null ? exists(named.group(4), month, "1") : exists(named.group(4), month, day)) return true;
        }
        return false;
    }

    /**
     * The months the question names whole, with their year and no day (#1976): "juillet 2026", never "4 juillet
     * 2026". Their bounds are computed here, not by the model: given only "the first day of the next month", the 7B
     * ran "en juillet 2026 après le 4" to 5 August, and could not turn December into January of the next year.
     */
    static List<YearMonth> wholeMonths(String question) {
        if (Text.blank(question)) return List.of();
        return NAMED_MONTH.matcher(QuestionWords.fold(question)).results()
            .filter(named -> named.group(1) == null && named.group(3) == null)
            .map(named -> YearMonth.of(Integer.parseInt(named.group(4)), monthOf(named)))
            // both bounds a PostgreSQL date literal: not year 0, not December 9999 (an ERP's open end) -> '+10000-01-01'
            .filter(month -> month.getYear() >= 1 && month.plusMonths(1).getYear() <= 9999)
            .distinct().toList();
    }

    private static int monthOf(MatchResult named) {
        return MONTHS.indexOf(named.group(2)) % 12 + 1;
    }

    private static boolean exists(String year, String month, String day) {
        try {
            LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
