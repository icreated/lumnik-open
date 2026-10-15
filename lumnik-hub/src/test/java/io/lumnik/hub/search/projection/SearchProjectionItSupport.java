package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.restassured.specification.RequestSpecification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static io.restassured.RestAssured.given;

/**
 * Shared fixtures for this package's own {@code @QuarkusTest} ITs — the {@code
 * io.lumnik.hub.search.mark.SearchProjectionMarkItSupport} idiom, but for {@code search.projection}:
 * each of these ITs previously hand-copied {@code unique}/{@code yaml}/{@code searchIndexYaml}/{@code
 * matchEntityYaml}/{@code workerWith} verbatim (and, where they also exercise purge-drain, {@code
 * insertPurgeRow}/{@code purgeRowCount}/{@code deleteSearchIndexRow} too).
 *
 * <p>Deliberately NOT extracted here: {@code createMatchTable}. Its DDL and its {@code
 * lumnik_readonly} grant strategy genuinely differ per file — most ITs use a guarded {@code DO $$
 * ... EXCEPTION WHEN OTHERS THEN NULL END $$} (plain {@code @QuarkusTest}, where the role may not
 * even exist), while {@code SearchProjectionTenantIsolationIT} runs under {@code RlsEnforcedProfile}
 * and needs the grant to be unconditional and load-bearing — collapsing the two would either break
 * that IT's own contract or silently swallow a real grant failure elsewhere. Package-private, like
 * {@code SearchProjectionMarkItSupport}: this package's own ITs reuse it, not copy it.
 */
final class SearchProjectionItSupport {

    private SearchProjectionItSupport() {}

    static String unique(String prefix) {
        return prefix + System.nanoTime();
    }

    static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    /** A {@link SearchProjectionWorker} wired to a fake, single-answer {@link ProjectionSinkGate}
     *  plus the real admin/readonly datasources and entity registry — every rebuild/catch-up IT in
     *  this package needs exactly this shape, only the sink itself varies per test. Not for
     *  {@code SearchProjectionPurgeDrainIT}: purgeDrain needs only {@code adminDs}, so wiring
     *  readonlyDs/registry/rebuildScanTimeoutSeconds there would be dead assignment, not reuse.
     *  {@code metrics} is a real {@link SearchProjectionMetrics} wrapping a bare {@link
     *  SimpleMeterRegistry} — none of this package's rebuild/catch-up ITs assert on it, but {@code
     *  runBuild}/{@code runCatchUp} call it unconditionally on every terminal path (Task 4), so a
     *  worker built outside CDI (never running {@code SearchProjectionMetrics}' own {@code @Inject
     *  MeterRegistry}) NPEs without this. */
    static SearchProjectionWorker workerWith(ProjectionSink sink, SearchProjectionMarkStore markStore,
            SearchProjectionStateStore stateStore, AgroalDataSource adminDs, AgroalDataSource readonlyDs,
            EntityRegistry registry) {
        ProjectionSinkGate gate = new ProjectionSinkGate() {
            @Override public Optional<ProjectionSink> resolve() { return Optional.of(sink); }
        };
        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.adminDs = adminDs;
        worker.readonlyDs = readonlyDs;
        worker.registry = registry;
        worker.rebuildScanTimeoutSeconds = 30;
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        // #703's on-demand concurrency gate: a worker built outside CDI never runs @PostConstruct, so
        // triggerRebuild's own rebuildPermits.tryAcquire() would NPE without this — mirrors metrics above.
        worker.maxConcurrentRebuilds = 3;
        worker.rebuildPermits = new Semaphore(worker.maxConcurrentRebuilds);
        return worker;
    }

    /** A {@code match:} Entity manifest over {@code table}'s {@code src_code}/{@code src_name}
     *  columns, plus one {@code src_<col>: <col>} mapping line per name in {@code extraColumns} —
     *  {@code SearchProjectionFullRebuildIT} and five siblings pass {@code "city"} for their own
     *  {@code src_city}-bearing tables; the rest pass none. */
    /** Replaces the entity's view with one shaped like an older hub made it: the declared columns, and
     *  {@code keyColumn} only when given (#842). */
    static void replaceViewAsAnOlderHubCompiledIt(AgroalDataSource adminDs, String entityName, String table,
                                                  String keyColumn) throws Exception {
        String view = EntityViewCompiler.viewName(entityName);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS " + view);
            // security_invoker, as EntityViewCompiler always compiles it: the querying role's own grants and the
            // table's RLS apply, so the scan meets the same privileges it meets in production.
            st.execute("CREATE VIEW " + view + " WITH (security_invoker = true) AS SELECT tenant_id, src_code AS code, src_name AS name"
                + (keyColumn == null ? "" : ", src_code AS " + keyColumn)
                + " FROM connector." + table);
            st.execute("GRANT SELECT ON " + view + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON " + view + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }

    static String matchEntityYaml(String name, String table, String... extraColumns) {
        StringBuilder map = new StringBuilder("      src_code: code\n      src_name: name\n");
        for (String col : extraColumns) {
            map.append("      src_").append(col).append(": ").append(col).append('\n');
        }
        return "kind: Entity\n"
            + "name: " + name + "\n"
            + "match: [code]\n"
            + "scopes: [sales]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + map;
    }

    static String searchIndexYaml(String name, String entity, String... fields) {
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            spec.append("    - name: ").append(fields[i]).append('\n');
            if (i == 0) spec.append("      match: fuzzy\n");
        }
        return "apiVersion: search.lumnik.io/v1\n"
            + "kind: SearchIndex\n"
            + "metadata:\n"
            + "  name: " + name + "\n"
            + "spec:\n"
            + "  entity: " + entity + "\n"
            + "  fields:\n"
            + spec;
    }

    /** {@code core.search_index_purge}'s own direct-SQL fixture (duplicated, per {@code
     *  SearchProjectionTwoWorkersIT}'s own comment, from {@code SearchProjectionStaleDiscoveryIT}/
     *  {@code SearchProjectionPurgeDrainIT}): purge-drain's own contract never consults {@code
     *  core.search_index}, so what matters is the row shapes it reads/writes, not how they came to
     *  exist. */
    static void insertPurgeRow(AgroalDataSource adminDs, long tenant, String name, String entity) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_index_purge (tenant_id, name, entity) VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, entity);
            ps.executeUpdate();
        }
    }

    static long purgeRowCount(AgroalDataSource adminDs, long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_index_purge WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** {@code SearchIndexStore.deleteWithPurge}'s own shape, reproduced directly by raw SQL — the
     *  HTTP delete endpoint is out of scope here; what matters is the row shape the worker's own
     *  re-read sees afterward. */
    static void deleteSearchIndexRow(AgroalDataSource adminDs, long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_index WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }
}
