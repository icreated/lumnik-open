package io.lumnik.hub.ask;

import static io.lumnik.hub.ask.SqlFilters.isKeyword;
import static io.lumnik.hub.ask.SqlFilters.isPunct;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One relation of a {@code FROM} list written {@code schema.table [[AS] alias]} (#1978): the ask guards that need the
 * query's tables ({@link DisputedRows}, {@link PointGuard}) read it here, against one list of the words that are never
 * an alias. A name is read as PostgreSQL reads it ({@link #identifier}). A relation written any other way — a bare table, a sub-query, a function, a
 * column-alias list {@code s(a, b)} — is not read, and each guard decides what that means.
 */
record SqlRelation(String schema, String name, String alias, int next) {

    /** The words a bare alias never is: each opens what may follow a relation — a clause, a join, a set operation. */
    private static final Set<String> NOT_AN_ALIAS = Set.of("where", "join", "inner", "left", "right", "full", "cross",
        "natural", "on", "using", "group", "order", "limit", "offset", "having", "union", "intersect", "except",
        "window", "fetch", "lateral", "for", "tablesample", "as");

    /** The relation written at {@code at}, {@code next} the token after it; null when it is not written that way. */
    static SqlRelation at(List<Token> t, int at) {
        if (at + 2 >= t.size() || t.get(at).kind() != Kind.IDENT || !isPunct(t.get(at + 1), ".")
                || t.get(at + 2).kind() != Kind.IDENT) {
            return null;
        }
        int next = at + 3;
        String alias = null;
        if (next + 1 < t.size() && isKeyword(t.get(next), "as") && t.get(next + 1).kind() == Kind.IDENT) {
            alias = identifier(t.get(next + 1));
            next += 2;
        } else if (next < t.size() && t.get(next).kind() == Kind.IDENT
                && (t.get(next).quoted() || !NOT_AN_ALIAS.contains(identifier(t.get(next))))) {
            alias = identifier(t.get(next));
            next++;
        }
        if (alias != null && next < t.size() && isPunct(t.get(next), "(")) return null;   // s(a, b): columns renamed
        return new SqlRelation(identifier(t.get(at)), identifier(t.get(at + 2)), alias, next);
    }

    String fqn() {
        return schema + "." + name;
    }

    /** Its table on the card, or null when the card does not hold it. */
    SchemaCard.Table on(SchemaCard card) {
        String fqn = fqn();
        return card.tables().stream().filter(x -> x.name().equalsIgnoreCase(fqn)).findFirst().orElse(null);
    }

    /** What its columns are qualified by: the alias, or else the table's own name. */
    String qualifier() {
        return alias != null ? alias : name;
    }

    /**
     * A name as PostgreSQL reads it: a bare one folded to lower case, a quoted one exactly. {@code "S"} and {@code s} are
     * two relations — folded alike, a guard would check one while the database reads the other.
     */
    static String identifier(Token t) {
        return t.quoted() ? t.text() : t.text().toLowerCase(Locale.ROOT);
    }
}
