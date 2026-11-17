package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.lumnik.hub.ask.SqlFilters.isKeyword;
import static io.lumnik.hub.ask.SqlFilters.isOp;
import static io.lumnik.hub.ask.SqlFilters.isPunct;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * #2062: a hub table ({@code connector.<name>} or {@code ext.<name>}, not an Entity's view) is one physical table for
 * every tenant ingesting under its name, and it holds every tenant's columns (#2023). The query Ask runs therefore never
 * reads it whole: each place it names one becomes a projection of the tenant's card — {@code (SELECT <the card's
 * columns> FROM <schema>.<name>) AS <its alias, or its name>} — so {@code *}, {@code t.*}, {@code row_to_json(t)} and
 * anything else see the card's columns only, and a name off the card is the database's own "column does not exist".
 *
 * <p>The projection is the card's columns the table holds: built from the card, never from the catalog, which only
 * leaves out a card column the table lost (a card behind its table would otherwise fail every query on it).
 *
 * <p>Fail-closed: a hub table named any way this cannot rewrite unambiguously refuses the query — as a type ({@code ::},
 * {@code CAST … AS}, a functional cast: its row type lists every physical column), a column through the table's full
 * name, {@code ONLY}, {@code TABLESAMPLE}, the inheritance {@code *}, a catalog-qualified name, a renamed column list
 * ({@code t(a, b)}), a table off the card, two unaliased tables that would take one alias, or a name in no {@code FROM}
 * position. Whether a name outside a {@code FROM} is a table at all, the catalog says: {@code ext.code} after an alias
 * {@code ext} is a column. An Entity's view is read as it is: its columns are its owner's (#2050).
 */
final class HubRelationProjection {

  /** The schemas of the hub's shared tables: dynamic ({@code connector.t_*}) and hybrid ({@code connector}, {@code ext}). */
  private static final Set<String> HUB_SCHEMAS = Set.of("connector", "ext");

  /** What closes a relation's clause going backwards: a relation follows FROM or JOIN, nothing else. */
  private static final Set<String> CLAUSE_OPENERS = Set.of("select", "where", "group", "order", "having", "limit",
      "offset", "returning", "set", "values", "window", "by", "when", "then", "else", "case");

  private HubRelationProjection() {
  }

  sealed interface Outcome {
    /** The query to run, every hub table read through the card. */
    record Rewritten(String sql) implements Outcome {}

    /** A hub table named in a shape this cannot read; nothing runs. */
    record Refused(String table) implements Outcome {}
  }

  /** {@code physical} answers a hub table's columns (schema-qualified) as the database holds them; asked once per table. */
  static Outcome rewrite(String sql, SchemaCard card, Function<String, Set<String>> physical) {
    Map<String, List<String>> columns = hubTables(card);
    Map<String, Set<String>> held = new HashMap<>();
    List<Token> tokens = SqlLexer.tokenize(sql);
    Set<String> aliases = new HashSet<>();
    StringBuilder out = new StringBuilder();
    int copied = 0;
    for (int i = 0; i + 2 < tokens.size(); i++) {
      String schema = SqlRelation.identifier(tokens.get(i));
      if (!HUB_SCHEMAS.contains(schema) || !isPunct(tokens.get(i + 1), ".") || tokens.get(i + 2).kind() != SqlLexer.Kind.IDENT) {
        continue;
      }
      String table = SqlRelation.identifier(tokens.get(i + 2));
      String fqn = schema + "." + table;
      if (i > 0 && isPunct(tokens.get(i - 1), ".")) return new Outcome.Refused(fqn);   // catalog-qualified
      if (i > 0 && (isOp(tokens.get(i - 1), "::") || isKeyword(tokens.get(i - 1), "as"))) {
        return new Outcome.Refused(fqn);   // a type: a table's row type lists every physical column
      }
      boolean entityView = "connector".equals(schema) && EntityViewCompiler.entityNameFromView(fqn) != null;
      if (entityView) continue;   // read as it is: its columns are its owner's (#2050)
      boolean inFrom = inFromClause(tokens, i);
      if (!inFrom || (i + 3 < tokens.size() && isPunct(tokens.get(i + 3), "("))) {
        // A function of the hub's schema, or an alias named like it (ext.code): no table, nothing to read. A table
        // there is a whole row or a functional cast, its row type.
        if (held.computeIfAbsent(fqn, physical).isEmpty()) continue;
        return new Outcome.Refused(fqn);
      }
      SqlRelation relation = SqlRelation.at(tokens, i);
      boolean columnOfTable = i + 3 < tokens.size() && isPunct(tokens.get(i + 3), ".");
      List<String> cardColumns = relation == null || columnOfTable || suffixed(tokens, relation) ? null : columns.get(fqn);
      if (cardColumns != null) {
        Set<String> holds = held.computeIfAbsent(fqn, physical);
        cardColumns = cardColumns.stream().filter(holds::contains).toList();
      }
      if (cardColumns == null || cardColumns.isEmpty()) return new Outcome.Refused(fqn);
      String alias = relation.alias() != null ? relation.alias() : table;
      if (relation.alias() == null && !aliases.add(alias)) return new Outcome.Refused(fqn);   // connector.x and ext.x
      out.append(sql, copied, tokens.get(i).start())
          .append("(SELECT ").append(cardColumns.stream().map(SqlIdentifiers::quoteIdent).collect(Collectors.joining(", ")))
          .append(" FROM ").append(schema).append('.').append(quoteIdent(table)).append(") AS ")
          .append(quoteIdent(alias));
      copied = tokens.get(relation.next() - 1).end();
      i = relation.next() - 1;
    }
    return new Outcome.Rewritten(out.append(sql, copied, sql.length()).toString());
  }

  /**
   * Whether the name at {@code at} is a relation of a {@code FROM} clause: going back over the clause (a parenthesis
   * skipped whole), the first keyword met is {@code FROM} or {@code JOIN}. A comma in a select list, a function's
   * arguments or a {@code GROUP BY} is no relation's.
   */
  private static boolean inFromClause(List<Token> tokens, int at) {
    if (at == 0 || isKeyword(tokens.get(at - 1), "only")) return false;
    int depth = 0;
    for (int k = at - 1; k >= 0; k--) {
      Token t = tokens.get(k);
      if (isPunct(t, ")")) depth++;
      else if (isPunct(t, "(")) {
        if (depth == 0) return false;   // we are inside a parenthesis that is no FROM clause of ours
        depth--;
      } else if (depth == 0 && t.kind() == SqlLexer.Kind.IDENT && !t.quoted()) {
        String word = t.text().toLowerCase(Locale.ROOT);
        if (word.equals("from")) return k == 0 || !isKeyword(tokens.get(k - 1), "distinct");   // not IS DISTINCT FROM
        if (word.equals("join")) return true;
        if (CLAUSE_OPENERS.contains(word)) return false;
      }
    }
    return false;
  }

  /** A relation-level suffix a sub-query cannot carry: {@code TABLESAMPLE}, or the inheritance {@code *}. */
  private static boolean suffixed(List<Token> tokens, SqlRelation relation) {
    if (relation.next() >= tokens.size()) return false;
    Token next = tokens.get(relation.next());
    return isKeyword(next, "tablesample") || isOp(next, "*") || isPunct(next, "*");
  }

  /**
   * The card's hub tables, schema-qualified as PostgreSQL folds a bare name, with their columns. A name without a schema
   * is {@code connector}'s, as {@link LeakGuard} reads it; an Entity's view is left out.
   */
  private static Map<String, List<String>> hubTables(SchemaCard card) {
    Map<String, List<String>> out = new HashMap<>();
    for (SchemaCard.Table t : card.tables()) {
      String name = t.name().toLowerCase(Locale.ROOT);
      String fqn = name.indexOf('.') > 0 ? name : "connector." + name;
      if (!HUB_SCHEMAS.contains(fqn.substring(0, fqn.indexOf('.')))) continue;
      if (EntityViewCompiler.entityNameFromView(fqn) != null) continue;
      out.put(fqn, t.columns().stream().map(SchemaCard.Column::name).toList());
    }
    return out;
  }
}
