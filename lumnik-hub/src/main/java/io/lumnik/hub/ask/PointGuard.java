package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.lumnik.hub.ask.SqlFilters.isKeyword;
import static io.lumnik.hub.ask.SqlFilters.isPunct;

/**
 * #1974: every call to {@code connector.distance_m} in a generated query measures between two halves, and each half is
 * one of three shapes: a point the card declares — its {@code lat} then its {@code lon} column, on one table reference
 * of the query; a centre the question typed as a pair, latitude first, with its sign; or a centre read from one row, as
 * two sub-queries selecting a declared point's {@code lat} then {@code lon}. A place name has no coordinates in the hub,
 * so the model would invent them: refused, like everything else.
 *
 * <p><b>Fail-closed</b>, unlike its neighbours: it reads {@link SqlLexer} tokens, not a syntax tree, so an argument, an
 * alias or a {@code FROM} it cannot read abstains. A stricter guard that sometimes abstains on a valid query is the
 * accepted cost. The radius and the comparison around the call are not inspected.
 */
final class PointGuard {

    private PointGuard() {}

    private static final String FUNCTION = "distance_m";
    private static final String SCHEMA = "connector";

    /** Why a call was refused. */
    enum Refusal {
        /** It measures from something no point declares. */
        NOT_A_POINT,
        /** It measures from a centre the question did not type. */
        CENTRE_NOT_TYPED,
        /** It compares a point's column with a number the question did not type (#1977). */
        NUMBER_NOT_TYPED,
        /** It writes a distance by hand: a point's column inside a trigonometric function (#1977). */
        HAND_DISTANCE;

        String message(String lang) {
            return switch (this) {
                case NOT_A_POINT -> AskSentences.notADeclaredPoint(lang);
                case CENTRE_NOT_TYPED -> AskSentences.centreNotTyped(lang);
                case NUMBER_NOT_TYPED -> AskSentences.coordinateNotTyped(lang);
                case HAND_DISTANCE -> AskSentences.distanceByHand(lang);
            };
        }
    }

