package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.OutputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs under {@link RlsEnforcedProfile} (not plain {@code @QuarkusTest}): under plain
 * {@code %test}, {@code application.properties} pins
 * {@code quarkus.datasource."readonly".jdbc.transactions=DISABLED}, so the readonly connection
 * runs autoCommit and pgjdbc silently ignores {@code Statement.setFetchSize(...)} on it — the
 * bounded-memory claim below would be untestable. {@code RlsEnforcedProfile} re-enables JTA
 * enlistment for this datasource, so {@code setFetchSize} is genuinely honored.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntitySnapshotStreamerStreamingIT {

    private static final String ENTITY = """
        kind: Entity
        name: SnapshotStreamerEntity677
        match: [code]
        scopes: [sales]
        sources:
          - table: t_snapshotstreamer677_erp
            map:
              cust_id: code
              cust_name: name
        """;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject EntitySnapshotStreamer streamer;

    @BeforeEach
    void applyEntityAndSeedRows() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_snapshotstreamer677_erp",
            "v_snapshotstreamerentity677", "cust_id", "cust_name");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            // cust_id chosen so text order ("1" < "10" < "2") differs from numeric order,
            // proving text comparison (not a numeric cast) runs. cust_name is mixed-case
            // ('bob' lowercase, 'Cid'/'Ann' capitalized) so COLLATE "C" and the DevServices
            // image's default locale collation (en_US.utf8, confirmed empirically — see
            // task-4-report.md) produce DIFFERENT orders for it: "C" -> Ann, Cid, bob (every
            // uppercase letter sorts before every lowercase letter); en_US.utf8 -> Ann, bob,
            // Cid. ordersByCCollationNotDefaultLocaleCollation below pins the "C" order.
            st.execute("INSERT INTO connector.t_snapshotstreamer677_erp (tenant_id, cust_id, cust_name) "
                + "VALUES (1, '2', 'bob'), (1, '10', 'Cid'), (1, '1', 'Ann')");
        }
    }

    private String downloadAsSingleTenant(List<String> columns, List<String> orderColumns)
            throws Exception {
        EntityManifest entity = registry.findReloadingIfMissing("SnapshotStreamerEntity677");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, 1L);
                streamer.stream(c, entity, columns, orderColumns, ExportManifest.Format.DEFAULT, out);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void ordersByTextCollationNotNumericMagnitude() throws Exception {
        String csv = downloadAsSingleTenant(List.of("code", "name"), List.of("code"));

        String[] lines = csv.split("\r\n");
        assertEquals("\"code\",\"name\"", lines[0]);
        assertEquals("\"1\",\"Ann\"", lines[1]);
        assertEquals("\"10\",\"Cid\"", lines[2]); // text order: "1" < "10" < "2"
        assertEquals("\"2\",\"bob\"", lines[3]);
    }

    /**
     * Distinguishes {@code COLLATE "C"} from this DevServices Postgres image's default locale
     * collation ({@code en_US.utf8}, confirmed empirically against a throwaway
     * {@code pgvector/pgvector:pg16} container — see task-4-report.md's fix section) — a test
     * using only same-case values (as {@link #ordersByTextCollationNotNumericMagnitude} does for
     * {@code code}) would pass unchanged even if {@code buildSql}'s explicit {@code COLLATE "C"}
     * were silently dropped, since digit-only strings sort identically under both collations.
     * Ordering by {@code name} here — seeded as {@code bob}/{@code Cid}/{@code Ann} — does NOT:
     * under {@code "C"} every uppercase letter sorts before every lowercase one, giving
     * {@code Ann, Cid, bob}; under {@code en_US.utf8} it is {@code Ann, bob, Cid} instead.
     */
    @Test
    void ordersByCCollationNotDefaultLocaleCollation() throws Exception {
        String csv = downloadAsSingleTenant(List.of("code", "name"), List.of("name"));

        String[] lines = csv.split("\r\n");
        assertEquals("\"code\",\"name\"", lines[0]);
        assertEquals("\"1\",\"Ann\"", lines[1]);
        assertEquals("\"10\",\"Cid\"", lines[2]);
        assertEquals("\"2\",\"bob\"", lines[3]); // "C": 'A' < 'C' < 'b' — would be Ann,bob,Cid under en_US.utf8
    }

    @Test
    void projectionExcludesInternalColumns() throws Exception {
        EntityManifest entity = registry.findReloadingIfMissing("SnapshotStreamerEntity677");
        assertTrue(entity.canonicalColumns().stream().noneMatch(
            c -> c.equals("_conflicts") || c.equals("_entity_id") || c.equals("_resolution")));
    }

    /**
     * Bounded memory: seeds 700 extra rows (well past {@code OutputStreamWriter}'s ~8KB internal
     * encoder buffer, so a regression that buffered the whole result set before writing would
     * clearly overflow a single write) and asserts both that no single write exceeds a threshold
     * well under that buffer, AND that the sink actually received multiple writes — the write-count
     * check is what actually proves progressive delivery: a fixture too small to exceed the
     * threshold would let a fully-buffered implementation pass on the size check alone.
     *
     * <p>This proves the CSV-writing path never accumulates the whole response before flushing —
     * it does NOT prove {@code PreparedStatement.setFetchSize(FETCH_SIZE)} is what bounds JDBC's
     * own client-side memory. Rows reach this sink one at a time via {@code ResultSetRowIterator}
     * regardless of whether pgJDBC fetched them through a server-side cursor or materialized the
     * entire {@code ResultSet} in one round trip, so a dropped {@code setFetchSize} call would not
     * move this test's writes at all. {@link EntitySnapshotStreamerFetchSizeTest} pins that
     * contract directly, against a mocked {@code PreparedStatement}.
     */
    @Test
    void streamsProgressivelyNotOneFinalBufferedWrite() throws Exception {
        EntityManifest entity = registry.findReloadingIfMissing("SnapshotStreamerEntity677");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector.t_snapshotstreamer677_erp (tenant_id, cust_id, cust_name) "
                + "SELECT 1, 'row' || g, 'Name' || g FROM generate_series(1, 700) AS g");
        }

        AtomicInteger writeCount = new AtomicInteger();
        OutputStream trackingRejectingSink = new OutputStream() {
            @Override public void write(int b) { }
            @Override public void write(byte[] b, int off, int len) {
                writeCount.incrementAndGet();
                if (len > 4000) throw new IllegalStateException("single write too large: " + len + " bytes");
            }
        };
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, 1L);
                streamer.stream(c, entity, List.of("code", "name"), List.of("code"),
                    ExportManifest.Format.DEFAULT, trackingRejectingSink);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(writeCount.get() >= 2,
            "expected multiple progressive writes, got " + writeCount.get());
    }
}
