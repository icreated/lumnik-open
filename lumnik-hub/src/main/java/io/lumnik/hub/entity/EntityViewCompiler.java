package io.lumnik.hub.entity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

@ApplicationScoped
public class EntityViewCompiler {

  @Inject
  @io.quarkus.agroal.DataSource("admin")
  DataSource adminDs;

  /** Run DROP/CREATE/GRANT + manifest UPSERT atomically on the admin (BYPASSRLS) datasource. */
  public void apply(EntityManifest m, String yaml) {
    try (Connection c = adminDs.getConnection()) {
      boolean prevAuto = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        for (String sql : buildStatements(m)) {
          try (Statement st = c.createStatement()) {
            st.execute(sql);
          }
        }
        try (var ps = c.prepareStatement(
            "INSERT INTO connector.entity_manifest (name, yaml_text, scopes) VALUES (?, ?, ?) "
            + "ON CONFLICT (name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, "
            + "scopes = EXCLUDED.scopes, updated_at = now()")) {
          ps.setString(1, m.name().toLowerCase(Locale.ROOT));
          ps.setString(2, yaml);
          ps.setArray(3, c.createArrayOf("text", m.scopes().toArray()));
          ps.executeUpdate();
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(prevAuto);
      }
    } catch (Exception e) {
      // 42P01 (undefined_table) naming one of the manifest's OWN source tables is the
      // fresh-install story (connectors never run) — translate it for the door. A
      // missing INTERNAL relation (entity_manifest, a migration fault) must stay a
      // loud 500: a "run your connectors" 409 there would be a lie.
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof java.sql.SQLException sql && "42P01".equals(sql.getSQLState())) {
          String line = firstLine(sql.getMessage());
          if (namesASourceTable(m, line)) {
            throw new MissingSourceTableException(line);
          }
          break;
        }
      }
      throw new RuntimeException("EntityViewCompiler.apply: " + e.toString(), e);
    }
  }

  /** The PG server message's first line, without the "ERROR: " prefix. */
  static String firstLine(String msg) {
    String line = msg == null ? "" : msg.lines().findFirst().orElse("");
    return line.startsWith("ERROR: ") ? line.substring(7) : line;
  }

  /** True when the 42P01 message names one of the manifest's declared source tables. */
  static boolean namesASourceTable(EntityManifest m, String dbFirstLine) {
    var matcher = java.util.regex.Pattern.compile("relation \"([^\"]+)\"").matcher(dbFirstLine);
    if (!matcher.find()) {
      return false;
    }
    String rel = matcher.group(1);
    String bare = rel.substring(rel.lastIndexOf('.') + 1);
    return m.sources().stream().anyMatch(s -> s.table().equalsIgnoreCase(bare));
  }

  static final String SCHEMA = "connector";
  static final String APP_ROLE = "lumnik_app";
  static final String READONLY_ROLE = "lumnik_readonly";

  /**
   * Dollar-quote tag for the guarded GRANT below, instead of a bare {@code $$}. {@code view} is
   * built from the manifest's {@code name} — normally refused by {@code EntityManifestParser}
   * unless it matches {@code [A-Za-z0-9_]+}, but this method must not rely solely on that upstream
   * gate (same "don't trust the caller" principle as {@code RlsDdl}, which this class cannot reuse
   * directly: it lives in a different package, scoped to the connector dynamic-table writers).
   */
  private static final String GRANT_TAG = "$lumnik_view$";

  /** Quote a SQL string literal (single quotes), for table/column name literals inside the SQL text. */
  static String quoteLiteral(String s) {
    return "'" + s.replace("'", "''") + "'";
  }

  static String viewName(String entityName) {
    return SCHEMA + "." + quoteIdent("v_" + entityName.toLowerCase(Locale.ROOT));
  }

  /**
   * Pure: manifest -&gt; ordered [DROP, CREATE security_invoker (form B fusion), GRANT].
   *
   * <p>In three movements: the guards, the unified CTE (one UNION ALL branch per source), and the
   * outer SELECT that folds the branches into one row per match key plus the {@code _conflicts}
   * ledger.
   */
  List<String> buildStatements(EntityManifest m) {
    requireCompilable(m);
    String view = viewName(m.name());
    List<String> canon = m.canonicalColumns();
    Set<String> matchSet = new LinkedHashSet<>(m.match());

    String create = "CREATE VIEW " + view + " WITH (security_invoker = true) AS WITH unified AS (\n"
        + unifiedCte(m, canon) + "\n) " + outerSelect(canon, matchSet, m.match());

    return List.of(
        "DROP VIEW IF EXISTS " + view,
        create,
        "GRANT SELECT ON " + view + " TO " + APP_ROLE,
        "DO " + GRANT_TAG + " BEGIN GRANT SELECT ON " + view + " TO " + READONLY_ROLE
            + "; EXCEPTION WHEN OTHERS THEN NULL; END " + GRANT_TAG);
  }

  /** EntityManifestParser owns these refusals; reaching them here means it was skipped. */
  private static void requireCompilable(EntityManifest m) {
    if (m.name() == null || m.name().isBlank())
      throw new IllegalStateException("manifest has a null/blank name — validate before compilation");
    if (m.match() == null || m.match().isEmpty())
      throw new IllegalStateException("manifest '" + m.name() + "' has no match columns — validate before compilation");
    if (m.sources() == null || m.sources().isEmpty())
      throw new IllegalStateException("manifest '" + m.name() + "' has no sources — validate before compilation");
  }

  /** One UNION ALL branch per source, each projecting the full canonical column list. */
  private String unifiedCte(EntityManifest m, List<String> canon) {
    StringBuilder cte = new StringBuilder();
    for (int i = 0; i < m.sources().size(); i++) {
      if (i > 0) cte.append("\nUNION ALL\n");
      appendSourceBranch(cte, m, m.sources().get(i), i, canon);
    }
    return cte.toString();
  }

  private void appendSourceBranch(StringBuilder cte, EntityManifest m, EntityManifest.Source s,
                                  int ordinal, List<String> canon) {
    if (s == null)
      throw new IllegalStateException("manifest '" + m.name() + "' has a null source entry — validate before compilation");
    if (s.table() == null || s.table().isBlank())
      throw new IllegalStateException("a source has a null/blank table — validate before compilation");
    if (s.map() == null)
      throw new IllegalStateException("source '" + s.table()
          + "' has a null map — manifest must be validated (EntityManifestParser) before compilation");

    Map<String, String> canonToSrc = new HashMap<>();
    for (Map.Entry<String, String> e : s.map().entrySet()) canonToSrc.put(e.getValue(), e.getKey());

    cte.append("SELECT ").append(ordinal).append("::int AS _ord, ")
       .append(quoteLiteral(s.table())).append("::text AS _src");
    for (String c : canon) {
      cte.append(", ");
      String srcCol = canonToSrc.get(c);
      if (srcCol != null) cte.append(quoteIdent(srcCol)).append("::text AS ").append(quoteIdent(c));
      else cte.append("NULL::text AS ").append(quoteIdent(c));
    }
    cte.append(" FROM ").append(SCHEMA).append(".").append(quoteIdent(s.table()));
    appendMatchNotNull(cte, s, canonToSrc, m.match());
  }

  /**
   * A row whose match key is NULL would fold every source into one NULL-key group. The parser
   * guarantees each source maps every match column; failing fast here says so if it did not.
   */
  private void appendMatchNotNull(StringBuilder cte, EntityManifest.Source s,
                                  Map<String, String> canonToSrc, List<String> match) {
    boolean firstWhere = true;
    for (String mc : match) {
      String srcMatchCol = canonToSrc.get(mc);   // physical column name in THIS source
      if (srcMatchCol == null)
        throw new IllegalStateException("source '" + s.table() + "' does not map the match column '"
            + mc + "' — manifest must be validated before compilation");
      cte.append(firstWhere ? " WHERE " : " AND ");
      cte.append(quoteIdent(srcMatchCol)).append(" IS NOT NULL");
      firstWhere = false;
    }
  }

  /** Match keys as-is, one representative per other column, the conflicts ledger, then the GROUP BY. */
  private String outerSelect(List<String> canon, Set<String> matchSet, List<String> match) {
    StringBuilder sel = new StringBuilder("SELECT ");
    boolean first = true;
    for (String c : canon) {
      if (!first) sel.append(", ");
      first = false;
      if (matchSet.contains(c)) {
        sel.append(quoteIdent(c));
      } else {
        sel.append("(array_agg(").append(quoteIdent(c)).append(" ORDER BY _ord, ").append(quoteIdent(c))
           .append(") FILTER (WHERE ").append(quoteIdent(c)).append(" IS NOT NULL))[1] AS ").append(quoteIdent(c));
      }
    }
    appendConflictsLedger(sel, canon, matchSet);
    sel.append(" FROM unified GROUP BY ");
    for (int i = 0; i < match.size(); i++) {
      if (i > 0) sel.append(", ");
      sel.append(quoteIdent(match.get(i)));
    }
    return sel.toString();
  }

  /** Per non-match column: the differing values, with their source, only when there IS a difference. */
  private void appendConflictsLedger(StringBuilder sel, List<String> canon, Set<String> matchSet) {
    sel.append(", jsonb_strip_nulls(jsonb_build_object(");
    boolean firstConf = true;
    for (String c : canon) {
      if (matchSet.contains(c)) continue;
      if (!firstConf) sel.append(", ");
      firstConf = false;
      sel.append(quoteLiteral(c)).append(", CASE WHEN count(DISTINCT ").append(quoteIdent(c))
         .append(") > 1 THEN jsonb_agg(jsonb_build_object('src', _src, 'value', ").append(quoteIdent(c))
         .append(") ORDER BY _ord, ").append(quoteIdent(c)).append(") FILTER (WHERE ").append(quoteIdent(c)).append(" IS NOT NULL) END");
    }
    sel.append(")) AS ").append(quoteIdent("_conflicts"));
  }
}
