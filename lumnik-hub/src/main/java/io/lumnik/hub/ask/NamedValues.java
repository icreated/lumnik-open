package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;

import java.util.List;
import java.util.regex.Pattern;

import static io.lumnik.hub.ask.SqlFilters.isKeyword;

/**
 * A value the question NAMES — « de la catégorie Pompes », "the status Validé", « le code PROMO10 » — is an equality,
 * whatever the model wrote (#1098). The containment rule of the SQL prompt says so, and the 7B followed it or not on
 * nothing more than the table names (four card names out of eight), never in English; every firmer wording measured
 * broke the negated containment instead. So the rule is applied here, on the generated SQL before any guard reads it:
 * {@code [NOT] [I]LIKE '%Y%'} becomes {@code = 'Y'} ({@code <> 'Y'}) when the question names Y right after one of
 * those nouns.
 *
 * <p>Only that. The contained text keeps its pattern (« dont la catégorie contient Pompes » names no category); a value
 * the question writes twice is left alone, since it may be both; a {@code %} or an escape inside Y, or an {@code ESCAPE}
 * clause, is not touched. An {@code _} needs no rule of its own: Y must stand in the question as written, so an
 * {@code _} that passes is a character of the name (« Pompes_HP »), and one the model wrote as a wildcard
 * ({@code '%Pomp_s%'}) never matches. Y is compared with accents and case folded, and written back as the model
 * wrote it.
 */
final class NamedValues {

    /** The nouns that name a value, in the words of the prompt's rule: a category, a status, a code. */
    private static final String NAMING_NOUN = "(?:categories?|category|statuts?|status|codes?)";

    private NamedValues() {}

    static String asEqualities(String sql, String question) {
        if (sql == null || question == null || question.isBlank()) return sql;
        String folded = QuestionWords.fold(AskToSqlAgent.straightQuotes(question));
        List<Token> tokens = SqlLexer.tokenize(sql);
        StringBuilder out = new StringBuilder(sql);
        // right to left, so the offsets of the predicates still to rewrite stay valid
        for (int i = tokens.size() - 2; i >= 0; i--) {
            Token op = tokens.get(i);
            Token literal = tokens.get(i + 1);
            if (!(isKeyword(op, "like") || isKeyword(op, "ilike")) || literal.kind() != Kind.STRING) continue;
            if (i + 2 < tokens.size() && isKeyword(tokens.get(i + 2), "escape")) continue;
            String value = named(literal.text(), folded);
            if (value == null) continue;
            boolean negated = i > 0 && isKeyword(tokens.get(i - 1), "not");
            int start = negated ? tokens.get(i - 1).start() : op.start();
            out.replace(start, literal.end(), (negated ? "<> '" : "= '") + value.replace("'", "''") + "'");
        }
        return out.toString();
    }

    /** The value a {@code %}-pattern searches for, when the question names it; otherwise null. */
    private static String named(String pattern, String folded) {
        boolean lead = pattern.startsWith("%");
        boolean trail = pattern.length() > 1 && pattern.endsWith("%");
        String value = pattern.substring(lead ? 1 : 0, pattern.length() - (trail ? 1 : 0));
        if (!(lead || trail) || value.isBlank() || value.contains("%") || value.contains("\\")) return null;
        String y = Pattern.quote(QuestionWords.fold(value));
        long times = Pattern.compile("(?<![\\p{L}\\p{N}])" + y + "(?![\\p{L}\\p{N}])").matcher(folded).results().count();
        boolean namedHere = Pattern.compile("\\b" + NAMING_NOUN + "\\s+\"?" + y + "\"?(?![\\p{L}\\p{N}])")
            .matcher(folded).find();
        return times == 1 && namedHere ? value : null;
    }
}
