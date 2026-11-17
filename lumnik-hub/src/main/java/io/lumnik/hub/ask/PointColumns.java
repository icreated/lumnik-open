package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;
import io.lumnik.hub.ask.PointGuard.Refusal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.lumnik.hub.ask.PointGuard.isSign;
import static io.lumnik.hub.ask.SqlFilters.isKeyword;
import static io.lumnik.hub.ask.SqlFilters.isPunct;

/**
 * #1977: coordinates the model knows and the question never typed, without {@code distance_m} — a box around a place,
 * {@code latitude BETWEEN 45.70 AND 45.82}, or the great-circle formula written by hand. A declared point's column
 * compared to, or computed with, a number — {@code < 45.7}, {@code - 45.76}, {@code BETWEEN … AND …},
 * {@code IN (…)} — must have a number the question typed, its sign with it: "north of latitude 45" runs
 * {@code latitude > 45}, "customers near Lyon" abstains on {@code latitude BETWEEN 45.70 AND 45.82}. 0, ±90 and ±180
 * are the bounds of the coordinates, no place, and pass. A point's column inside a trigonometric function abstains: a
 * distance is measured with {@code distance_m}.
 *
 * <p><b>Fail-open</b>, like {@link DomainGuard}, unlike {@link PointGuard}: it reads the forms a model writes
 * unprompted and lets through what it cannot read — the column inside another function or a {@code CAST(…)}, renamed
 * before it is compared, a constant in a string ({@code box '…'}) or in a sub-query, a number written {@code .5};
 * {@code PointGuardTest.notRead} pins them, {@code DistanceAskClosedIT} watches the model. A column is a point's by
 * its name, case ignored: any column named as a point's {@code lat} or {@code lon} on the card.
 */
final class PointColumns {

    private PointColumns() {}

    private static final Set<String> OPERATORS = Set.of("=", "<>", "!=", "<", ">", "<=", ">=", "+", "-", "*", "/", "%");
    private static final Set<String> TRIGONOMETRY = Set.of("sin", "cos", "tan", "cot", "asin", "acos", "atan", "atan2",
        "sind", "cosd", "tand", "cotd", "asind", "acosd", "atand", "atan2d");
    /** The words a sign follows as a number's own, not as a subtraction: {@code WHERE -45 < latitude}. */
    private static final Set<String> SIGN_AFTER = Set.of("where", "and", "or", "not", "on", "having", "when", "then",
        "else", "select", "by", "between", "symmetric", "in", "is", "from", "case", "distinct");

    /** A number the question wrote, an ordinal's suffix allowed: "the 46th parallel" types 46. */
    private static final Pattern TYPED_NUMBER =
        Pattern.compile("(?<![\\w.])(" + PointGuard.SIGNED_NUMBER + ")(?:st|nd|rd|th|er|e|ème)?" + PointGuard.NUMBER_END);

    /** The lat and lon columns of every point the card declares, lower-cased. */
    static Set<String> of(SchemaCard card) {
        Set<String> names = new HashSet<>();
        for (SchemaCard.Table table : card.tables()) {
            for (SchemaCard.Point p : table.points()) {
                if (p.lat() != null) names.add(lower(p.lat()));
                if (p.lon() != null) names.add(lower(p.lon()));
            }
        }
        return names;
    }

    static Optional<Refusal> check(List<Token> t, Set<String> columns, String question) {
        List<BigDecimal> typed = question == null ? List.of()
            : TYPED_NUMBER.matcher(question).results().map(m -> new BigDecimal(m.group(1))).toList();
        for (int i = 0; i < t.size(); i++) {
            if (!isColumn(t, i, columns)) continue;
            if (inTrigonometry(t, i)) return Optional.of(Refusal.HAND_DISTANCE);
            for (BigDecimal number : numbersBeside(t, i)) {
                if (frame(number) || typed.stream().anyMatch(n -> n.compareTo(number) == 0)) continue;
                return Optional.of(Refusal.NUMBER_NOT_TYPED);
            }
        }
        return Optional.empty();
    }

    /** 0, ±90, ±180: the bounds of the coordinates, no place — "no coordinates" is often 0 in a legacy system. */
    private static boolean frame(BigDecimal number) {
        BigDecimal abs = number.abs();
        return abs.signum() == 0 || abs.compareTo(BigDecimal.valueOf(90)) == 0 || abs.compareTo(BigDecimal.valueOf(180)) == 0;
    }

    /** A name a point declares, used as a column: not a function, a qualifier, nor an alias being given. */
    private static boolean isColumn(List<Token> t, int i, Set<String> columns) {
        if (t.get(i).kind() != Kind.IDENT || !columns.contains(lower(t.get(i).text()))) return false;
        if (i + 1 < t.size() && (isPunct(t.get(i + 1), "(") || isPunct(t.get(i + 1), "."))) return false;
        return i == 0 || !isKeyword(t.get(i - 1), "as");
    }

