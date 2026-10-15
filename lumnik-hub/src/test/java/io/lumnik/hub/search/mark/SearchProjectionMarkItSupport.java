package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.restassured.specification.RequestSpecification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.fail;

/**
 * Shared fixtures for the #701 {@code core.search_projection_mark}/{@code
 * core.search_projection_mark_watermark} ITs — the {@link io.lumnik.hub.search.SearchIndexItSupport}
 * idiom, but for this package: an Entity manifest with a {@code _ingested_at}-bearing physical source
 * table (not {@code EntitySourceTableFixture}, which has no such column — the watermark predicate
 * this producer scans is exactly {@code _ingested_at}, the same system column {@code
 * DynamicTableWriter} stamps on every real ingested row), a SearchIndex manifest, and direct reads of
 * {@code core.search_projection_mark}/{@code core.search_projection_mark_watermark} on the admin pool.
 *
 * <p>Package-private, like {@code SearchIndexItSupport}: this package's own ITs reuse it, not
 * copy it — {@code SearchProjectionIngestionIT} and {@code SearchProjectionReconcilerIT} previously
 * each hand-copied every method here (their own javadocs said as much: "Mirrors
 * SearchProjectionIngestionIT.entityManifest").
 */
public final class SearchProjectionMarkItSupport {

    private SearchProjectionMarkItSupport() {}

    public static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** The Entity manifest alone, over a source table that already exists — mirrors {@code
     *  SearchIndexItSupport.entityManifest} (package-private there, in {@code hub.search}, so
     *  duplicated rather than imported across packages). */
    static String entityManifest(String name, String table, List<String> scopes, String... canonicalColumns) {
        StringBuilder map = new StringBuilder();
        for (String col : canonicalColumns) map.append("      src_").append(col).append(": ").append(col).append('\n');
        return "kind: Entity\n"
            + "name: " + name + "\n"
            + "match: [" + canonicalColumns[0] + "]\n"
            + "scopes: [" + String.join(", ", scopes) + "]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + map;
    }

