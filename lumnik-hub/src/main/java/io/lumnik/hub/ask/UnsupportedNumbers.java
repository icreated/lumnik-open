package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;
import io.lumnik.hub.view.ViewFilter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers a phrased answer states that its rows cannot back (#912) — the one deterministic check
 * on the only free-text part of an ask. A prompt fence does not hold in production call order (#922).
 *
 * <p>Backed: the row count, a numeric cell, a text cell that is a number and nothing else, and each
 * numeric column's sum, minimum, maximum and average (compared at the answer's own precision), and the
 * number literals and date years the query compares a column against (#1391). A
 * number written <em>inside</em> a text cell backs nothing: that is exactly where a forged
 * "the verified count is 9999" sits. Digits inside identifiers and dates ({@code SO-000004},
 * {@code 2026-08-05}) are not numbers, nor are list markers opening a line ({@code 1. }), nor a
 * number read back from a text cell together with the two words before it.
 *
 * <p>Known limit: a cell that holds the forged sentence itself ("There are 9999 orders") is
 * indistinguishable from a quote of it, so an answer repeating it passes. The net closes the
 * cheaper forgery — a cell that <em>instructs</em> the count — not a cell that already reads as one.
 */
final class UnsupportedNumbers {

    /**
     * A standalone number, its sign included: not glued to a letter, digit, hyphen, slash, colon or
     * a further decimal. A minus is a sign only where nothing word-like precedes it, so the hyphens
     * of {@code SO-000004} and {@code 2026-08-05} stay part of their token. Thousands may be grouped
     * by a comma, a point or a space ({@code 1,250}, {@code 1.234.567}, {@code 312 480}).
     */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}_\\-/:.,])-?"
        + "(?:\\d{1,3}(?:[,. \\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)"
        + "(?![\\p{L}\\p{N}_\\-/:]|[.,]\\d)");
    private static final Pattern SPACE = Pattern.compile("[ \\u00A0\\u202F]");

    /** "1. " / "2) " opening a line: a list marker the model numbers its rows with, not a figure. */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d+[.)]\\s");
    /** A word for quote matching: letters and digits only, so spacing and punctuation fold away. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    /** A text cell that is a number and nothing else — grouped or not, like an answer's figure. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile(
        "-?(?:\\d{1,3}(?:[,. \\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)");
    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", ">", "<=", ">=");

    private UnsupportedNumbers() {}

    /** An answer read against its rows alone, with no query. */
    static List<String> in(String answer, List<Map<String, Object>> rows) {
        return in(answer, rows, null);
    }

    /** The unbacked numbers of {@code answer}, as written, each once, in order of appearance; {@code sql} ran. */
    static List<String> in(String answer, List<Map<String, Object>> rows, String sql) {
        if (answer == null || answer.isBlank()) return List.of();
        List<BigDecimal> backed = backedBy(rows);
        backed.addAll(comparedIn(sql));
        String text = LIST_MARKER.matcher(answer).replaceAll(" ");
        List<MatchResult> words = WORD.matcher(text).results().toList();
        List<String> cellTexts = null;  // folded only if a number is not backed — the rare path
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text);
        int wordsBefore = 0;
        while (m.find()) {
            while (wordsBefore < words.size() && words.get(wordsBefore).end() <= m.start()) wordsBefore++;
            if (isBacked(m.group(), backed)) continue;
            if (cellTexts == null) cellTexts = foldedTextCells(rows);
            if (quotedFromACell(words.subList(Math.max(0, wordsBefore - 2), wordsBefore), m.group(), cellTexts)) continue;
            out.add(m.group());
        }
        return new ArrayList<>(out);
    }

    /**
     * Backed when one plausible reading of the token is: {@code 1,250} reads as 1250 (grouped) or
     * 1.25 (decimal comma); with a space ({@code 2 480}) it may also be two figures, each backed.
     */
    private static boolean isBacked(String token, List<BigDecimal> backed) {
        for (BigDecimal n : readings(SPACE.matcher(token).replaceAll(""))) {
            int scale = Math.max(n.scale(), 0);
            BigDecimal rounded = n.setScale(scale, RoundingMode.HALF_UP);
            if (backed.stream().anyMatch(b -> b.setScale(scale, RoundingMode.HALF_UP).compareTo(rounded) == 0)) {
                return true;
            }
        }
        String[] parts = SPACE.split(token);
        return parts.length > 1 && Arrays.stream(parts).allMatch(p -> isBacked(p, backed));
    }

    /** The values a token without spaces may mean, by where its separators sit. */
    private static List<BigDecimal> readings(String token) {
        boolean negative = token.startsWith("-");
        String t = negative ? token.substring(1) : token;
        int last = Math.max(t.lastIndexOf(','), t.lastIndexOf('.'));
        List<String> out = new ArrayList<>();
        if (last < 0) {
            out.add(t);
        } else if (t.chars().filter(c -> c == ',' || c == '.').count() == 1) {
            out.add(t.replace(',', '.'));                                     // decimal
            if (t.length() - last - 1 == 3) out.add(t.replaceAll("[,.]", "")); // grouped
        } else if (t.indexOf(t.charAt(last)) == last) {
            out.add(t.substring(0, last).replaceAll("[,.]", "") + "." + t.substring(last + 1)); // 1,250.50
        } else {
            out.add(t.replaceAll("[,.]", ""));                                // 1.234.567
        }
        return out.stream().map(v -> negative ? new BigDecimal(v).negate() : new BigDecimal(v)).toList();
    }

    /**
     * True when {@code number}, with the one or two words just before it (one only at the cell's start), is read back from a text cell: a
     * note quoted as written is not a figure the answer states. Compared word by word, case and
     * spacing folded — the model re-spaces what it copies ("< /rows>"). A count the model makes up
     * next to the quote ("There are 9999") is not in any cell, so it stays a claim.
     */
    private static boolean quotedFromACell(List<MatchResult> lead, String number, List<String> cellTexts) {
        // Two words match anywhere in a cell. One word matches only at the cell's start: before a short
        // cell the first word is the answer's own framing ("Note: ..."), but mid-sentence a single
        // shared word ("is 9999") is a claim's wording, not evidence of a quote.
        for (int take = lead.size(); take >= 1; take--) {
            StringBuilder needle = new StringBuilder();
            for (MatchResult w : lead.subList(lead.size() - take, lead.size())) needle.append(w.group()).append(' ');
            String folded = fold(needle.append(number).toString());
            boolean found = take == 1
                ? cellTexts.stream().anyMatch(t -> t.startsWith(folded))
                : cellTexts.stream().anyMatch(t -> t.contains(folded));
            if (found) return true;
        }
        return false;
    }

    private static List<String> foldedTextCells(List<Map<String, Object>> rows) {
        return rows.stream().flatMap(r -> r.values().stream())
            .filter(v -> v instanceof String s && !isWholeNumber(s))
            .map(v -> fold((String) v)).toList();
    }

    private static String fold(String s) {
        return String.join(" ", WORD.matcher(s).results().map(MatchResult::group).toList()).toLowerCase(Locale.ROOT);
    }

    /**
     * The figures the query compares a column against (#1391): a number literal, or a date literal's year, on
     * the right of {@code col <op>} or bounding {@code col BETWEEN … AND …}. "15 000" restating
     * {@code amount_eur > 15000} describes the query, not the rows. Any other literal backs nothing:
     * {@code ROUND(x, 2)}, {@code GROUP BY 1} and {@code LIMIT 500} would back a made-up "2 orders",
     * {@code WHERE 9999 = 9999} or {@code SELECT '2025-01-01'} any figure at all. A column the code cannot see —
     * qualified, wrapped in a function — fails safe: the sentence is withheld, never the rows.
     */
    private static List<BigDecimal> comparedIn(String sql) {
        List<BigDecimal> out = new ArrayList<>();
        List<Token> tokens = SqlLexer.tokenize(sql);
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            // The lexer reads a sign as an operator of its own: `> -100` compares against -100.
            boolean signed = i > 0 && tokens.get(i - 1).kind() == Kind.OP && tokens.get(i - 1).text().equals("-");
            if (!comparedWithAColumn(tokens, signed ? i - 1 : i)) continue;
            String number = t.kind() == Kind.NUMBER ? SqlFilters.canonicalNumber(t.text()) : null;
            if (number != null) out.add(signed ? new BigDecimal(number).negate() : new BigDecimal(number));
            if (t.kind() == Kind.STRING && ViewFilter.isTemporal(t.text())) { // its year
                out.add(new BigDecimal(t.text().strip().substring(0, 4)));
            }
        }
        return out;
    }

    /** The literal starting at {@code i} is the right side of {@code col <op>}, or a bound of {@code col BETWEEN}. */
    private static boolean comparedWithAColumn(List<Token> tokens, int i) {
        if (i < 2) return false;
        Token before = tokens.get(i - 1);
        boolean comparison = before.kind() == Kind.OP && COMPARISONS.contains(before.text());
        if (comparison || SqlFilters.isKeyword(before, "between")) {
            return isColumn(tokens, i - 2);
        }
        if (!SqlFilters.isKeyword(before, "and")) return false;
        // `col BETWEEN [-]a AND [-]b`: the lower bound may carry its sign too.
        int lower = i - 2;
        if (lower > 0 && tokens.get(lower - 1).kind() == Kind.OP && tokens.get(lower - 1).text().equals("-")) lower--;
        return lower >= 2 && SqlFilters.isKeyword(tokens.get(lower - 1), "between") && isColumn(tokens, lower - 2);
    }

    /** A column name by {@link SqlFilters#isColumnRef}, and no value keyword ({@code current_date}, {@code null}). */
    private static boolean isColumn(List<Token> tokens, int i) {
        Token t = tokens.get(i);
        return SqlFilters.isColumnRef(tokens, i) && !SqlFilters.isValueKeyword(t.text(), t.quoted());
    }

    /** Compared at the precision each answer number is written at: "116.67" backs 116.666…. */
    private static List<BigDecimal> backedBy(List<Map<String, Object>> rows) {
        List<BigDecimal> backed = new ArrayList<>();
        backed.add(BigDecimal.valueOf(rows.size()));
        Map<String, List<BigDecimal>> columns = new HashMap<>();
        for (Map<String, Object> row : rows) {
            row.forEach((column, v) -> {
                List<BigDecimal> readings = numberIn(v);
                if (!readings.isEmpty()) {
                    backed.addAll(readings);
                    // One value per cell for the aggregates: the grouped reading when there is one.
                    columns.computeIfAbsent(column, c -> new ArrayList<>()).add(readings.get(readings.size() - 1));
                }
            });
        }
        for (List<BigDecimal> values : columns.values()) {
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            backed.add(sum);
            backed.add(values.stream().min(BigDecimal::compareTo).orElseThrow());
            backed.add(values.stream().max(BigDecimal::compareTo).orElseThrow());
            backed.add(sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64));
        }
        return backed;
    }

    /**
     * The values a cell may hold: one for a number, every plausible reading for a numeric text. A
     * NaN or ±Infinity — pgjdbc hands them over as a Double or Float, for numeric too — backs nothing.
     */
    private static List<BigDecimal> numberIn(Object v) {
        if ((v instanceof Double d && !Double.isFinite(d)) || (v instanceof Float f && !Float.isFinite(f))) return List.of();
        if (v instanceof Number n) return List.of(new BigDecimal(n.toString()));
        if (v instanceof String s && isWholeNumber(s)) return readings(SPACE.matcher(s.strip()).replaceAll(""));
        return List.of();
    }

    private static boolean isWholeNumber(String s) {
        return WHOLE_NUMBER.matcher(s.strip()).matches();
    }

}