    /** Whether a call to a trigonometric function encloses the token at {@code i}, however deep. */
    private static boolean inTrigonometry(List<Token> t, int i) {
        int depth = 0;
        for (int k = i - 1; k > 0; k--) {
            if (isPunct(t.get(k), ")")) depth++;
            if (!isPunct(t.get(k), "(")) continue;
            if (depth > 0) {
                depth--;
            } else if (t.get(k - 1).kind() == Kind.IDENT && TRIGONOMETRY.contains(lower(t.get(k - 1).text()))) {
                return true;
            }
        }
        return false;
    }

    /** The numbers written right beside the column at {@code i}: across an operator either side, a BETWEEN, an IN. */
    private static List<BigDecimal> numbersBeside(List<Token> t, int i) {
        List<BigDecimal> out = new ArrayList<>();
        int start = i >= 2 && isPunct(t.get(i - 1), ".") ? i - 2 : i;
        if (start >= 2 && isOperator(t.get(start - 1))) add(out, before(t, start - 2));
        int next = i + 1;
        while (next + 1 < t.size() && t.get(next).kind() == Kind.OP && "::".equals(t.get(next).text())) {
            next += 2;   // a cast, its precision with it: ::numeric(9, 6)
            if (next < t.size() && isPunct(t.get(next), "(")) next = SqlFilters.matchingParen(t, next) + 1;
            if (next <= 0) return out;
        }
        if (next < t.size() && isKeyword(t.get(next), "not")) next++;
        if (next + 1 >= t.size()) return out;
        if (isOperator(t.get(next))) {
            add(out, after(t, next + 1));
        } else if (isKeyword(t.get(next), "between")) {
            int low = isKeyword(t.get(next + 1), "symmetric") ? next + 2 : next + 1;
            add(out, after(t, low));
            int and = low;
            while (and < t.size() && !isKeyword(t.get(and), "and")) {
                and = isPunct(t.get(and), "(") ? SqlFilters.matchingParen(t, and) + 1 : and + 1;
                if (and <= 0) return out;   // a bracket never closed
            }
            add(out, after(t, and + 1));
        } else if (isKeyword(t.get(next), "in") && isPunct(t.get(next + 1), "(")) {
            int close = SqlFilters.matchingParen(t, next + 1);
            int depth = 0;
            for (int k = next + 2; k < close; k++) {
                if (depth == 0 && (k == next + 2 || isPunct(t.get(k - 1), ","))) add(out, after(t, k));
                if (isPunct(t.get(k), "(")) depth++;
                if (isPunct(t.get(k), ")")) depth--;
            }
        }
        return out;
    }

    /** The number starting at {@code k} — signed, in a string, in brackets — or null when something else starts there. */
    private static BigDecimal after(List<Token> t, int k) {
        if (k >= t.size()) return null;
        Token first = t.get(k);
        if (isPunct(first, "(")) {
            int end = k + 1 < t.size() && isSign(t.get(k + 1)) ? k + 3 : k + 2;
            return end < t.size() && isPunct(t.get(end), ")") ? after(t, k + 1) : null;
        }
        if (first.kind() == Kind.STRING) return string(first.text());
        if (isSign(first)) return k + 1 < t.size() ? PointGuard.literal(t.subList(k, k + 2)) : null;
        return first.kind() == Kind.NUMBER ? PointGuard.literal(t.subList(k, k + 1)) : null;
    }

    /** The number ending at {@code k}, its sign with it when the sign is no subtraction. */
    private static BigDecimal before(List<Token> t, int k) {
        Token last = t.get(k);
        if (last.kind() == Kind.STRING) return string(last.text());
        if (last.kind() != Kind.NUMBER) return null;
        boolean signed = k >= 1 && isSign(t.get(k - 1)) && (k == 1 || signFollows(t.get(k - 2)));
        return PointGuard.literal(t.subList(signed ? k - 1 : k, k + 1));
    }

    /** Whether a sign after this token belongs to the number: after an operator, a bracket, a comma, a keyword. */
    private static boolean signFollows(Token before) {
        if (before.kind() == Kind.OP || isPunct(before, "(") || isPunct(before, ",")) return true;
        return before.kind() == Kind.IDENT && !before.quoted() && SIGN_AFTER.contains(lower(before.text()));
    }

    /** A string holding a number, {@code '45.7'}: shape-checked as the other guards read one. */
    private static BigDecimal string(String text) {
        String number = SqlFilters.canonicalNumber(text);
        return number == null ? null : new BigDecimal(number);
    }

    private static void add(List<BigDecimal> out, BigDecimal number) {
        if (number != null) out.add(number);
    }

    private static boolean isOperator(Token t) {
        return t.kind() == Kind.OP && OPERATORS.contains(t.text());
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