    /** A SearchIndex over {@code entity}: the first field fuzzy-searchable, the others return-only.
     *  Mirrors {@code SearchIndexItSupport.searchIndex}. */
    public static String searchIndex(String name, String entity, String... fields) {
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

    /** A source table shaped like a real {@code t_*} table: {@code tenant_id} + {@code _ingested_at}
     *  plus {@code src_code}/{@code src_name}, matching {@link #entityManifest}'s {@code "code",
     *  "name"} canonical columns. */
    static void createMatchSourceTable(AgroalDataSource adminDs, String table) throws Exception {
        createTable(adminDs, table, "src_code text, src_name text");
    }

    static void insertRow(AgroalDataSource adminDs, String table, long tenant, String code, String name)
            throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name) VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    /** A {@code resolution:} entity (`CUSTOMER_ID`/`NAME_COL`, one {@code exact:} rule on {@code
     *  name}), its physical source table created and its manifest applied — mirrors {@code
     *  SearchProjectionIngestionIT.resolutionModeEntityProducesNothingFromThisHook}'s inline fixture
     *  and {@code SearchProjectionResolutionIT}'s own {@code applyResolutionEntity}. */
    static void applyResolutionEntity(AgroalDataSource adminDs, long tenant, String entityName, String table)
            throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " ("
                + "id bigserial PRIMARY KEY, _run_id uuid, "
                + "tenant_id bigint NOT NULL, _ingested_at timestamptz NOT NULL DEFAULT now(), "
                + "\"CUSTOMER_ID\" text, \"NAME_COL\" text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
        }

        String manifest = "kind: Entity\n"
            + "name: " + entityName + "\n"
            + "scopes: [sales]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + "      CUSTOMER_ID: customer_id\n"
            + "      NAME_COL: name\n"
            + "resolution:\n"
            + "  identity:\n"
            + "    " + table + ": customer_id\n"
            + "  cascade:\n"
            + "    - id: name\n"
            + "      exact: { fields: [name] }\n";
        yaml(tenant).body(manifest).post("/api/entities/apply").then().statusCode(200);
    }

    /** A match entity over a fresh {@code t_*} source table, with a SearchIndex over it — the fixture the #730
     *  freshness ITs share. {@code label} keeps names unique per test. */
    record MatchFixture(String table, String index) {}

    static MatchFixture applyMatchEntityAndIndex(AgroalDataSource adminDs, long tenant, String label) throws Exception {
        String entity = "AsOf" + label + System.nanoTime();
        String table = "t_asof_" + label.toLowerCase() + "_" + System.nanoTime();
        String index = unique("asof-" + label.toLowerCase());
        createMatchSourceTable(adminDs, table);
        yaml(tenant).body(entityManifest(entity, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(searchIndex(index, entity, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        return new MatchFixture(table, index);
    }

    /** The database's own clock — the one that stamps {@code _ingested_at} and bounds freshness. */
    static Instant dbClock(AgroalDataSource adminDs) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT clock_timestamp()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    /** A table's stored watermark, {@code null} when it has no row. */
    static Instant watermark(AgroalDataSource adminDs, long tenant, String qualifiedTable) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT watermark FROM core.search_projection_mark_watermark WHERE tenant_id = ? AND table_name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, qualifiedTable);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toInstant() : null;
            }
        }
    }

    /** An Entity over {@code products} (pid, cid, pname) joining {@code lookup} (cid, cname) as {@code joinedColumn} (#847). */
    public static String joinEntityYaml(String entity, String products, String lookup, String joinedColumn) {
        return "kind: Entity\nname: " + entity + "\nmatch: [product_id]\nscopes: [lk]\nsources:\n"
            + "  - table: " + products + "\n    map: { pid: product_id, cid: category_id, pname: product_name }\n"
            + "joins:\n  - table: " + lookup + "\n    from: category_id\n    to: cid\n    map: { cname: " + joinedColumn + " }\n";
    }

    /** A {@code resolution:} Entity over {@code products} (pid, cid, pname) joining {@code lookup} (cid, cname) as {@code joinedColumn} (#850). */
    public static String resolutionJoinEntityYaml(String entity, String products, String lookup, String joinedColumn) {
        return resolutionJoinYaml(entity, lookup, joinedColumn, products);
    }

    /** A {@code resolution:} Entity over TWO sources (each pid, cid, pname) joining {@code lookup}: the shape where rows of
     *  several sources converge on one identity (#850). */
    public static String resolutionJoinTwoSourceEntityYaml(String entity, String s1, String s2, String lookup,
                                                           String joinedColumn) {
        return resolutionJoinYaml(entity, lookup, joinedColumn, s1, s2);
    }

    private static String resolutionJoinYaml(String entity, String lookup, String joinedColumn, String... sources) {
        StringBuilder sourceBlocks = new StringBuilder();
        StringBuilder identity = new StringBuilder();
        for (String source : sources) {
            sourceBlocks.append("  - table: ").append(source)
                .append("\n    map: { pid: product_id, cid: category_id, pname: product_name }\n");
            identity.append(identity.isEmpty() ? "" : ", ").append(source).append(": product_id");
        }
        return "kind: Entity\nname: " + entity + "\nscopes: [lk]\nsources:\n" + sourceBlocks
            + "resolution:\n  identity: { " + identity + " }\n  cascade:\n"
            + "    - id: by-name\n      exact: { fields: [product_name] }\n"
            + "joins:\n  - table: " + lookup + "\n    from: category_id\n    to: cid\n    map: { cname: " + joinedColumn + " }\n";
    }

    /** A decided crosswalk row: source row {@code sourceId} of {@code sourceTable} belongs to {@code entityId} (#850). */
    public static void insertIdentity(AgroalDataSource adminDs, long tenant, String entity, String sourceTable,
                                      String sourceId, UUID entityId) throws Exception {
        exec(adminDs, "INSERT INTO core.entity_identity (tenant_id, entity_type, source, source_id, entity_id, state) VALUES ("
            + tenant + ", '" + entity.toLowerCase(Locale.ROOT) + "', '" + sourceTable + "', '" + sourceId + "', '"
            + entityId + "', 'resolved')");
    }

    /** A crosswalk row that claims no entity ({@code state} is ambiguous / unresolved_no_rule / unresolved_provider). */
    public static void insertUndecidedIdentity(AgroalDataSource adminDs, long tenant, String entity, String sourceTable,
                                               String sourceId, String state) throws Exception {
        exec(adminDs, "INSERT INTO core.entity_identity (tenant_id, entity_type, source, source_id, entity_id, state) VALUES ("
            + tenant + ", '" + entity.toLowerCase(Locale.ROOT) + "', '" + sourceTable + "', '" + sourceId + "', NULL, '"
            + state + "')");
    }

    /** What {@code ConnectorRunCompleted} triggers, without an actual run — and without the observer itself, which
     *  is inert when no provider is installed (#896), as in this profile without {@code -Psearch}. */
    static void fireRun(SearchProjectionRunHook hook, long tenant) throws SQLException {
        hook.processTenant(tenant);
    }

    /** A table shaped like a real {@code t_*} table (#847): {@code tenant_id} + {@code _ingested_at} + {@code cols}. */
    public static void createTable(AgroalDataSource adminDs, String name, String cols) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS connector.\"v_" + name + "\"");
            st.execute("DROP TABLE IF EXISTS connector." + name);
            st.execute("CREATE TABLE connector." + name + " (tenant_id bigint NOT NULL, "
                + "_ingested_at timestamptz NOT NULL DEFAULT now(), " + cols + ")");
            st.execute("ALTER TABLE connector." + name + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + name + "_iso ON connector." + name
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + name + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON connector." + name + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }

    public static void exec(AgroalDataSource adminDs, String sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** The canonical keys of this index's KEY marks of one reason, sorted. */
    static List<String> markedKeys(AgroalDataSource adminDs, long tenant, String index, String reason)
            throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT canonical_key FROM core.search_projection_mark "
                 + "WHERE tenant_id = ? AND search_index = ? AND reason = ? ORDER BY canonical_key")) {
            ps.setLong(1, tenant);
            ps.setString(2, index);
            ps.setString(3, reason);
            List<String> keys = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
            return keys;
        }
    }

    static long markCount(AgroalDataSource adminDs, long tenant, String searchIndexName, String reason)
            throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND mark_kind = 'KEY' AND reason = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, searchIndexName);
            ps.setString(3, reason);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static long watermarkRowCount(AgroalDataSource adminDs, long tenant, String qualifiedTable) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark_watermark "
               + "WHERE tenant_id = ? AND table_name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, qualifiedTable);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * #748: a connector run (with its own connector row) in {@code status}. Its {@code resolution_fence_at}
     * is the column default (the database clock at INSERT); {@code nullFence} rewrites it to {@code NULL},
     * the state of every run that predates V60.
     */
    static UUID insertRun(AgroalDataSource adminDs, long tenant, String status, boolean nullFence) throws Exception {
        try (Connection c = adminDs.getConnection()) {
            UUID connector;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO connector.connector (tenant_id, name, type_id) VALUES (?, ?, 'hello-world') RETURNING id")) {
                ps.setLong(1, tenant);
                ps.setString(2, "run748-" + UUID.randomUUID());
                try (ResultSet rs = ps.executeQuery()) { rs.next(); connector = (UUID) rs.getObject(1); }
            }
            UUID run;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO connector.connector_run (tenant_id, connector_id, status, sync_mode, triggered_by) "
                  + "VALUES (?, ?, ?, 'Incremental', 'manual') RETURNING id")) {
                ps.setLong(1, tenant);
                ps.setObject(2, connector);
                ps.setString(3, status);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); run = (UUID) rs.getObject(1); }
            }
            if (nullFence) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE connector.connector_run SET resolution_fence_at = NULL WHERE id = ?")) {
                    ps.setObject(1, run);
                    ps.executeUpdate();
                }
            }
            return run;
        }
    }

    /** A run's {@code resolution_fence_at}, {@code null} when unknown. */
    static Instant runFence(AgroalDataSource adminDs, UUID run) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT resolution_fence_at FROM connector.connector_run WHERE id = ?")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        }
    }

    /**
     * Polls until {@code safeBound} is strictly after {@code t}. {@code safeBoundAdmin()} subtracts a 1 s
     * margin, so right after a fresh fence it is legitimately still behind it: any assertion that an
     * advance went PAST a fence must wait for the bound to clear it first.
     */
    static void awaitSafeBoundPast(Supplier<Instant> safeBound, Instant t) throws InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (safeBound.get().isAfter(t)) return;
            Thread.sleep(50);
        }
        fail("safeBound never passed " + t);
    }

    /** #748: flags a run as one that never executed (skipped for a held endpoint lock), as the engine does. */
    static void markRunExempt(AgroalDataSource adminDs, UUID run) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE connector.connector_run SET resolution_exempt = true WHERE id = ?")) {
            ps.setObject(1, run);
            ps.executeUpdate();
        }
    }
}
