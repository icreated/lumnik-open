package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1594: a widening, or a column added mid-run, changes an ingest table's row type. A pooled connection that had
 * prepared a read of the table inside a transaction (pgjdbc prepares server-side after five runs) answered "cached plan
 * must not change result type" once — the due sweep's page on the readonly pool missed a tick that way. The readonly
 * pool keeps no server-side plan ({@code prepareThreshold=0}), so no DDL can leave it a stale one, whoever runs it.
 * The test profile reads without transactions, where pgjdbc heals by itself: hence {@link ReadonlyTransactionsProfile}.
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class DynamicTableWriterPlanCacheIT {

    @Inject DynamicTableWriter writer;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

    /** One chunk, one row, committed — the production shape the writer expects. */
    private void writeChunk(MappingTarget.Table spec, String hash, Map<String, Object> row) {
        Map<String, Object> sys = Map.of(
            "_row_hash", hash, "_tenant", 1L, "_mapping", spec.name(),
            "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(row), List.of(sys), 1L);
                utx.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** A read the way the due sweep reads: in its own transaction, under the tenant, on the readonly pool. */
    private Object read(String sql, String hash) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, 1L);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, hash);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).as(hash).isTrue();
                        return rs.getObject(1);
                    }
                }
            }
        });
    }

    private static MappingTarget.Table spec(String name) {
        return new MappingTarget.Table("connector", name, true, MappingTarget.OnConflict.SKIP, List.of());
    }

    @Test
    void a_read_prepared_before_a_widening_succeeds_after_it() throws Exception {
        String name = "t_plan_widen_" + System.nanoTime();
        writeChunk(spec(name), "w1", Map.of("qty", 1000));
        String sql = "SELECT qty FROM connector." + name + " WHERE _row_hash = ?";
        for (int i = 0; i < 12; i++) read(sql, "w1");   // past pgjdbc's prepareThreshold on the connection it takes
        writeChunk(spec(name), "w2", Map.of("qty", 748.5d));   // bigint → numeric(18,4)
        assertThat((BigDecimal) read(sql, "w2")).isEqualByComparingTo("748.5");
    }

    @Test
    void a_select_star_prepared_before_a_column_is_added_succeeds_after_it() throws Exception {
        String name = "t_plan_add_" + System.nanoTime();
        writeChunk(spec(name), "a1", Map.of("email", "a@example.org"));
        String sql = "SELECT * FROM connector." + name + " WHERE _row_hash = ?";
        for (int i = 0; i < 12; i++) read(sql, "a1");
        writeChunk(spec(name), "a2", Map.of("email", "b@example.org", "phone", "0123456789"));   // ADD COLUMN phone
        assertThat(read(sql, "a2")).isNotNull();
    }

    /** Not only the writer reshapes a table (a promoted column, the _seen_run retrofit, a table recreated): any DDL. */
    @Test
    void a_read_prepared_before_any_ddl_on_the_table_succeeds_after_it() throws Exception {
        String name = "t_plan_ddl_" + System.nanoTime();
        writeChunk(spec(name), "d1", Map.of("qty", 1000));
        String sql = "SELECT * FROM connector." + name + " WHERE _row_hash = ?";
        for (int i = 0; i < 12; i++) read(sql, "d1");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE connector." + name + " ADD COLUMN promoted text");
        }
        assertThat(read(sql, "d1")).isNotNull();
    }
}
