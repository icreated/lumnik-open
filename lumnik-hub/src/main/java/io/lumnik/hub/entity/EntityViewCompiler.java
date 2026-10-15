package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.sql.JoinKeys;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

@ApplicationScoped
public class EntityViewCompiler {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  /**
   * Run DROP/CREATE/GRANT + manifest UPSERT atomically on the admin (BYPASSRLS) datasource,
   * refusing to overwrite a name whose stored scopes the caller's grants do not all reach.
   *
   * <p>{@code callerScopes} is an authorization predicate, not a filter ({@code null} = unscoped
   * caller, tenant-wide); {@link ScopeGuardedUpsert} says why the door's earlier check is not enough
   * on its own, and do not "simplify" this one away on the grounds that the door already asked.
   *
   * <p><b>Containment, not membership</b>, and a non-empty stored array — the SQL twin of
   * {@code ScopeGrants.allowsAll}, which this door asks rather than {@code allowsAny}: taking over a
   * manifest tagged {@code [clients, magasin]} while holding only {@code clients} publishes into
   * magasin. {@code '{}' <@ anything} is true, so the {@code cardinality} clause is what keeps a
   * scopeless manifest out of a bound caller's reach — the same rule that stops them creating one.
   *
   * <p><b>The guarded upsert runs FIRST, before the DDL.</b> Order inside one transaction is
   * invisible from outside — a refusal would roll the DROP/CREATE VIEW back either way — but taken
   * first it costs one statement instead of five, and does not take an ACCESS EXCLUSIVE lock on the
   * view name only to hand it straight back. The manifest row is not read by the view it compiles,
   * so nothing here depends on the old order.
   */
  public ScopeGuardedUpsert apply(EntityManifest m, String yaml, Set<String> callerScopes) {
    try (Connection c = adminDs.getConnection()) {
      boolean prevAuto = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        // #669: a SearchIndex write validated against this definition holds the shared side until its
        // commit; rewriting the definition waits for it, so the re-check after this apply sees it.
        EntityDefinitionLock.exclusive(c, m.name());
        // The predicate reads the EXISTING row — which in a DO UPDATE is the one spelled with the
        // table name, `excluded` being the row we proposed.
        try (var ps = c.prepareStatement(
            "INSERT INTO connector.entity_manifest (name, yaml_text, scopes) VALUES (?, ?, ?) "
            + "ON CONFLICT (name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, "
            + "scopes = EXCLUDED.scopes, updated_at = now() "
            + "WHERE (?::text[] IS NULL OR (connector.entity_manifest.scopes <@ ?::text[] "
            + "AND cardinality(connector.entity_manifest.scopes) > 0))")) {
          ps.setString(1, m.name().toLowerCase(Locale.ROOT));
          ps.setString(2, yaml);
          ps.setArray(3, c.createArrayOf("text", m.scopes().toArray()));
          ScopeGuardedUpsert.bindGrants(ps, 4, callerScopes);
          if (ps.executeUpdate() == 0) {
            c.rollback();
            return new ScopeGuardedUpsert.NameHeldOutOfScope();
          }
        }
        for (EntityManifest.Source src : m.sources()) preflight(c, src);
        for (EntityManifest.Join j : m.joins()) preflight(c, j);
        for (String sql : buildStatements(m)) {
          try (Statement st = c.createStatement()) {
            st.execute(sql);
          }
        }
        c.commit();
        return new ScopeGuardedUpsert.Applied();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(prevAuto);
      }
    } catch (MissingSourceTableException | MissingSourceColumnException | MissingLookupColumnException e) {
      throw e;   // already precise; the generic wrapper below would bury it
    } catch (Exception e) {
      // 42P01 (undefined_table) naming one of the manifest's OWN source tables is the
      // fresh-install story (connectors never run) — translate it for the door. A
      // missing INTERNAL relation (entity_manifest, a migration fault) must stay a
      // loud 500: a "run your connectors" 409 there would be a lie.
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof SQLException sql && "42P01".equals(sql.getSQLState())) {
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

  /** Extra work a caller needs inside {@link #delete}'s own transaction — see that method. */
  @FunctionalInterface
  public interface ConnectionWork {
    void run(Connection c) throws SQLException;
  }

  /**
   * The inverse of {@link #apply}: DROP the entity's view and remove its manifest row, atomically
   * on the admin (BYPASSRLS) datasource. {@code DROP VIEW IF EXISTS} is idempotent, so a
   * half-applied entity (row present, view already gone) still deletes cleanly.
   */
  public void delete(String name) {
    delete(name, null);
  }

  /**
   * As {@link #delete(String)}, plus {@code extra} run on this SAME connection before commit — for
   * a caller with its own write that must land in the exact same transaction as the drop (design
   * §4 row 6: {@code EntityWriteResource.delete} purging every tenant's SearchIndex over this
   * entity). Before this overload existed, that purge ran in its own, separately-committed
   * transaction on the admin datasource (which is JTA-disabled — never enlisted in any surrounding
   * one), ahead of this method's own commit: a review of this PR (#709) found the window that left —
   * a purge already landed with the entity's view/manifest row not actually dropped yet if this
   * method's own DROP/DELETE then failed. {@code extra} now runs inside the same
   * commit-or-rollback boundary, closing it for real rather than narrowing it.
   */
  public void delete(String name, ConnectionWork extra) {
    String view = viewName(name);
    try (Connection c = adminDs.getConnection()) {
      boolean prevAuto = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        EntityDefinitionLock.exclusive(c, name);
        try (Statement st = c.createStatement()) {
          st.execute("DROP VIEW IF EXISTS " + view);
        }
        try (var ps = c.prepareStatement("DELETE FROM connector.entity_manifest WHERE name = ?")) {
          ps.setString(1, name.toLowerCase(Locale.ROOT));
          ps.executeUpdate();
        }
        if (extra != null) extra.run(c);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(prevAuto);
      }
    } catch (Exception e) {
      throw new RuntimeException("EntityViewCompiler.delete: " + e.toString(), e);
    }
  }

