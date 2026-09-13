package io.lumnik.hub.tenant;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * On the {@code admin} datasource, nothing scopes a read but the SQL itself.
 *
 * <p>{@code lumnik_admin} is BYPASSRLS. That is what lets it migrate, create the dynamic tables and
 * read across tenants where a superadmin must — and it means every statement it runs has exactly
 * one line of tenant isolation: the {@code tenant_id} predicate the developer wrote, or did not.
 * No policy, no session filter, no interceptor stands behind it. An audit calls this a gap in
 * complete mediation and looks at it first: the {@code Jdbc} calls and the raw
 * {@code adminDs.getConnection()} sites, all correct today, held by discipline alone.
 *
 * <p>This test is the discipline made mechanical. It reads PRODUCTION SOURCE: every file that
 * injects {@code @DataSource("admin")}, and every SQL string in it. Adjacent string literals
 * joined by {@code +} are recombined into one statement first — a statement built by
 * concatenation must be judged whole, or a fragment like
 * {@code "SELECT card FROM connector.schema_card "} reads as unscoped when its next line is
 * {@code "WHERE tenant_id = ?"}. A statement that names a tenanted table must also name
 * {@code tenant_id}.
 *
 * <p><b>What it does NOT see:</b> SQL built at run time, and the dynamic connector tables
 * ({@code connector.t_*}). Those tables get RLS from Java at creation ({@code RlsDdl}), never from
 * a migration, so they are absent from the tenanted set below; and a statement like
 * {@code "SELECT * FROM " + fqn + " WHERE ..."} names its table in a variable this text scan
 * cannot resolve. The rule for that population belongs in the builder that emits the SQL, not
 * here — {@code DataRowResource} carries the {@code tenant_id} predicate itself, for exactly the
 * reason this test would otherwise have to guess. This guard covers the STATIC-SQL population; it
 * says so rather than letting a reader assume the dynamic tables are covered too.
 *
 * <p>Which tables are tenanted is read from the migrations — every {@code ENABLE ROW LEVEL
 * SECURITY} across the hub, identity and rag histories — not from a list kept here. A list kept
 * here is what let V21 claim "every policy" while missing two schemas. The open edition ships
 * without rag and this guard runs there too, so a closed module may be absent from the tree
 * ({@link #CLOSED_MODULES}); an absent open module still fails.
 *
 * <p>Deliberately cross-tenant statements are named in {@link #DELIBERATE} with their reason. Each
 * is a system reader with no tenant on its thread — the four background sweeps — or a documented
 * cross-tenant admin surface. The list fails if it names a statement that no longer exists, so a
 * reason cannot outlive the code it excused.
 */
class AdminDatasourceTenantGuardTest {

  /**
   * {@code File:verb:tables} → why THAT statement may name a tenanted table without a
   * {@code tenant_id = ?} predicate. The verb is in the key so a SELECT and an UPDATE on the same
   * table are separate excuses — one reason cannot silently cover the other. Stable across edits
   * that move the SQL within the file.
   */
  private static final Map<String, String> DELIBERATE = Map.ofEntries(
      // Four background @Scheduled sweeps: no HTTP caller, no tenant on the thread, cross-tenant
      // by design — the admin datasource is what lets them span tenants.
      e("AuditDispatcher:select:core.audit_outbox",  "drains every tenant's audit outbox"),
      e("AuditDispatcher:update:core.audit_outbox",  "marks the row that same sweep just drained, by id"),
      e("AuditRetentionPurger:delete:core.audit_event", "operator-wide retention purge, all tenants"),
      e("ConnectorScheduler:select:connector.connector,connector.connector_run",
          "cross-tenant due-run scan; the run it starts binds its own tenant"),
      e("RunReaper:update:connector.connector,connector.connector_run",
          "startup recovery of stale Running rows, all tenants; the RETURNING correlates each "
          + "reaped run's connector NAME for the operator's warning, keyed by that run's own "
          + "connector_id — it reads one more table, not one more tenant"),
      // Cross-tenant admin surfaces / correlated reads the tenant_id-PREDICATE check surfaced.
      // (EventStreamResource dropped: its SSE query now scopes tenant_id = ?, so it is no longer a
      // deliberate cross-tenant reader — it passes the predicate check like any other statement.)
      e("ScimTokenRepository:select:identity.scim_token",
          "resolves a SCIM token BY its secret hash to ESTABLISH the tenant — the query that "
          + "discovers tenant_id cannot presuppose it"),
      e("DynamicTableRegistry:delete:connector.dynamic_table",
          "purge keyed by a tenant-unique connector UUID; the SELECT and INSERT here ARE scoped"),
      e("DynamicTableRegistry:select:connector.dynamic_table",
          "dependentViews enumerates a connector's tables (keyed by tenant-unique connector UUID, "
          + "like purge) to ask pg_depend which views block the drop — the same tenant-safe key"),
      e("WorkflowDormancySweep:select:core.workflow_manifest",
          "the dormancy sweep's cross-tenant scan for which tenants have a workflow to check"),
      e("TenantListResource:select:identity.app_user,identity.org",
          "the superadmin cross-tenant listing — its count sub-queries correlate o.tenant_id = "
          + "t.id per LISTED tenant, they do not scope to the caller (which is the whole point)"),
      e("InboxResource:select:core.event_outbox",
          "the ack-match sub-select correlates a.tenant_id = d.tenant_id; its outer query scopes "
          + "d.tenant_id = ?, so the join is the correlation, not a missing caller scope"));

  private static Map.Entry<String, String> e(String k, String v) { return Map.entry(k, v); }

  private static final List<SourceTree> MODULES = List.of(
      SourceTree.of("lumnik-hub", "io/lumnik/hub"),
      SourceTree.of("lumnik-identity", "io/lumnik/identity"));

  private static final Pattern ENABLE_RLS = Pattern.compile(
      "ALTER\\s+TABLE\\s+([a-z_]+\\.[a-z_]+)\\s+ENABLE\\s+ROW\\s+LEVEL\\s+SECURITY",
      Pattern.CASE_INSENSITIVE);
  /** One Java string literal: a text block or a quoted string. */
  private static final String LITERAL = "(?:\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\")";
  /** A run of such literals joined only by {@code +} — one logical statement built by concatenation. */
  private static final Pattern LITERAL_RUN = Pattern.compile(LITERAL + "(?:\\s*\\+\\s*" + LITERAL + ")*");
  private static final Pattern SQL_VERB = Pattern.compile("\\b(select|insert|update|delete)\\b");
  /** A tenant PREDICATE that scopes to the CALLER: {@code tenant_id = ?} / {@code = :param} /
   *  {@code = current_setting(...)}. Not a bare occurrence (a SELECT list, a RETURNING), and not a
   *  JOIN — {@code tenant_id = d.tenant_id} or {@code = t.id} correlates two tables, it does not
   *  bind the current tenant, so it must NOT count as scoping. */
  private static final Pattern TENANT_PREDICATE =
      Pattern.compile("tenant_id\\s*=\\s*(\\?|:\\w+|current_setting)");
  /** An INSERT stamps a tenant by listing the column: {@code INSERT INTO t (… tenant_id …)}. The
   *  column must be in the INSERT's OWN column list — the first parenthesised group after the
   *  table — not merely somewhere in the statement (a RETURNING clause, a sub-select). */
  private static final Pattern INSERT_STAMPS_TENANT =
      Pattern.compile("insert\\s+into\\s+\\S+\\s*\\(([^)]*)\\)");
  /** {@code tenant_id} as a whole column name inside an INSERT's column list. */
  private static final Pattern TENANT_COLUMN = Pattern.compile("(?<![\\w.])tenant_id(?![\\w])");

  @Test
  void every_admin_datasource_statement_naming_a_tenanted_table_names_the_tenant() throws IOException {
    Path root = reactorRoot();
    TreeSet<String> tenanted = tenantedTables(root);
    assertThat(tenanted).as("no RLS tables found in any migration — the guard is reading nothing")
        .hasSizeGreaterThan(20);

    // One matcher per tenanted table, compiled once. The non-identifier lookbehind/ahead keeps
    // connector.connector from matching inside connector.connector_run — one is the other's prefix.
    Map<String, Pattern> matcher = new TreeMap<>();
    for (String t : tenanted) matcher.put(t, Pattern.compile("(?<![\\w.])" + Pattern.quote(t) + "(?![\\w])"));

    Set<String> found = new TreeSet<>();
    Set<String> presentFiles = new TreeSet<>();
    for (SourceTree module : MODULES) {
      for (Path p : module.javaFiles()) {
        String file = p.getFileName().toString().replace(".java", "");
        presentFiles.add(file);
        String src = SourceTree.code(Files.readString(p));
        if (!src.contains("@DataSource(\"admin\")")) continue;

        Matcher run = LITERAL_RUN.matcher(src);
        while (run.find()) {
          String sql = run.group().replaceAll("[\"+]", " ").toLowerCase(Locale.ROOT);
          if (!SQL_VERB.matcher(sql).find()) continue;
          TreeSet<String> named = new TreeSet<>();
          matcher.forEach((t, pat) -> { if (pat.matcher(sql).find()) named.add(t); });
          if (named.isEmpty()) continue;
          // INSERT isolates by STAMPING tenant_id as a column; the other verbs isolate by a
          // predicate. contains("tenant_id") accepted "SELECT tenant_id, name FROM t WHERE x" as
          // scoped when it is not — so require the predicate unless the statement is an INSERT.
          Matcher v = SQL_VERB.matcher(sql);
          String verb = v.find() ? v.group(1) : "?";
          boolean insert = verb.equals("insert");
          boolean scoped;
          if (insert) {
            // tenant_id must be in the INSERT's own column list, not just anywhere in the text.
            Matcher cols = INSERT_STAMPS_TENANT.matcher(sql);
            scoped = cols.find() && TENANT_COLUMN.matcher(cols.group(1)).find();
          } else {
            scoped = TENANT_PREDICATE.matcher(sql).find();
          }
          if (scoped) continue;
          found.add(file + ":" + verb + ":" + String.join(",", named));
        }
      }
    }

    List<String> unexpected = found.stream().filter(k -> !DELIBERATE.containsKey(k)).toList();
    assertThat(unexpected)
        .as("a statement on the BYPASSRLS datasource that names a tenanted table and not tenant_id "
            + "is a read nothing scopes — add the predicate, or move the read to the default "
            + "datasource where RLS holds it; if it is deliberately cross-tenant, name it in "
            + "DELIBERATE with the reason")
        .isEmpty();

    // The open edition prunes closed files with their modules (SCIM with identity's scim package);
    // an exception naming a file that is not in THIS tree is pruned, not stale. Only that tree
    // gets the tolerance, and only for a file that is truly absent — a present file that no
    // longer matches is stale on either tree. In the monorepo every named file must exist.
    boolean openEdition = CLOSED_MODULES.stream().anyMatch(m -> !Files.isDirectory(root.resolve(m)));
    List<String> stale = DELIBERATE.keySet().stream()
        .filter(k -> !found.contains(k))
        .filter(k -> !(openEdition && !presentFiles.contains(k.substring(0, k.indexOf(':')))))
        .toList();
    assertThat(stale)
        .as("an exception that matches nothing: the code moved and the reason did not follow it")
        .isEmpty();
  }

  /** The modules whose migrations declare tenanted tables. */
  private static final List<String> MIGRATION_MODULES = List.of("lumnik-hub", "lumnik-identity", "lumnik-rag");
  /**
   * The closed modules, which the open edition prunes ({@code scripts/extract-open-edition.sh}).
   * This guard runs there too — on the tree the publish ritual proves — and an absent closed
   * module contributes nothing: the code that could name its tables is pruned with it. An absent
   * OPEN module is still the guard reading nothing, and fails.
   */
  private static final Set<String> CLOSED_MODULES = Set.of("lumnik-rag");

  @Test
  void a_closed_module_absent_from_the_tree_contributes_nothing(@TempDir Path root) throws IOException {
    migration(root, "lumnik-hub", "ALTER TABLE core.a ENABLE ROW LEVEL SECURITY;");
    migration(root, "lumnik-identity", "ALTER TABLE identity.b ENABLE ROW LEVEL SECURITY;");

    assertThat(tenantedTables(root)).containsExactly("core.a", "identity.b");
  }

  @Test
  void an_open_module_absent_from_the_tree_fails(@TempDir Path root) throws IOException {
    migration(root, "lumnik-hub", "ALTER TABLE core.a ENABLE ROW LEVEL SECURITY;");

    assertThatThrownBy(() -> tenantedTables(root))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("lumnik-identity");
  }

  private static void migration(Path root, String module, String sql) throws IOException {
    Path dir = Files.createDirectories(root.resolve(module).resolve("src/main/resources/db/migration"));
    Files.writeString(dir.resolve("V1__t.sql"), sql);
  }

  /** Every table any migration under {@code root} put under RLS, qualified, lower-case. */
  private static TreeSet<String> tenantedTables(Path root) throws IOException {
    TreeSet<String> out = new TreeSet<>();
    for (String module : MIGRATION_MODULES) {
      Path migrations = root.resolve(module).resolve("src/main/resources/db/migration");
      if (!Files.isDirectory(migrations)) {
        assertThat(CLOSED_MODULES).as("%s has no migration directory", module).contains(module);
        continue;
      }
      try (Stream<Path> files = Files.walk(migrations)) {
        for (Path sql : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
          Matcher m = ENABLE_RLS.matcher(Files.readString(sql));
          while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
      }
    }
    return out;
  }

  /** The reactor root, found the way SourceTree finds sources: from cwd, then each ancestor. */
  private static Path reactorRoot() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("lumnik-hub/src/main"))) return dir;
    }
    throw new IllegalStateException("cannot locate the reactor root from " + Path.of("").toAbsolutePath());
  }
}
