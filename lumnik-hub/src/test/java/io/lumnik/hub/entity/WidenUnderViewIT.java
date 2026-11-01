package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1600 (design: docs/superpowers/specs/2026-10-27-widen-under-view-design.md). A whole-number column is {@code bigint}
 * (#497) and widens on its first fractional value; a view over the column made Postgres refuse the {@code ALTER … TYPE},
 * and the source stopped landing rows. Entity views are rebuilt around the widening from their stored manifests; any
 * other view refuses it by name, before any DDL.
 */
@QuarkusTest
class WidenUnderViewIT {

    @Inject DynamicTableWriter writer;
    @Inject EntityViewCompiler compiler;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @AfterEach
    void seams() {
        compiler.afterWalk(c -> {});
        compiler.afterDrops(c -> {});
    }

    private void writeChunk(String table, String hash, Map<String, Object> row) {
        writeChunk(1L, table, hash, row);
    }

    /** One chunk, one row, committed — the production shape the writer expects; rolled back when it fails. */
    private void writeChunk(long tenant, String table, String hash, Map<String, Object> row) {
        MappingTarget.Table spec = new MappingTarget.Table("connector", table, true, MappingTarget.OnConflict.SKIP,
            List.of());
        Map<String, Object> sys = Map.of(
            "_row_hash", hash, "_tenant", tenant, "_mapping", table,
            "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(row), List.of(sys), tenant);
                utx.commit();
            } catch (Exception e) {
                try {
                    if (utx.getStatus() != Status.STATUS_NO_TRANSACTION) utx.rollback();
                } catch (Exception ignored) {
                    // the failure that matters is the write's
                }
                throw e instanceof RuntimeException r ? r : new RuntimeException(e);
            }
        });
    }

    private void sql(String s) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    private String one(String query) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** {@code bigint}, {@code numeric}, {@code numeric(18,4)} — the type as Postgres formats it, null when absent. */
    private String type(String relation, String column) throws Exception {
        return one("SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
            + "WHERE a.attrelid = to_regclass('connector." + relation + "') AND a.attname = '" + column + "'");
    }

    private void applyEntity(String yaml) {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body(yaml).post("/api/entities/apply").then().statusCode(200);
    }

    private static String entity(String name, String table, String map) {
        return "kind: Entity\nname: " + name + "\nmatch: [no]\nscopes: [widen]\nsources:\n"
            + "  - table: " + table + "\n    map: { " + map + " }\n";
    }

    private String viewDef(String view) throws Exception {
        return one("SELECT pg_get_viewdef('connector." + view + "'::regclass, true)");
    }

    /** 1: the view comes back compiled from the widened source, exactly as an apply compiles it. */
    @Test
    void an_entity_over_the_column_is_rebuilt_as_an_apply_would_compile_it() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_entity_" + n, name = "WidenOrder" + n, view = "v_widenorder" + n;
        writeChunk(table, "w1", Map.of("no", "A1", "qty", 1000));
        applyEntity(entity(name, table, "no: no, qty: qty"));

        writeChunk(table, "w2", Map.of("no", "A2", "qty", 748.5d));

        assertThat(type(table, "qty")).isEqualTo("numeric");
        String rebuilt = viewDef(view);
        applyEntity(entity(name, table, "no: no, qty: qty"));
        assertThat(rebuilt).as("the rebuilt view is the one an apply compiles").isEqualTo(viewDef(view));
        assertThat(one("SELECT qty::text FROM connector." + view + " WHERE no = 'A1'")).isEqualTo("1000");
        assertThat(new BigDecimal(one("SELECT qty::text FROM connector." + view + " WHERE no = 'A2'")))
            .isEqualByComparingTo("748.5");
    }

    /** 2: the stored manifest is the one compiled, not this instance's cached copy (another replica's apply). */
    @Test
    void the_stored_manifest_is_compiled_not_a_cached_copy() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_stale_" + n, name = "WidenStale" + n, view = "v_widenstale" + n;
        writeChunk(table, "s1", Map.of("no", "S1", "qty", 1000, "note", "kept"));
        applyEntity(entity(name, table, "no: no, qty: qty"));
        sql("UPDATE connector.entity_manifest SET yaml_text = '" + entity(name, table, "no: no, qty: qty, note: note")
            + "' WHERE name = '" + name.toLowerCase() + "'");

        writeChunk(table, "s2", Map.of("no", "S2", "qty", 2.5d, "note", "new"));

        assertThat(type(view, "note")).as("the stored definition's column").isNotNull();
    }

    /** 3: an entity deleted while the widening waits for its lock is not brought back as an orphan view. */
    @Test
    void an_entity_deleted_meanwhile_leaves_no_orphan_view() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_deleted_" + n, name = "WidenDeleted" + n, view = "v_widendeleted" + n;
        writeChunk(table, "d1", Map.of("no", "D1", "qty", 1000));
        applyEntity(entity(name, table, "no: no, qty: qty"));
        compiler.afterWalk(c -> compiler.delete(name));

        writeChunk(table, "d2", Map.of("no", "D2", "qty", 1.5d));

        assertThat(type(table, "qty")).isEqualTo("numeric");
        assertThat(one("SELECT to_regclass('connector." + view + "')::text")).as("no orphan").isNull();
    }

    /** 4: another tenant's first chunk widens in the evolve step, never inside the creation transaction. */
    @Test
    void another_tenants_first_chunk_widens_after_its_creation_transaction() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_create_" + n, view = "v_widencreate" + n;
        writeChunk(1L, table, "c1", Map.of("no", "C1", "qty", 1000));
        applyEntity(entity("WidenCreate" + n, table, "no: no, qty: qty"));

        writeChunk(2L, table, "c2", Map.of("no", "C2", "qty", 12.25d));

        assertThat(type(table, "qty")).isEqualTo("numeric");
        assertThat(type(view, "qty")).isEqualTo("numeric");
    }

    /** 5: a view the hub did not make, directly or over the entity's view, refuses by name before any DDL. */
    @Test
    void a_foreign_view_at_any_depth_refuses_before_any_ddl() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_foreign_" + n, view = "v_widenforeign" + n;
        String direct = "r_direct_" + n, stacked = "r_stacked_" + n;
        writeChunk(table, "f1", Map.of("no", "F1", "qty", 1000));
        applyEntity(entity("WidenForeign" + n, table, "no: no, qty: qty"));
        sql("CREATE VIEW connector." + direct + " AS SELECT qty FROM connector." + table);
        sql("CREATE VIEW connector." + stacked + " AS SELECT qty FROM connector." + view);
        try {
            assertThatThrownBy(() -> writeChunk(table, "f2", Map.of("no", "F2", "qty", 3.5d)))
                .hasStackTraceContaining("connector." + direct)
                .hasStackTraceContaining("connector." + stacked)
                .hasStackTraceContaining("cannot widen");
            assertThat(type(table, "qty")).isEqualTo("bigint");
            assertThat(type(view, "qty")).as("the entity's view untouched").isEqualTo("bigint");
        } finally {
            sql("DROP VIEW IF EXISTS connector." + stacked);
            sql("DROP VIEW IF EXISTS connector." + direct);
        }
    }

    /** 5b: a materialized view is named as one, so the gesture (DROP MATERIALIZED VIEW) is the right one. */
    @Test
    void a_foreign_materialized_view_is_named_as_one() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_matview_" + n, mat = "m_report_" + n;
        writeChunk(table, "m1", Map.of("no", "M1", "qty", 1000));
        sql("CREATE MATERIALIZED VIEW connector." + mat + " AS SELECT qty FROM connector." + table);
        try {
            assertThatThrownBy(() -> writeChunk(table, "m2", Map.of("no", "M2", "qty", 3.5d)))
                .hasStackTraceContaining("materialized view connector." + mat);
            assertThat(type(table, "qty")).isEqualTo("bigint");
        } finally {
            sql("DROP MATERIALIZED VIEW IF EXISTS connector." + mat);
        }
    }

    /** 5c: the walk has no depth cap: the last of seventeen stacked views is still found, and named. */
    @Test
    void a_view_seventeen_levels_up_is_still_found() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_deep_" + n;
        writeChunk(table, "z1", Map.of("no", "Z1", "qty", 1000));
        String below = "connector." + table;
        for (int i = 1; i <= 17; i++) {
            sql("CREATE VIEW connector.r_deep_" + n + "_" + i + " AS SELECT qty FROM " + below);
            below = "connector.r_deep_" + n + "_" + i;
        }
        try {
            assertThatThrownBy(() -> writeChunk(table, "z2", Map.of("no", "Z2", "qty", 9.5d)))
                .hasStackTraceContaining("view connector.r_deep_" + n + "_17");
        } finally {
            sql("DROP VIEW IF EXISTS connector.r_deep_" + n + "_1 CASCADE");
        }
    }

    /** 5d: a lattice of views (two per level, each reading both below) is walked once per view, not once per path. */
    @Test
    void a_lattice_of_views_is_walked_without_enumerating_its_paths() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_lattice_" + n;
        writeChunk(table, "q1", Map.of("no", "Q1", "qty", 1000));
        String a = "connector." + table, b = "connector." + table;
        for (int i = 1; i <= 24; i++) {   // 2^24 paths to the top
            String na = "connector.r_lat_" + n + "_" + i + "a", nb = "connector.r_lat_" + n + "_" + i + "b";
            sql("CREATE VIEW " + na + " AS SELECT x.qty FROM " + a + " x, " + b + " y LIMIT 0");
            sql("CREATE VIEW " + nb + " AS SELECT x.qty FROM " + a + " x, " + b + " y LIMIT 0");
            a = na;
            b = nb;
        }
        try {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> writeChunk(table, "q2", Map.of("no", "Q2", "qty", 1.5d)))
                .hasStackTraceContaining("r_lat_" + n + "_24a");
            assertThat((System.nanoTime() - t0) / 1_000_000).as("ms").isLessThan(10_000);
        } finally {
            sql("DROP VIEW IF EXISTS connector.r_lat_" + n + "_1a, connector.r_lat_" + n + "_1b CASCADE");
        }
    }

    /** 6: an entity built on another entity's view is rebuilt after it. */
    @Test
    void an_entity_over_another_entitys_view_is_rebuilt_in_order() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_base_" + n, base = "v_widenbase" + n, top = "v_widentop" + n;
        writeChunk(table, "b1", Map.of("no", "B1", "qty", 1000));
        applyEntity(entity("WidenBase" + n, table, "no: no, qty: qty"));
        applyEntity(entity("WidenTop" + n, base, "no: no, qty: qty"));

        writeChunk(table, "b2", Map.of("no", "B2", "qty", 0.25d));

        assertThat(type(base, "qty")).isEqualTo("numeric");
        assertThat(type(top, "qty")).isEqualTo("numeric");
    }

    /** 7: a failure after the drops rolls everything back; the entity's view is as it was. */
    @Test
    void a_failure_after_the_drops_leaves_the_entity_view_as_it_was() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_atomic_" + n, view = "v_widenatomic" + n;
        writeChunk(table, "a1", Map.of("no", "A1", "qty", 1000));
        applyEntity(entity("WidenAtomic" + n, table, "no: no, qty: qty"));
        compiler.afterDrops(c -> {
            throw new IllegalStateException("injected after the drops");
        });

        assertThatThrownBy(() -> writeChunk(table, "a2", Map.of("no", "A2", "qty", 4.5d)))
            .hasStackTraceContaining("injected after the drops");

        assertThat(type(view, "qty")).isEqualTo("bigint");
        assertThat(type(table, "qty")).isEqualTo("bigint");
    }

    /** 8: a column another tenant already widened needs no DDL, even under a foreign view. */
    @Test
    void a_column_already_widened_by_another_tenant_needs_no_ddl() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_twice_" + n, report = "r_twice_" + n;
        writeChunk(2L, table, "t0", Map.of("no", "T0", "qty", 7));          // tenant 2 registers it BIGINT
        writeChunk(1L, table, "t1", Map.of("no", "T1", "qty", 7.5d));       // tenant 1 widens it
        sql("CREATE VIEW connector." + report + " AS SELECT qty FROM connector." + table);
        try {
            writeChunk(2L, table, "t2", Map.of("no", "T2", "qty", 8.5d));   // tenant 2's registry is stale

            assertThat(one("SELECT schema_inferred->>'qty' FROM connector.dynamic_table WHERE tenant_id = 2 "
                + "AND mapping_id = '" + table + "'")).isEqualTo("NUMERIC");
        } finally {
            sql("DROP VIEW IF EXISTS connector." + report);
        }
    }

    /** 9: plain numeric has no 10^14 ceiling: a large whole value widens. */
    @Test
    void a_bigint_past_ten_to_the_fourteenth_widens() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_large_" + n;
        writeChunk(table, "l1", Map.of("no", "L1", "qty", 123_456_789_012_345L));

        writeChunk(table, "l2", Map.of("no", "L2", "qty", 0.5d));

        assertThat(type(table, "qty")).isEqualTo("numeric");
        assertThat(one("SELECT qty::text FROM connector." + table + " WHERE no = 'L1'")).isEqualTo("123456789012345");
    }

    /** 10: a view appearing after the walk makes the ALTER fail; all rolls back, and the next run refuses it by name. */
    @Test
    void a_view_appearing_after_the_walk_rolls_back_and_is_refused_next_time() throws Exception {
        long n = System.nanoTime();
        String table = "t_widen_race_" + n, view = "v_widenrace" + n, late = "r_late_" + n;
        writeChunk(table, "r1", Map.of("no", "R1", "qty", 1000));
        applyEntity(entity("WidenRace" + n, table, "no: no, qty: qty"));
        compiler.afterWalk(c -> {
            try (Connection other = adminDs.getConnection(); Statement st = other.createStatement()) {
                st.execute("CREATE VIEW connector." + late + " AS SELECT qty FROM connector." + table);
            }
        });
        try {
            assertThatThrownBy(() -> writeChunk(table, "r2", Map.of("no", "R2", "qty", 5.5d)));
            assertThat(type(view, "qty")).as("rolled back").isEqualTo("bigint");
            compiler.afterWalk(c -> {});

            assertThatThrownBy(() -> writeChunk(table, "r3", Map.of("no", "R3", "qty", 6.5d)))
                .hasStackTraceContaining("connector." + late);
        } finally {
            sql("DROP VIEW IF EXISTS connector." + late);
        }
    }

    /** 11: the registry says the physical type: NUMERIC once widened, NUMERIC(18,4) for a decimal from the start. */
    @Test
    void the_registry_labels_a_widened_column_numeric_and_a_created_decimal_numeric_18_4() throws Exception {
        long n = System.nanoTime();
        String widened = "t_widen_label_" + n, created = "t_decimal_label_" + n;
        writeChunk(widened, "x1", Map.of("no", "X1", "qty", 1));
        writeChunk(widened, "x2", Map.of("no", "X2", "qty", 1.5d));
        writeChunk(widened, "x3", Map.of("no", "X3", "qty", 2.5d));      // a second fraction: nothing to widen
        writeChunk(created, "y1", Map.of("no", "Y1", "qty", 1.5d));

        assertThat(one("SELECT schema_inferred->>'qty' FROM connector.dynamic_table WHERE tenant_id = 1 "
            + "AND mapping_id = '" + widened + "'")).isEqualTo("NUMERIC");
        assertThat(type(widened, "qty")).isEqualTo("numeric");
        assertThat(one("SELECT schema_inferred->>'qty' FROM connector.dynamic_table WHERE tenant_id = 1 "
            + "AND mapping_id = '" + created + "'")).isEqualTo("NUMERIC(18,4)");
        assertThat(type(created, "qty")).isEqualTo("numeric(18,4)");
    }
}