  /** Names the lookup's physical columns once each; LIMIT 0 reads nothing but keeps the relation locked to commit. */
  static String preflightSql(EntityManifest.Join j) {
    LinkedHashSet<String> cols = new LinkedHashSet<>();
    cols.add(j.to());
    cols.addAll(j.map().keySet());
    return preflightSql(j.table(), cols);
  }

  /** The same probe for a source: every mapped physical column, once. */
  static String preflightSql(EntityManifest.Source s) {
    return preflightSql(s.table(), s.map().keySet());
  }

  private static String preflightSql(String table, Collection<String> cols) {
    return "SELECT " + cols.stream().map(c -> quoteIdent(c)).collect(Collectors.joining(", "))
        + " FROM " + SCHEMA + "." + quoteIdent(table) + " LIMIT 0";
  }

  private void preflight(Connection c, EntityManifest.Source s) throws SQLException {
    probe(c, preflightSql(s), dbMessage -> new MissingSourceColumnException(s.table(), dbMessage));
  }

  private void preflight(Connection c, EntityManifest.Join j) throws SQLException {
    probe(c, preflightSql(j), dbMessage -> new MissingLookupColumnException(j.table(), dbMessage));
  }

  private void probe(Connection c, String sql, Function<String, RuntimeException> missingColumn) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (SQLException e) {
      if ("42P01".equals(e.getSQLState())) throw new MissingSourceTableException(firstLine(e.getMessage()));
      if ("42703".equals(e.getSQLState())) throw missingColumn.apply(firstLine(e.getMessage()));
      throw e;
    }
  }

  /** The PG server message's first line, without the "ERROR: " prefix. */
  static String firstLine(String msg) {
    String line = msg == null ? "" : msg.lines().findFirst().orElse("");
    return line.startsWith("ERROR: ") ? line.substring(7) : line;
  }

  /** True when the 42P01 message names one of the manifest's declared source tables. */
  static boolean namesASourceTable(EntityManifest m, String dbFirstLine) {
    var matcher = Pattern.compile("relation \"([^\"]+)\"").matcher(dbFirstLine);
    if (!matcher.find()) {
      return false;
    }
    String rel = matcher.group(1);
    String bare = rel.substring(rel.lastIndexOf('.') + 1);
    return m.sources().stream().anyMatch(s -> s.table().equalsIgnoreCase(bare));
  }

  static final String SCHEMA = "connector";
  /** The output aliases of a lookup CTE (#846); EntityManifestParser refuses a joined column named like one. */
  static final String LOOKUP_KEY = "_k";
  static final String LOOKUP_COUNT = "_n";
  static final String LOOKUP_AMBIGUITY = "_amb";

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

  public static String viewName(String entityName) {
    return SCHEMA + "." + quoteIdent("v_" + entityName.toLowerCase(Locale.ROOT));
  }

  /**
   * The entity name a {@code connector.v_<name>} view was compiled from — the inverse of
   * {@link #viewName}. Returns {@code null} for anything not shaped like one of our entity views
   * (a foreign view, in any schema), so the caller can tell an entity view from a stranger's.
   */
  public static String entityNameFromView(String qualifiedView) {
    String prefix = SCHEMA + ".v_";
    return qualifiedView != null && qualifiedView.startsWith(prefix) ? qualifiedView.substring(prefix.length()) : null;
  }

  /**
   * Pure: manifest -&gt; ordered [DROP, CREATE security_invoker (form B fusion), GRANT].
   *
   * <p>In three movements: the guards, the unified CTE (one UNION ALL branch per source), and the
   * outer SELECT that folds the branches into one row plus the {@code _conflicts} ledger.
   * {@code resolution:} absent groups by the declared {@code match:} columns, byte for byte as
   * today (#618 v1 compatibility); {@code resolution:} present groups by the canonical id each
   * source branch resolves to via {@link #appendResolvedSourceBranch} (design doc §8).
   */
  List<String> buildStatements(EntityManifest m) {
    requireCompilable(m);
    String view = viewName(m.name());
    List<String> canon = m.fusedColumns();
    boolean resolved = m.resolution() != null;

    // cte MUST build first: it is what fails fast on a malformed match/identity element (e.g. a
    // null match column), and outerSelect's own quoteIdent(null) would otherwise NPE first.
    String cte = resolved ? unifiedCteResolved(m, canon) : unifiedCte(m, canon);
    String outer = resolved
        ? outerSelectResolved(canon)
        : outerSelect(canon, new LinkedHashSet<>(m.match()), m.match());

    String fused = "WITH unified AS (\n" + cte + "\n) " + outer;
    String body = m.joins().isEmpty() ? fused : joinedBody(m, fused);
    String create = "CREATE VIEW " + view + " WITH (security_invoker = true) AS " + body;

    return List.of(
        "DROP VIEW IF EXISTS " + view,
        create,
        "GRANT SELECT ON " + view + " TO " + APP_ROLE,
        "DO " + GRANT_TAG + " BEGIN GRANT SELECT ON " + view + " TO " + READONLY_ROLE
            + "; EXCEPTION WHEN OTHERS THEN NULL; END " + GRANT_TAG);
  }

  /** #846: wrap the untouched fused view in one LEFT JOIN per lookup. See the design doc §5. */
  private String joinedBody(EntityManifest m, String fusedSql) {
    List<EntityManifest.Join> joins = m.joins();
    String n = quoteIdent(LOOKUP_COUNT);
    StringBuilder sb = new StringBuilder("WITH fused AS (").append(fusedSql).append(")");
    for (int k = 0; k < joins.size(); k++) sb.append(",\n").append(lookupCte(joins.get(k), k));
    sb.append("\nSELECT ");
    for (String c : m.fusedColumns()) sb.append("f.").append(quoteIdent(c)).append(", ");
    for (int k = 0; k < joins.size(); k++) {
      for (String canon : joins.get(k).map().values()) {
        sb.append("CASE WHEN lk").append(k).append('.').append(n).append(" = 1 THEN lk").append(k).append('.')
          .append(quoteIdent(canon)).append(" END AS ").append(quoteIdent(canon)).append(", ");
      }
    }
    // The technical columns of the fused body travel through unchanged: a match: view keys its rows by
    // _canonical_key, a resolution: view by _entity_id (+ _resolution, _handle) — #850, #928.
    if (m.resolution() != null) {
      sb.append("f.").append(quoteIdent("_entity_id")).append(", f.").append(quoteIdent("_resolution"))
        .append(", f.").append(quoteIdent("_handle"));
    } else {
      sb.append("f.").append(quoteIdent("_canonical_key"));
    }
    sb.append(", f.").append(quoteIdent("_conflicts"));
    for (int k = 0; k < joins.size(); k++) {
      sb.append(" || (CASE WHEN lk").append(k).append('.').append(n).append(" > 1 THEN lk").append(k)
        .append('.').append(quoteIdent(LOOKUP_AMBIGUITY)).append(" ELSE '{}'::jsonb END)");
    }
    sb.append(" AS ").append(quoteIdent("_conflicts")).append("\nFROM fused f");
    for (int k = 0; k < joins.size(); k++) {
      sb.append("\nLEFT JOIN lk").append(k).append(" ON ")
        .append(JoinKeys.normalised("f." + quoteIdent(joins.get(k).from())))
        .append(" = lk").append(k).append('.').append(quoteIdent(LOOKUP_KEY));
    }
    return sb.toString();
  }

  /**
   * One row per distinct key, with its multiplicity {@code _n}. Every aggregate shares ONE total
   * order — the tuple of all projected physical columns, ::text COLLATE "C" NULLS LAST — so element i
   * of every array is the same lookup row and the JSON bytes do not depend on physical row order.
   *
   * <p>{@code MATERIALIZED} (#871): a plain {@code WITH lk0 AS (...)} is referenced exactly once
   * ({@code joinedBody}'s own {@code LEFT JOIN}), so Postgres 12+ is free to inline it — and a filter
   * on any OTHER canonical column of {@code fused} (every one of them wrapped in an
   * {@code array_agg(...)[1]} the planner cannot estimate the selectivity of) always costs that
   * expression a {@code rows=1} guess. When the planner picks a Nested Loop on that guess, an inlined
   * {@code lk0} is not a relation it can cache across outer rows — it is this CTE's own
   * {@code GroupAggregate} pipeline, re-run once per outer row. Measured: 10.5 s at 10,000 fused rows
   * against 208 ms for the identical read with no filter (#868's verification, #871). Forcing
   * materialization makes Postgres compute this lookup exactly once regardless of that misestimate —
   * confirmed with {@code enable_hashjoin}/{@code enable_mergejoin} off (which would otherwise hide a
   * regression behind a planner that simply avoided the Nested Loop that day): the lookup's own
   * {@code GroupAggregate} drops to {@code loops=1} and the Nested Loop's inner side becomes a
   * {@code CTE Scan}, reading the already-computed tuplestore. See
   * {@code EntityJoinLookupMaterializedIT}.
   */
  private String lookupCte(EntityManifest.Join j, int k) {
    String ord = j.map().keySet().stream()
        .map(phys -> quoteIdent(phys) + "::text COLLATE \"C\" NULLS LAST")
        .collect(Collectors.joining(", "));
    StringBuilder sb = new StringBuilder("lk").append(k).append(" AS MATERIALIZED (SELECT ")
        .append(JoinKeys.normalised(quoteIdent(j.to()))).append(" AS ").append(quoteIdent(LOOKUP_KEY))
        .append(", count(*) AS ").append(quoteIdent(LOOKUP_COUNT));
    j.map().forEach((phys, canon) -> sb.append(", (array_agg(").append(quoteIdent(phys))
        .append("::text ORDER BY ").append(ord).append("))[1] AS ").append(quoteIdent(canon)));
    String ambiguity = j.map().entrySet().stream()
        .map(e -> quoteLiteral(e.getValue()) + ", jsonb_agg(jsonb_build_object('src', "
            + quoteLiteral(j.table()) + "::text, 'value', " + quoteIdent(e.getKey())
            + "::text) ORDER BY " + ord + ")")
        .collect(Collectors.joining(", "));
    return sb.append(", jsonb_build_object(").append(ambiguity).append(") AS ")
        .append(quoteIdent(LOOKUP_AMBIGUITY)).append(" FROM ").append(SCHEMA).append('.')
        .append(quoteIdent(j.table())).append(" WHERE ").append(quoteIdent(j.to()))
        .append(" IS NOT NULL GROUP BY 1)").toString();
  }

  /** A relation shaped like the entity view for one probe, and the columns whose values bind its {@code ?}, in order. */
  public record ProbeRelation(String sql, List<String> paramColumns) {}

  /**
   * #965 — the entity view restricted to what ONE disputed-rows probe reads, for a {@code match:}
   * entity: the match columns, the {@code probed} column's representative and ledger, and the
   * representative of each other filtered column. It fuses only the records that can pass the probe
   * instead of every record of the entity: the view's {@code _conflicts} ledger cannot be pushed
   * below its {@code GROUP BY}, so reading it through the view computes it for the whole entity
   * (7x the answer at 100k records, 15 s at 1M).
   *
   * <p>A record can pass only if a source holds one of the probed values (CTE {@code h}) AND its
   * sources disagree on the probed column (CTE {@code k}: two distinct non-null values, the ledger's
   * own condition). Both are necessary conditions of the probe's predicate, so every record that could
   * pass is kept whole — all its source rows, which the aggregates need — and fused by
   * {@link #representative} and {@link #ledgerValue}, the view's own. A filter on a {@code match:}
   * column also narrows {@code h}, as Postgres does through the view. {@code k} reads only the keys
   * {@code h} found: its cost follows the probed value's frequency, not the entity's size.
   *
   * <p>Empty — the caller reads the view — for a {@code resolution:} entity (no match key to group
   * by), when a column involved comes from a {@code joins:} lookup (whose values the sources do not
   * hold), or when no source maps the probed column.
   */
  public Optional<ProbeRelation> probeRelation(EntityManifest m, String probed, List<String> filtered) {
    List<String> match = m.match();
    if (m.resolution() != null || match == null || match.isEmpty()) return Optional.empty();
    Set<String> joined = m.joins().stream().flatMap(j -> j.map().values().stream()).collect(Collectors.toSet());
    if (match.contains(probed) || joined.contains(probed) || filtered.stream().anyMatch(joined::contains)) {
      return Optional.empty();
    }
    List<String> keyFilters = filtered.stream().filter(match::contains).distinct().toList();
    List<String> carried = new ArrayList<>(List.of(probed));
    filtered.stream().filter(c -> !match.contains(c) && !carried.contains(c)).forEach(carried::add);
    List<String> canon = new ArrayList<>(match);
    canon.addAll(carried);

    String keyAliases = IntStream.range(0, match.size()).mapToObj(i -> quoteIdent("_k" + i)).collect(Collectors.joining(", "));
    List<String> params = new ArrayList<>();
    List<String> holders = new ArrayList<>();
    List<String> readers = new ArrayList<>();
    List<String> branches = new ArrayList<>();
    for (int i = 0; i < m.sources().size(); i++) {
      EntityManifest.Source s = m.sources().get(i);
      StringBuilder branch = new StringBuilder();
      appendSourceBranch(branch, m, s, i, canon);   // also the guard: every match column is mapped
      Map<String, String> canonToSrc = validatedCanonToSrc(m, s);
      List<String> keyCols = match.stream().map(mc -> quoteIdent(canonToSrc.get(mc)) + "::text").toList();
      String tuple = "(" + String.join(", ", keyCols) + ")";
      branches.add(branch.append(" AND ").append(tuple).append(" IN (SELECT ").append(keyAliases).append(" FROM k)").toString());

      String physProbed = canonToSrc.get(probed);
      if (physProbed == null) continue;   // this source holds no value for the column: nothing for h or k
      String table = SCHEMA + "." + quoteIdent(s.table());
      String keySelect = IntStream.range(0, keyCols.size())
          .mapToObj(j -> keyCols.get(j) + " AS " + quoteIdent("_k" + j)).collect(Collectors.joining(", "));
      StringBuilder holder = new StringBuilder("SELECT ").append(keySelect).append(" FROM ").append(table)
          .append(" WHERE ").append(quoteIdent(physProbed)).append("::text = ANY(?)");
      params.add(probed);
      for (String kf : keyFilters) {
        holder.append(" AND ").append(quoteIdent(canonToSrc.get(kf))).append("::text = ANY(?)");
        params.add(kf);
      }
      holders.add(holder.toString());
      readers.add("SELECT " + keySelect + ", " + quoteIdent(physProbed) + "::text AS v FROM " + table
          + " WHERE " + tuple + " IN (SELECT " + keyAliases + " FROM h)");
    }
    if (holders.isEmpty()) return Optional.empty();

    StringBuilder sb = new StringBuilder("(WITH h AS (").append(String.join("\nUNION\n", holders)).append("),\n")
        .append("k AS (SELECT ").append(keyAliases).append(" FROM (").append(String.join("\nUNION ALL\n", readers))
        .append(") x GROUP BY ").append(keyAliases).append(" HAVING min(v) <> max(v)),\n")
        .append("unified AS (").append(String.join("\nUNION ALL\n", branches)).append(")\nSELECT ");
    String groupBy = match.stream().map(c -> quoteIdent(c)).collect(Collectors.joining(", "));
    sb.append(groupBy);
    for (String c : carried) sb.append(", ").append(representative(c)).append(" AS ").append(quoteIdent(c));
    sb.append(", jsonb_strip_nulls(jsonb_build_object(").append(quoteLiteral(probed)).append(", ")
      .append(ledgerValue(probed)).append(")) AS ").append(quoteIdent("_conflicts"))
      .append("\nFROM unified GROUP BY ").append(groupBy).append(")");
    return Optional.of(new ProbeRelation(sb.toString(), List.copyOf(params)));
  }

  /** EntityManifestParser owns these refusals; reaching them here means it was skipped. */
  private static void requireCompilable(EntityManifest m) {
    if (m.name() == null || m.name().isBlank())
      throw new IllegalStateException("manifest has a null/blank name — validate before compilation");
    boolean hasMatch = m.match() != null && !m.match().isEmpty();
    boolean hasResolution = m.resolution() != null;
    if (!hasMatch && !hasResolution)
      throw new IllegalStateException(
          "manifest '" + m.name() + "' has neither match nor resolution columns — validate before compilation");
    if (m.sources() == null || m.sources().isEmpty())
      throw new IllegalStateException("manifest '" + m.name() + "' has no sources — validate before compilation");
    if (hasResolution && (m.resolution().identity() == null || m.resolution().identity().isEmpty()))
      throw new IllegalStateException(
          "manifest '" + m.name() + "' resolution has no identity columns — validate before compilation");
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
    Map<String, String> canonToSrc = validatedCanonToSrc(m, s);

    cte.append("SELECT ").append(ordinal).append("::int AS _ord, ")
       .append(quoteLiteral(s.table())).append("::text AS _src");
    appendCanonicalProjections(cte, canonToSrc, canon, "");
    cte.append(" FROM ").append(SCHEMA).append(".").append(quoteIdent(s.table()));
    appendMatchNotNull(cte, s, canonToSrc, m.match());
  }

  /** Shared null/blank guards, same defensive posture for both the match and the resolved branch. */
  private static Map<String, String> validatedCanonToSrc(EntityManifest m, EntityManifest.Source s) {
    if (s == null)
      throw new IllegalStateException("manifest '" + m.name() + "' has a null source entry — validate before compilation");
    if (s.table() == null || s.table().isBlank())
      throw new IllegalStateException("a source has a null/blank table — validate before compilation");
    if (s.map() == null)
      throw new IllegalStateException("source '" + s.table()
          + "' has a null map — manifest must be validated (EntityManifestParser) before compilation");

    Map<String, String> canonToSrc = new HashMap<>();
    for (Map.Entry<String, String> e : s.map().entrySet()) canonToSrc.put(e.getValue(), e.getKey());
    return canonToSrc;
  }

  /**
   * Every canonical column, projected from its physical source column or NULL when unmapped.
   * {@code qualifier} prefixes each physical column ({@code ""} when the source is the only relation).
   */
  private void appendCanonicalProjections(StringBuilder cte, Map<String, String> canonToSrc, List<String> canon,
                                          String qualifier) {
    for (String c : canon) {
      cte.append(", ");
      String srcCol = canonToSrc.get(c);
      if (srcCol != null) cte.append(qualifier).append(quoteIdent(srcCol)).append("::text AS ").append(quoteIdent(c));
      else cte.append("NULL::text AS ").append(quoteIdent(c));
    }
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

  // -------------------------------------------------------------------------------------------
  // resolution: projection (#618, design doc §8).
  // -------------------------------------------------------------------------------------------

  /** One UNION ALL branch per source, each LEFT JOINing the crosswalk on its own identity column. */
  private String unifiedCteResolved(EntityManifest m, List<String> canon) {
    String entityType = m.name().toLowerCase(Locale.ROOT);
    StringBuilder cte = new StringBuilder();
    for (int i = 0; i < m.sources().size(); i++) {
      if (i > 0) cte.append("\nUNION ALL\n");
      appendResolvedSourceBranch(cte, m, m.sources().get(i), i, canon, entityType);
    }
    return cte.toString();
  }

  /**
   * Same shape as {@link #appendSourceBranch}, plus a per-row {@code _source_id} (this source's
   * declared identity value) and a {@code LEFT JOIN core.entity_identity} exposing {@code
   * _entity_id}/{@code _resolution}. An INNER join would silently drop a row nothing has resolved
   * yet — the whole reason this is a LEFT join (design doc §8, and #618's acceptance: unresolved
   * rows must not vanish).
   *
   * <p>The source is aliased {@code s} and every physical column is read as {@code s."col"} (#967):
   * unqualified, a source column named like one of {@code ei}'s own ({@code state}, {@code source},
   * {@code source_id}, {@code created_at}…) is ambiguous and the CREATE VIEW fails.
   */
  private void appendResolvedSourceBranch(StringBuilder cte, EntityManifest m, EntityManifest.Source s,
                                          int ordinal, List<String> canon, String entityType) {
    Map<String, String> canonToSrc = validatedCanonToSrc(m, s);
    String identity = "s." + quoteIdent(m.resolution().physicalIdentityColumn(s));

    cte.append("SELECT ").append(ordinal).append("::int AS _ord, ")
       .append(quoteLiteral(s.table())).append("::text AS _src, ")
       .append(identity).append("::text AS ").append(quoteIdent("_source_id"));
    appendCanonicalProjections(cte, canonToSrc, canon, "s.");
    cte.append(", ei.entity_id AS ").append(quoteIdent("_entity_id"))
       .append(", ei.state AS ").append(quoteIdent("_resolution"));
    cte.append(" FROM ").append(SCHEMA).append(".").append(quoteIdent(s.table())).append(" s")
       .append(" LEFT JOIN core.entity_identity ei ON ei.entity_type = ").append(quoteLiteral(entityType))
       .append(" AND ei.source = ").append(quoteLiteral(s.table()))
       .append(" AND ei.source_id = ").append(identity).append("::text");
    cte.append(" WHERE ").append(identity).append(" IS NOT NULL");
  }

  /** A source row's address, {@code <table>:<identity>} — the group key of an unresolved row and its {@code _handle}. */
  private static final String SOURCE_HANDLE = quoteIdent("_src") + " || ':' || " + quoteIdent("_source_id");

  /**
   * Every canonical column goes through the same representative/conflicts machinery as v1's
   * non-match columns — an empty match set, reusing {@link #outerSelect}'s per-column shape rather
   * than a second copy. The group key is {@code coalesce(_entity_id::text, _src || ':' ||
   * _source_id)}: a resolved row's group is its canonical id, and an unresolved row is a group of
   * one so it neither vanishes (an INNER join would drop it) nor collapses into every other
   * unresolved row (a bare {@code GROUP BY _entity_id} would fold every NULL together — the same
   * defect {@link #appendMatchNotNull} already guards against for a NULL match key).
   */
  private String outerSelectResolved(List<String> canon) {
    StringBuilder sel = new StringBuilder("SELECT ");
    // Postgres has no built-in MAX/MIN aggregate for uuid, and this reuses the exact same
    // representative pattern as every canonical column below rather than a second mechanism —
    // constant within a group by construction (see class javadoc above), so first-non-null wins.
    sel.append("(array_agg(").append(quoteIdent("_entity_id")).append(" ORDER BY _ord) FILTER (WHERE ")
       .append(quoteIdent("_entity_id")).append(" IS NOT NULL))[1] AS ").append(quoteIdent("_entity_id"))
       .append(", (array_agg(").append(quoteIdent("_resolution")).append(" ORDER BY _ord) FILTER (WHERE ")
       .append(quoteIdent("_resolution")).append(" IS NOT NULL))[1] AS ").append(quoteIdent("_resolution"));
    // The address of a group nothing has resolved (#928): the very coalesce fallback below. Every row of
    // such a group shares one (_src, _source_id), so min() is that pair; a resolved group has none.
    sel.append(", min(").append(SOURCE_HANDLE).append(") FILTER (WHERE ")
       .append(quoteIdent("_entity_id")).append(" IS NULL) AS ").append(quoteIdent("_handle"));
    for (String c : canon) {
      sel.append(", ").append(representative(c)).append(" AS ").append(quoteIdent(c));
    }
    appendConflictsLedger(sel, canon, Set.of());
    sel.append(" FROM unified GROUP BY coalesce(").append(quoteIdent("_entity_id")).append("::text, ")
       .append(SOURCE_HANDLE).append(")");
    return sel.toString();
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
        sel.append(representative(c)).append(" AS ").append(quoteIdent(c));
      }
    }
    sel.append(", ").append(canonicalKeyExpression(match));
    appendConflictsLedger(sel, canon, matchSet);
    sel.append(" FROM unified GROUP BY ");
    for (int i = 0; i < match.size(); i++) {
      if (i > 0) sel.append(", ");
      sel.append(quoteIdent(match.get(i)));
    }
    return sel.toString();
  }

  /**
   * Mirrors {@link io.lumnik.hub.search.mark.MatchKeyEncoding#encode} exactly:
   * "<n>|<len1>:<v1>|<len2>:<v2>|…", octet_length() standing in for Java's UTF-8 byte length. Built
   * once per view, over the match columns in their declared order — the same order MatchKeyEncoding's
   * caller must use when re-encoding a candidate row's tuple for comparison (#671 design doc §1).
   * Each column is cast to {@code ::text} at the point of use (Copilot review, #671) — {@code
   * appendCanonicalProjections} already casts every canonical column, match ones included, when it
   * builds the {@code unified} CTE these reference, but repeating the cast here means this
   * expression's own correctness for a non-text physical column (e.g. boolean) does not depend on a
   * reader tracing that back to a different method.
   *
   * <p>No aggregation needed — {@code GROUP BY match} already makes every match column functionally
   * single-valued per group, so this is a plain expression over already-grouped columns, valid under
   * Postgres's functional-dependency rule for {@code GROUP BY}.
   *
   * <p>Appended unconditionally to every {@code match:} entity's view, whether or not any {@code
   * SearchIndex} ever ends up declared over it (code review, #671): making it conditional would need
   * the view recompiled on every SearchIndex apply/delete against this entity, a coupling the design
   * doc never introduces elsewhere. Deliberately left as-is — {@code GROUP BY} means Postgres cannot
   * prune this expression when a reader's own query doesn't select it, but the expression itself is a
   * handful of {@code octet_length}/concat calls per row, cheap next to the view's own conflicts-ledger
   * JSON building and multi-source UNION, both already unconditional for the exact same reason.
   */
  private static String canonicalKeyExpression(List<String> match) {
    StringBuilder expr = new StringBuilder("(").append(match.size()).append("::text");
    for (String col : match) {
      String q = quoteIdent(col) + "::text";
      expr.append(" || '|' || octet_length(").append(q).append(")::text || ':' || ").append(q);
    }
    expr.append(") AS ").append(quoteIdent("_canonical_key"));
    return expr.toString();
  }

  /** Per non-match column: the differing values, with their source, only when there IS a difference. */
  private void appendConflictsLedger(StringBuilder sel, List<String> canon, Set<String> matchSet) {
    sel.append(", jsonb_strip_nulls(jsonb_build_object(");
    boolean firstConf = true;
    for (String c : canon) {
      if (matchSet.contains(c)) continue;
      if (!firstConf) sel.append(", ");
      firstConf = false;
      sel.append(quoteLiteral(c)).append(", ").append(ledgerValue(c));
    }
    sel.append(")) AS ").append(quoteIdent("_conflicts"));
  }

  /**
   * The one fusion rule for a non-match column's value: the first non-null across the sources, in
   * source order. The view and the disputed-rows probe ({@link #probeRelation}) both render it here.
   */
  private static String representative(String c) {
    return "(array_agg(" + quoteIdent(c) + " ORDER BY _ord, " + quoteIdent(c)
        + ") FILTER (WHERE " + quoteIdent(c) + " IS NOT NULL))[1]";
  }

  /** The ledger of a non-match column: every source's value, only when the sources DISAGREE (same sharing as above). */
  private static String ledgerValue(String c) {
    return "CASE WHEN count(DISTINCT " + quoteIdent(c) + ") > 1 THEN jsonb_agg(jsonb_build_object('src', _src, 'value', "
        + quoteIdent(c) + ") ORDER BY _ord, " + quoteIdent(c) + ") FILTER (WHERE " + quoteIdent(c) + " IS NOT NULL) END";
  }
}