    static Optional<Refusal> check(String sql, SchemaCard card, String question) {
        if (sql == null || card == null) return Optional.empty();
        Set<String> pointColumns = PointColumns.of(card);
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.contains(FUNCTION) && pointColumns.stream().noneMatch(lower::contains)) return Optional.empty();
        List<Token> t = SqlLexer.tokenize(sql);
        int[] depth = selectDepths(t);
        Relations relations = null;
        List<Pair> typed = null;
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!opensCall(t, i + 1)) continue;
            if (i >= 2 && isPunct(t.get(i - 1), ".") && !isName(t.get(i - 2), SCHEMA)) return Optional.of(Refusal.NOT_A_POINT);
            // inside a sub-select: whose tables, the guard cannot say
            if (depth[i] > 0) return Optional.of(Refusal.NOT_A_POINT);
            List<List<Token>> args = arguments(t, i + 1);
            if (args.size() != 4) return Optional.of(Refusal.NOT_A_POINT);
            if (relations == null) {
                relations = Relations.of(t, card, depth);
                typed = typedPairs(question);
            }
            for (int h = 0; h < 4; h += 2) {
                Optional<Refusal> refusal = half(args.get(h), args.get(h + 1), relations, card, typed);
                if (refusal.isPresent()) return refusal;
            }
        }
        return PointColumns.check(t, pointColumns, question);
    }

    /**
     * Whether the {@code (} at {@code open} opens a call to the distance function — its name quoted or not: a quoted
     * {@code "distance_m"(} is the same call, and must not slip past the guard. Shared with {@link UnsupportedNumbers}, so
     * both read one definition of the call.
     */
    static boolean opensCall(List<Token> t, int open) {
        return open > 0 && open < t.size() && isPunct(t.get(open), "(") && isName(t.get(open - 1), FUNCTION);
    }

    /** For each token, how many sub-selects ({@code (SELECT …)}, {@code (WITH …)}) enclose it. */
    private static int[] selectDepths(List<Token> t) {
        int[] depth = new int[t.size()];
        Deque<Boolean> open = new ArrayDeque<>();
        int current = 0;
        for (int k = 0; k < t.size(); k++) {
            if (isPunct(t.get(k), ")") && !open.isEmpty() && open.pop()) current--;
            depth[k] = current;
            if (isPunct(t.get(k), "(")) {
                boolean select = k + 1 < t.size() && (isKeyword(t.get(k + 1), "select") || isKeyword(t.get(k + 1), "with"));
                open.push(select);
                if (select) current++;
            }
        }
        return depth;
    }

    // --- one half -------------------------------------------------------------------------------------------------------

    private static Optional<Refusal> half(List<Token> lat, List<Token> lon, Relations relations, SchemaCard card,
                                          List<Pair> typed) {
        if (rowCentre(lat, lon, card)) return Optional.empty();
        Column latColumn = Column.of(lat);
        Column lonColumn = Column.of(lon);
        if (latColumn != null && lonColumn != null) {
            return relations.declaresPoint(latColumn, lonColumn) ? Optional.empty() : Optional.of(Refusal.NOT_A_POINT);
        }
        BigDecimal latValue = literal(lat);
        BigDecimal lonValue = literal(lon);
        // a column with a literal, an expression
        if (latValue == null || lonValue == null) return Optional.of(Refusal.NOT_A_POINT);
        if (latValue.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lonValue.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            return Optional.of(Refusal.CENTRE_NOT_TYPED);
        }
        return typed.contains(new Pair(latValue, lonValue))
                ? Optional.empty() : Optional.of(Refusal.CENTRE_NOT_TYPED);
    }

    /**
     * A centre read from a row: {@code (SELECT lat FROM schema.table …)} then {@code (SELECT lon FROM schema.table …)}, the
     * two sub-queries identical but for the column they select, on a table whose declared point those two columns are.
     * The coordinates come from the data, not from the model; a sub-query that finds several rows is the database's to
     * refuse.
     */
    private static boolean rowCentre(List<Token> lat, List<Token> lon, SchemaCard card) {
        if (lat.size() != lon.size() || lat.size() < 8) return false;
        if (!isPunct(lat.getFirst(), "(") || !isPunct(lat.getLast(), ")") || !isKeyword(lat.get(1), "select")) return false;
        if (lat.get(2).kind() != Kind.IDENT || lon.get(2).kind() != Kind.IDENT || !isKeyword(lat.get(3), "from")) return false;
        for (int k = 0; k < lat.size(); k++) {
            if (k != 2 && !sameToken(lat.get(k), lon.get(k))) return false;
        }
        // the table alone, then its WHERE: another relation or a set operation may supply a row the data does not have
        SqlRelation relation = SqlRelation.at(lat, 4);
        if (relation == null || !(relation.next() == lat.size() - 1 || isKeyword(lat.get(relation.next()), "where"))) {
            return false;
        }
        if (lat.stream().anyMatch(x -> ADDS_A_ROW.stream().anyMatch(w -> isKeyword(x, w)))) return false;
        // one row, or the database's refusal: a sub-query that picks one of several rows may pick another for the longitude
        if (lat.stream().anyMatch(x -> PICKS_A_ROW.stream().anyMatch(w -> isKeyword(x, w)))) return false;
        SchemaCard.Table table = relation.on(card);
        return table != null && Relations.hasPoint(table, lower(lat.get(2)), lower(lon.get(2)));
    }

    /**
     * One token, as the database reads it: a string or a quoted name exactly — {@code 'Lyon'} and {@code 'lyon'} are two
     * rows — and a bare name or a keyword whatever its case.
     */
    private static boolean sameToken(Token a, Token b) {
        if (a.kind() != b.kind() || a.quoted() != b.quoted()) return false;
        boolean exact = a.kind() == Kind.STRING || a.quoted();
        return exact ? a.text().equals(b.text()) : a.text().equalsIgnoreCase(b.text());
    }

    private static final List<String> PICKS_A_ROW = List.of("limit", "order", "offset", "fetch");
    private static final List<String> ADDS_A_ROW = List.of("union", "intersect", "except");

    /** A column reference: {@code col} or {@code qualifier.col}, nothing around it. */
    private record Column(String qualifier, String name) {
        static Column of(List<Token> arg) {
            if (arg.size() == 1 && arg.getFirst().kind() == Kind.IDENT) return new Column(null, lower(arg.getFirst()));
            if (arg.size() == 3 && arg.get(0).kind() == Kind.IDENT && isPunct(arg.get(1), ".")
                && arg.get(2).kind() == Kind.IDENT) {
                return new Column(SqlRelation.identifier(arg.get(0)), lower(arg.get(2)));
            }
            return null;
        }
    }

    static boolean isSign(Token t) {
        return t.kind() == Kind.OP && ("-".equals(t.text()) || "+".equals(t.text()));
    }

    /** A number with at most one sign before it — {@code - -33} or {@code 0 - 33} is an expression, not a coordinate. */
    static BigDecimal literal(List<Token> arg) {
        String sign = "";
        List<Token> rest = arg;
        if (arg.size() == 2 && isSign(arg.getFirst())) {
            sign = arg.getFirst().text();
            rest = arg.subList(1, 2);
        }
        if (rest.size() != 1 || rest.getFirst().kind() != Kind.NUMBER) return null;
        try {
            return new BigDecimal(sign + rest.getFirst().text());
        } catch (NumberFormatException notANumber) {   // "1.2.3": the lexer reads digits and dots
            return null;
        }
    }

    // --- the question's typed pairs -------------------------------------------------------------------------------------

    /** A typed pair, its numbers without trailing zeros: 45.7640 and 45.764 are one number. */
    private record Pair(BigDecimal lat, BigDecimal lon) {
        Pair {
            lat = lat.stripTrailingZeros();
            lon = lon.stripTrailingZeros();
        }
    }

    /** A signed number written in the question: an ASCII sign directly before it, a dot for the decimals. */
    static final String SIGNED_NUMBER = "[-+]?\\d+(?:\\.\\d+)?";
    /** What may follow a number: not a letter or a digit, and a dot only as a full stop — "45.75." types 45.75. */
    static final String NUMBER_END = "(?![\\w]|\\.\\d)";
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])" + SIGNED_NUMBER + NUMBER_END);
    /** What may sit between the two numbers of a pair: spaces, and at most one comma or semicolon. */
    private static final Pattern PAIR_GAP = Pattern.compile("\\s*[,;]?\\s*");

    /**
     * Two numbers the question wrote as a pair, latitude first: next to each other, apart only by spaces and one comma
     * or semicolon. Two numbers typed apart ("45 km of 4.8357") are not a pair, so a radius never passes for a latitude.
     */
    private static List<Pair> typedPairs(String question) {
        List<Pair> pairs = new ArrayList<>();
        if (question == null) return pairs;
        Matcher m = NUMBER.matcher(question);
        int previousEnd = -1;
        BigDecimal previous = null;
        while (m.find()) {
            BigDecimal value = new BigDecimal(m.group());
            if (previous != null && PAIR_GAP.matcher(question.substring(previousEnd, m.start())).matches()) {
                pairs.add(new Pair(previous, value));
            }
            previous = value;
            previousEnd = m.end();
        }
        return pairs;
    }

    // --- the call's arguments -------------------------------------------------------------------------------------------

    /** The arguments of the call opening at {@code open}, split on top-level commas; empty when it never closes. */
    private static List<List<Token>> arguments(List<Token> t, int open) {
        int close = SqlFilters.matchingParen(t, open);
        if (close < 0) return List.of();
        List<List<Token>> args = new ArrayList<>();
        int depth = 0;
        int from = open + 1;
        for (int i = open + 1; i < close; i++) {
            if (isPunct(t.get(i), "(")) depth++;
            if (isPunct(t.get(i), ")")) depth--;
            if (depth == 0 && isPunct(t.get(i), ",")) {
                args.add(t.subList(from, i));
                from = i + 1;
            }
        }
        args.add(t.subList(from, close));
        return args;
    }

    // --- the query's table references -----------------------------------------------------------------------------------

    /**
     * The card tables the outer query reads, each under the names it can be qualified by: its alias, or its own name. Only
     * the outer {@code FROM}/{@code JOIN} count — a sub-select's relations never supply an outer column. A {@code WITH}, a
     * derived table or a relation the card does not hold makes the list incomplete: a bare column could then come from
     * somewhere the guard cannot see.
     */
    private record Relations(List<SchemaCard.Table> references, Map<String, SchemaCard.Table> byQualifier,
                             Set<String> ambiguousQualifiers, boolean complete) {

        static Relations of(List<Token> t, SchemaCard card, int[] depth) {
            List<SchemaCard.Table> references = new ArrayList<>();
            Map<String, SchemaCard.Table> byQualifier = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            boolean complete = true;
            for (int i = 0; i < t.size(); i++) {
                if (isKeyword(t.get(i), "with")) complete = false;
                if (depth[i] > 0 || !(isKeyword(t.get(i), "from") || isKeyword(t.get(i), "join"))) continue;
                int at = i + 1;
                while (true) {
                    SqlRelation relation = SqlRelation.at(t, at);
                    SchemaCard.Table table = relation == null ? null : relation.on(card);
                    if (table == null) complete = false;   // a sub-query, a function, a table off the card
                    if (relation == null) break;
                    if (table != null) {
                        references.add(table);
                        SchemaCard.Table held = byQualifier.putIfAbsent(relation.qualifier(), table);
                        if (held != null && held != table) ambiguous.add(relation.qualifier());
                    }
                    if (relation.next() >= t.size() || !isPunct(t.get(relation.next()), ",")) break;
                    at = relation.next() + 1;   // a comma-join: the next relation of this FROM
                }
            }
            return new Relations(references, byQualifier, ambiguous, complete);
        }

        /** Whether exactly one table reference the two columns can name declares them as a point, lat then lon. */
        boolean declaresPoint(Column lat, Column lon) {
            if (lat.qualifier() != null && lon.qualifier() != null && !lat.qualifier().equals(lon.qualifier())) return false;
            boolean bare = lat.qualifier() == null || lon.qualifier() == null;
            String qualifier = lat.qualifier() != null ? lat.qualifier() : lon.qualifier();
            SchemaCard.Table named = qualifier == null ? null : byQualifier.get(qualifier);
            if (qualifier != null && (named == null || ambiguousQualifiers.contains(qualifier))) return false;
            if (!bare) return hasPoint(named, lat.name(), lon.name());
            // a bare column: exactly one table reference of a query the guard reads whole may supply it — and, next to a
            // qualified half, that reference is the qualified one
            if (!complete) return false;
            List<SchemaCard.Table> candidates = references.stream().filter(x -> hasPoint(x, lat.name(), lon.name())).toList();
            return candidates.size() == 1 && (named == null || candidates.getFirst() == named);
        }

        static boolean hasPoint(SchemaCard.Table table, String lat, String lon) {
            return table.points().stream().anyMatch(p -> p.lat().equalsIgnoreCase(lat) && p.lon().equalsIgnoreCase(lon));
        }
    }

    // --- tokens ---------------------------------------------------------------------------------------------------------

    /** A name, quoted or not: the function and its schema are recognised either way, so a quoted call is still checked. */
    private static boolean isName(Token t, String name) {
        return t.kind() == Kind.IDENT && t.text().equalsIgnoreCase(name);
    }

    private static String lower(Token t) {
        return t.text().toLowerCase(Locale.ROOT);
    }
}
