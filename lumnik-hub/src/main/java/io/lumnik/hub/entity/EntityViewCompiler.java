package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.error.Throwables;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.sql.JoinKeys;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.stream.Stream;

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
        for (String sql : buildStatements(m, sources(c, m))) {
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
      String line = undefinedTableLine(e);
      if (line != null && namesASourceTable(m, line)) {
        throw new MissingSourceTableException(line);
      }
      throw new RuntimeException("EntityViewCompiler.apply: " + e.toString(), e);
    }
  }

  /** The first line of the first {@code 42P01} (undefined_table) in the cause chain, or null. */
  static String undefinedTableLine(Throwable failure) {
    for (Throwable t : Throwables.causeChain(failure)) {
      if (t instanceof SQLException sql && "42P01".equals(sql.getSQLState())) {
        return firstLine(sql.getMessage());
      }
    }
    return null;
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

  /**
   * #1185: the manifest's tables (sources and lookups) that keep their row history — those
   * {@code DynamicTableWriter} creates, insert-only, every version of a source row stored under the
   * {@code _run_id} that brought it and ordered by {@code id}. A table without both columns (a hybrid
   * REST target upserts by {@code external_id}; a table made by hand) holds one version per row and is
   * read whole. Asked of the catalog, not {@code information_schema}, which hides what the role cannot read.
   */
  private static Set<String> historyTables(Connection c, EntityManifest m) throws SQLException {
    String[] tables = tables(m);
    Set<String> history = new HashSet<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT r.relname FROM pg_catalog.pg_class r JOIN pg_catalog.pg_attribute a ON a.attrelid = r.oid"
            + " WHERE r.relnamespace = " + quoteLiteral(SCHEMA) + "::regnamespace AND r.relname = ANY(?)"
            + " AND a.attname IN ('id', '_run_id') AND NOT a.attisdropped GROUP BY r.relname HAVING count(*) = 2")) {
      ps.setArray(1, c.createArrayOf("text", tables));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) history.add(rs.getString(1));
      }
    }
    return history;
  }

  private static String[] tables(EntityManifest m) {
    return Stream.concat(m.sources().stream().map(EntityManifest.Source::table),
        m.joins().stream().map(EntityManifest.Join::table)).toArray(String[]::new);
  }

  /**
   * What the catalog says of the manifest's tables when the view is compiled: which keep their row
   * history (#1185), and which columns hold a date or a timestamp (#1325), table → column → type name.
   */
  public record Sources(Set<String> history, Map<String, Map<String, String>> temporal) {
    /** No column typed: every canonical column fused as text, the shape before #1325. */
    public static Sources of(Set<String> history) {
      return new Sources(history, Map.of());
    }

    String typeOf(String table, String column) {
      return temporal.getOrDefault(table, Map.of()).get(column);
    }
  }

  /** The catalog's answer for {@code m}'s tables, read on {@code c}. */
  public static Sources sources(Connection c, EntityManifest m) throws SQLException {
    return new Sources(historyTables(c, m), temporalColumns(c, m));
  }

  private static Map<String, Map<String, String>> temporalColumns(Connection c, EntityManifest m) throws SQLException {
    Map<String, Map<String, String>> temporal = new HashMap<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT r.relname, a.attname, t.typname FROM pg_catalog.pg_class r"
            + " JOIN pg_catalog.pg_attribute a ON a.attrelid = r.oid JOIN pg_catalog.pg_type t ON t.oid = a.atttypid"
            + " WHERE r.relnamespace = " + quoteLiteral(SCHEMA) + "::regnamespace AND r.relname = ANY(?)"
            + " AND a.attnum > 0 AND NOT a.attisdropped AND t.typname IN ('date', 'timestamp', 'timestamptz')")) {
      ps.setArray(1, c.createArrayOf("text", tables(m)));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) temporal.computeIfAbsent(rs.getString(1), t -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
      }
    }
    return temporal;
  }

  /**
   * #1325: the type a canonical column keeps once fused. The same business date lands as a
   * {@code timestamptz} from an ERP (an Oracle DATE, a naive timestamp: wall clock read in the
   * connection's zone, UTC by default), as a {@code date} from a PostgreSQL date or a CSV parsed with a
   * date-only pattern, as text from a CSV left as written. Every source a date → {@code date}; dates and timestamps mixed → {@code timestamptz}, a
   * date being its midnight UTC ({@code TemporalText}'s one rule); one source anything else → null, text
   * as before: a written date is not guessed. A {@code match:} column too: the group is the aligned
   * value, and {@code _canonical_key} encodes that value's text ({@link #matchKeyExpressions}).
   */
  static String fusedType(EntityManifest m, Sources sources, String canon) {
    boolean any = false;
    boolean allDates = true;
    for (EntityManifest.Source s : m.sources()) {
      String phys = validatedCanonToSrc(m, s).get(canon);
      if (phys == null) continue;
      String type = sources.typeOf(s.table(), phys);
      if (type == null) return null;
      any = true;
      allDates &= type.equals("date");
    }
    return !any ? null : allDates ? "date" : "timestamptz";
  }

  /**
   * One source's match key, column by column, as the view groups it (#1325): a reader that must agree with
   * {@code _canonical_key} — the search marks, written from each source table on its own — casts these to text
   * in UTC, as the view does. A key one source holds as a date and another as a timestamp is one value.
   */
  public static List<String> matchKeyExpressions(EntityManifest m, Sources sources, String table) {
    EntityManifest.Source s = m.sources().stream().filter(x -> x.table().equals(table)).findFirst().orElseThrow();
    Map<String, String> canonToSrc = validatedCanonToSrc(m, s);
    return m.match().stream().map(mc -> {
      String phys = canonToSrc.get(mc);
      return aligned(quoteIdent(phys), sources.typeOf(table, phys), fusedType(m, sources, mc));
    }).toList();
  }

  /** A lookup column's own type: one source, nothing to align. */
  private static String lookupType(Sources sources, EntityManifest.Join j, String phys) {
    String type = sources.typeOf(j.table(), phys);
    return type == null ? null : type.equals("date") ? "date" : "timestamptz";
  }

  /**
   * {@code column} as the {@code fused} type: text when that is null, as it is when it already has the
   * type, and otherwise a date (its midnight) or a naive timestamp read as UTC — never in the session's
   * zone, which is the hub JVM's.
   */
  private static String typed(String column, String sourceType, String fused) {
    return fused == null ? column + "::text" : aligned(column, sourceType, fused);
  }

  /**
   * {@code column} equal to one of the bound values, as {@code DisputedRows} compares the view: a date or a
   * timestamp column as an instant (the database's own cast, in UTC), any other as its exact text (#1326 review).
   */
  private static String anyOf(String column, String sourceType, String fused) {
    return fused == null ? column + "::text = ANY(?)"
        : "(" + aligned(column, sourceType, fused) + ")::timestamptz = ANY(?::timestamptz[])";
  }

  /** {@code column} as the {@code fused} type, or as it is when there is none (text) or it already has it. */
  private static String aligned(String column, String sourceType, String fused) {
    return fused == null || fused.equals(sourceType) ? column : "(" + column + "::timestamp AT TIME ZONE 'UTC')";
  }

  /**
   * #1185: the relation a branch or a lookup reads for {@code table}, under {@code alias} ({@code null}:
   * the table's own name). Per {@code key}, a history table's current rows are those of the newest run
   * that brought the key — the run of its highest {@code id}: an older version is neither the value nor
   * a disagreement, while two rows ONE run brought for a key still disagree. A source is partitioned by its
   * plain key columns, so a filter on the key still reaches the table; a lookup by the key its join compares
   * ({@link #lookupToKey}): {@code 7.0} then {@code 7} is one key, and a day-keyed join's times on one day are one
   * key (#1329). Any other table is read as it is.
   */
  private static String current(String table, List<String> key, Set<String> history, String alias) {
    String rel = SCHEMA + "." + quoteIdent(table);
    if (!history.contains(table)) return alias == null ? rel : rel + " " + alias;
    return "(SELECT * FROM (SELECT *, first_value(_run_id) OVER (PARTITION BY " + String.join(", ", key)
        + " ORDER BY id DESC) AS " + LATEST_RUN + " FROM " + rel + ") w WHERE _run_id IS NOT DISTINCT FROM "
        + LATEST_RUN + ") " + (alias == null ? quoteIdent(table) : alias);
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
  /** The run of a key's newest row, beside a history table's own columns (#1185); named to clash with none. */
  private static final String LATEST_RUN = quoteIdent("_lumnik_latest_run");

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
   * The compiled view's column types as the catalog names them — {@code text}, {@code date},
   * {@code timestamp with time zone} (#1325) — empty when the view is not compiled. What the card
   * tells Ask and views, and what an entity read filters by.
   */
  public static Map<String, String> viewColumnTypes(Connection c, String entityName) throws SQLException {
    Map<String, String> types = new HashMap<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod)"
        + " FROM pg_catalog.pg_attribute a WHERE a.attrelid = pg_catalog.to_regclass(?) AND a.attnum > 0"
        + " AND NOT a.attisdropped")) {
      ps.setString(1, viewName(entityName));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) types.put(rs.getString(1), rs.getString(2));
      }
    }
    return types;
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
  List<String> buildStatements(EntityManifest m, Set<String> history) {
    return buildStatements(m, Sources.of(history));
  }

  List<String> buildStatements(EntityManifest m, Sources sources) {
    requireCompilable(m);
    String view = viewName(m.name());
    List<String> canon = m.fusedColumns();
    boolean resolved = m.resolution() != null;

    // cte MUST build first: it is what fails fast on a malformed match/identity element (e.g. a
    // null match column), and outerSelect's own quoteIdent(null) would otherwise NPE first.
    Map<String, Fusion> fusions = new HashMap<>();
    for (String c : canon) fusions.put(c, fusion(m, sources, c));
    String cte = resolved ? unifiedCteResolved(m, canon, sources) : unifiedCte(m, canon, sources);
    String outer = resolved
        ? outerSelectResolved(canon, fusions)
        : outerSelect(canon, new LinkedHashSet<>(m.match()), m.match(), fusions);

    String fused = "WITH unified AS (\n" + cte + "\n) " + outer;
    String body = m.joins().isEmpty() ? fused : joinedBody(m, fused, sources);
    String create = "CREATE VIEW " + view + " WITH (security_invoker = true) AS " + body;

    return List.of(
        "DROP VIEW IF EXISTS " + view,
        create,
        "GRANT SELECT ON " + view + " TO " + APP_ROLE,
        "DO " + GRANT_TAG + " BEGIN GRANT SELECT ON " + view + " TO " + READONLY_ROLE
            + "; EXCEPTION WHEN OTHERS THEN NULL; END " + GRANT_TAG);
  }

  /** #846: wrap the untouched fused view in one LEFT JOIN per lookup. See the design doc §5. */
  private String joinedBody(EntityManifest m, String fusedSql, Sources sources) {
    List<EntityManifest.Join> joins = m.joins();
    String n = quoteIdent(LOOKUP_COUNT);
    StringBuilder sb = new StringBuilder("WITH fused AS (").append(fusedSql).append(")");
    for (int k = 0; k < joins.size(); k++) sb.append(",\n").append(lookupCte(m, joins.get(k), k, sources));
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
        .append(JoinKeys.compared("f." + quoteIdent(joins.get(k).from()), fusedType(m, sources, joins.get(k).from()),
            sources.typeOf(joins.get(k).table(), joins.get(k).to())))
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
  private String lookupCte(EntityManifest m, EntityManifest.Join j, int k, Sources sources) {
    String key = lookupToKey(m, sources, j);
    String ord = j.map().keySet().stream()
        .map(phys -> quoteIdent(phys) + "::text COLLATE \"C\" NULLS LAST")
        .collect(Collectors.joining(", "));
    StringBuilder sb = new StringBuilder("lk").append(k).append(" AS MATERIALIZED (SELECT ")
        .append(key).append(" AS ").append(quoteIdent(LOOKUP_KEY))
        .append(", count(*) AS ").append(quoteIdent(LOOKUP_COUNT));
    j.map().forEach((phys, canon) -> sb.append(", (array_agg(")
        .append(typed(quoteIdent(phys), sources.typeOf(j.table(), phys), lookupType(sources, j, phys)))
        .append(" ORDER BY ").append(ord).append("))[1] AS ").append(quoteIdent(canon)));
    String ambiguity = j.map().entrySet().stream()
        .map(e -> quoteLiteral(e.getValue()) + ", jsonb_agg(jsonb_build_object('src', "
            + quoteLiteral(j.table()) + "::text, 'value', " + quoteIdent(e.getKey())
            + "::text) ORDER BY " + ord + ")")
        .collect(Collectors.joining(", "));
    return sb.append(", jsonb_build_object(").append(ambiguity).append(") AS ")
        .append(quoteIdent(LOOKUP_AMBIGUITY)).append(" FROM ")
        .append(current(j.table(), List.of(key), sources.history(), null)).append(" WHERE ").append(quoteIdent(j.to()))
        .append(" IS NOT NULL GROUP BY 1)").toString();
  }

  /** A lookup's key, compared as the fused {@code from} it meets ({@link JoinKeys#compared}, #1329). */
  public static String lookupToKey(EntityManifest m, Sources sources, EntityManifest.Join j) {
    return JoinKeys.compared(quoteIdent(j.to()), sources.typeOf(j.table(), j.to()), fusedType(m, sources, j.from()));
  }

  /**
   * One source's {@code from} key for a join, as the view compares it — the source's value aligned to the fused type,
   * then {@link JoinKeys#compared} — for a reader that scans that source table on its own (the search marks).
   * {@code qualifier} prefixes the source's physical {@code from} column as the reader aliases it ({@code ""} or
   * {@code "s."}).
   */
  public static String lookupFromKey(EntityManifest m, Sources sources, EntityManifest.Join j, String sourceTable,
                                     String physicalColumn, String qualifier) {
    String fused = fusedType(m, sources, j.from());
    return JoinKeys.compared(aligned(qualifier + quoteIdent(physicalColumn), sources.typeOf(sourceTable, physicalColumn),
        fused), fused, sources.typeOf(j.table(), j.to()));
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
   * {@link Fusion#representative} and {@link Fusion#ledger}, the view's own. A filter on a {@code match:}
   * column also narrows {@code h}, as Postgres does through the view. {@code k} reads only the keys
   * {@code h} found: its cost follows the probed value's frequency, not the entity's size.
   *
   * <p>{@code sources} is the view's own ({@link #sources}): the branches read only a history
   * table's current rows, as the view does. {@code h} and {@code k} read every version, which only
   * widens a necessary condition — a record they keep in vain is fused and fails the probe.
   *
   * <p>Empty — the caller reads the view — for a {@code resolution:} entity (no match key to group
   * by), when a column involved comes from a {@code joins:} lookup (whose values the sources do not
   * hold), or when no source maps the probed column.
   */
  public Optional<ProbeRelation> probeRelation(EntityManifest m, Sources sources, String probed,
                                               List<String> filtered) {
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
    String probedFused = fusedType(m, sources, probed);
    for (int i = 0; i < m.sources().size(); i++) {
      EntityManifest.Source s = m.sources().get(i);
      StringBuilder branch = new StringBuilder();
      appendSourceBranch(branch, m, s, i, canon, sources);   // also the guard: every match column is mapped
      Map<String, String> canonToSrc = validatedCanonToSrc(m, s);
      List<String> keyCols = matchKeyExpressions(m, sources, s.table()).stream().map(k -> k + "::text").toList();
      String tuple = "(" + String.join(", ", keyCols) + ")";
      branches.add(branch.append(" AND ").append(tuple).append(" IN (SELECT ").append(keyAliases).append(" FROM k)").toString());

      String physProbed = canonToSrc.get(probed);
      if (physProbed == null) continue;   // this source holds no value for the column: nothing for h or k
      String table = SCHEMA + "." + quoteIdent(s.table());
      // The probed value as the view fuses it (#1325): a date read as its timestamp when the column mixes them.
      String probedText = probedFused == null ? quoteIdent(physProbed) + "::text"
          : "(" + typed(quoteIdent(physProbed), sources.typeOf(s.table(), physProbed), probedFused) + ")::text";
      String keySelect = IntStream.range(0, keyCols.size())
          .mapToObj(j -> keyCols.get(j) + " AS " + quoteIdent("_k" + j)).collect(Collectors.joining(", "));
      StringBuilder holder = new StringBuilder("SELECT ").append(keySelect).append(" FROM ").append(table)
          .append(" WHERE ").append(anyOf(quoteIdent(physProbed), sources.typeOf(s.table(), physProbed), probedFused));
      params.add(probed);
      for (String kf : keyFilters) {
        String physKey = canonToSrc.get(kf);
        holder.append(" AND ").append(anyOf(quoteIdent(physKey), sources.typeOf(s.table(), physKey), fusedType(m, sources, kf)));
        params.add(kf);
      }
      holders.add(holder.toString());
      readers.add("SELECT " + keySelect + ", " + probedText + " AS v FROM " + table
          + " WHERE " + tuple + " IN (SELECT " + keyAliases + " FROM h)");
    }
    if (holders.isEmpty()) return Optional.empty();

    StringBuilder sb = new StringBuilder("(WITH h AS (").append(String.join("\nUNION\n", holders)).append("),\n")
        .append("k AS (SELECT ").append(keyAliases).append(" FROM (").append(String.join("\nUNION ALL\n", readers))
        .append(") x GROUP BY ").append(keyAliases).append(" HAVING min(v) <> max(v)),\n")
        .append("unified AS (").append(String.join("\nUNION ALL\n", branches)).append(")\nSELECT ");
    String groupBy = match.stream().map(c -> quoteIdent(c)).collect(Collectors.joining(", "));
    sb.append(groupBy);
    for (String c : carried) sb.append(", ").append(fusion(m, sources, c).representative(c)).append(" AS ").append(quoteIdent(c));
    sb.append(", jsonb_strip_nulls(jsonb_build_object(").append(quoteLiteral(probed)).append(", ")
      .append(fusion(m, sources, probed).ledger(probed)).append(")) AS ").append(quoteIdent("_conflicts"))
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
  private String unifiedCte(EntityManifest m, List<String> canon, Sources sources) {
    StringBuilder cte = new StringBuilder();
    for (int i = 0; i < m.sources().size(); i++) {
      if (i > 0) cte.append("\nUNION ALL\n");
      appendSourceBranch(cte, m, m.sources().get(i), i, canon, sources);
    }
    return cte.toString();
  }

  private void appendSourceBranch(StringBuilder cte, EntityManifest m, EntityManifest.Source s,
                                  int ordinal, List<String> canon, Sources sources) {
    Map<String, String> canonToSrc = validatedCanonToSrc(m, s);

    cte.append("SELECT ").append(ordinal).append("::int AS _ord, ")
       .append(quoteLiteral(s.table())).append("::text AS _src");
    appendCanonicalProjections(cte, m, s, canonToSrc, canon, "", sources);
    List<String> key = matchKey(s, canonToSrc, m.match());
    cte.append(" FROM ").append(current(s.table(), key, sources.history(), null))
       .append(key.stream().map(k -> k + " IS NOT NULL").collect(Collectors.joining(" AND ", " WHERE ", "")));
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
  private void appendCanonicalProjections(StringBuilder cte, EntityManifest m, EntityManifest.Source s,
                                          Map<String, String> canonToSrc, List<String> canon, String qualifier,
                                          Sources sources) {
    for (String c : canon) {
      cte.append(", ");
      String fused = fusedType(m, sources, c);
      String srcCol = canonToSrc.get(c);
      if (srcCol != null) cte.append(typed(qualifier + quoteIdent(srcCol), sources.typeOf(s.table(), srcCol), fused));
      else cte.append("NULL::").append(fused == null ? "text" : fused);
      cte.append(" AS ").append(quoteIdent(c));
    }
  }

  /**
   * The source's physical match columns, quoted, in match order — each branch keeps only rows where
   * they are all NOT NULL: a NULL match key would fold every source into one NULL-key group. The
   * parser guarantees each source maps every match column; failing fast here says so if it did not.
   */
  private static List<String> matchKey(EntityManifest.Source s, Map<String, String> canonToSrc, List<String> match) {
    List<String> key = new ArrayList<>();
    for (String mc : match) {
      String srcMatchCol = canonToSrc.get(mc);   // physical column name in THIS source
      if (srcMatchCol == null)
        throw new IllegalStateException("source '" + s.table() + "' does not map the match column '"
            + mc + "' — manifest must be validated before compilation");
      key.add(quoteIdent(srcMatchCol));
    }
    return key;
  }

  // -------------------------------------------------------------------------------------------
  // resolution: projection (#618, design doc §8).
  // -------------------------------------------------------------------------------------------

  /** One UNION ALL branch per source, each LEFT JOINing the crosswalk on its own identity column. */
  private String unifiedCteResolved(EntityManifest m, List<String> canon, Sources sources) {
    String entityType = m.name().toLowerCase(Locale.ROOT);
    StringBuilder cte = new StringBuilder();
    for (int i = 0; i < m.sources().size(); i++) {
      if (i > 0) cte.append("\nUNION ALL\n");
      appendResolvedSourceBranch(cte, m, m.sources().get(i), i, canon, entityType, sources);
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
                                          int ordinal, List<String> canon, String entityType,
                                          Sources sources) {
    Map<String, String> canonToSrc = validatedCanonToSrc(m, s);
    String key = quoteIdent(m.resolution().physicalIdentityColumn(s));
    String identity = "s." + key;

    cte.append("SELECT ").append(ordinal).append("::int AS _ord, ")
       .append(quoteLiteral(s.table())).append("::text AS _src, ")
       .append(identity).append("::text AS ").append(quoteIdent("_source_id"));
    appendCanonicalProjections(cte, m, s, canonToSrc, canon, "s.", sources);
    cte.append(", ei.entity_id AS ").append(quoteIdent("_entity_id"))
       .append(", ei.state AS ").append(quoteIdent("_resolution"));
    cte.append(" FROM ").append(current(s.table(), List.of(key), sources.history(), "s"))
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
   * defect {@link #matchKey} already guards against for a NULL match key).
   */
  private String outerSelectResolved(List<String> canon, Map<String, Fusion> fusions) {
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
      sel.append(", ").append(fusions.get(c).representative(c)).append(" AS ").append(quoteIdent(c));
    }
    appendConflictsLedger(sel, canon, Set.of(), fusions);
    sel.append(" FROM unified GROUP BY coalesce(").append(quoteIdent("_entity_id")).append("::text, ")
       .append(SOURCE_HANDLE).append(")");
    return sel.toString();
  }

  /** Match keys as-is, one representative per other column, the conflicts ledger, then the GROUP BY. */
  private String outerSelect(List<String> canon, Set<String> matchSet, List<String> match, Map<String, Fusion> fusions) {
    StringBuilder sel = new StringBuilder("SELECT ");
    boolean first = true;
    for (String c : canon) {
      if (!first) sel.append(", ");
      first = false;
      if (matchSet.contains(c)) {
        sel.append(quoteIdent(c));
      } else {
        sel.append(fusions.get(c).representative(c)).append(" AS ").append(quoteIdent(c));
      }
    }
    sel.append(", ").append(canonicalKeyExpression(match));
    appendConflictsLedger(sel, canon, matchSet, fusions);
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
   * Each column is cast to {@code ::text} at the point of use (Copilot review, #671): a match column
   * the sources hold as a date or a timestamp is typed in the {@code unified} CTE (#1325), and its key
   * is that aligned value's text — the text the search marks write from each source through
   * {@link #matchKeyExpressions}, both in UTC.
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
  private void appendConflictsLedger(StringBuilder sel, List<String> canon, Set<String> matchSet, Map<String, Fusion> fusions) {
    sel.append(", jsonb_strip_nulls(jsonb_build_object(");
    boolean firstConf = true;
    for (String c : canon) {
      if (matchSet.contains(c)) continue;
      if (!firstConf) sel.append(", ");
      firstConf = false;
      sel.append(quoteLiteral(c)).append(", ").append(fusions.get(c).ledger(c));
    }
    sel.append(")) AS ").append(quoteIdent("_conflicts"));
  }

  /**
   * How one canonical column is fused: its {@code type} ({@link #fusedType}, null for text) and, for a column whose
   * sources hold days beside times (#1327), the ordinals of the sources that hold times; null otherwise. A source that
   * only knows the day cannot contradict an hour: such a column disagrees when its times differ among themselves, or
   * when its values do not all fall on one UTC day, and its value is a time before a day. The source's TYPE decides,
   * never a value at midnight: a time at 00:00 is a time.
   */
  record Fusion(String type, List<Integer> timeSources) {

    /**
     * The one fusion rule for a non-match column's value: the first non-null across the sources, in source order —
     * a time before a day when they mix, so a day never hides the hour beside it. The view and the disputed-rows
     * probe ({@link #probeRelation}) both render it here.
     */
    String representative(String c) {
      String col = quoteIdent(c);
      String timesFirst = timeSources == null ? "" : "_ord NOT IN (" + ordinals() + "), ";
      return "(array_agg(" + col + " ORDER BY " + timesFirst + "_ord, " + col + ") FILTER (WHERE " + col
          + " IS NOT NULL))[1]";
    }

    /**
     * The ledger of a non-match column: every source's value, only when the sources DISAGREE (same sharing as
     * above). A typed column (#1325) disagrees by value — the same instant from a date and a timestamp is one
     * value — and its ledger still says each value as text, the form {@code DisputedRows} compares. Beside a day,
     * a time disagrees only on another day (#1327).
     */
    String ledger(String c) {
      String col = quoteIdent(c);
      String value = type != null ? col + "::text" : col;
      String disagree = timeSources == null ? "count(DISTINCT " + col + ") > 1"
          : "count(DISTINCT " + col + ") FILTER (WHERE _ord IN (" + ordinals() + ")) > 1"
              + " OR count(DISTINCT (" + col + " AT TIME ZONE 'UTC')::date) > 1";
      return "CASE WHEN " + disagree + " THEN jsonb_agg(jsonb_build_object('src', _src, 'value', "
          + value + ") ORDER BY _ord, " + col + ") FILTER (WHERE " + col + " IS NOT NULL) END";
    }

    private String ordinals() {
      return timeSources.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
  }

  static Fusion fusion(EntityManifest m, Sources sources, String canon) {
    String type = fusedType(m, sources, canon);
    if (!"timestamptz".equals(type)) return new Fusion(type, null);
    List<Integer> times = new ArrayList<>();
    boolean anyDay = false;
    for (int i = 0; i < m.sources().size(); i++) {
      EntityManifest.Source s = m.sources().get(i);
      String phys = validatedCanonToSrc(m, s).get(canon);
      if (phys == null) continue;
      if ("date".equals(sources.typeOf(s.table(), phys))) anyDay = true;
      else times.add(i);
    }
    return new Fusion(type, anyDay ? List.copyOf(times) : null);
  }
}
