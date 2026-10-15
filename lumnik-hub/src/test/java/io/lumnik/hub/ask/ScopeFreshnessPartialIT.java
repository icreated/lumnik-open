package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which run statuses date a scope's data.
 *
 * <p>{@code lm ask} prints "data as of …" from {@code MAX(ended_at)} over this scope's runs. The
 * predicate used to read {@code status = 'Completed'} alone, which was complete until {@code
 * Partial} existed: such a run WROTE everything it read and simply did not read everything there
 * was. On a topic whose retention keeps overtaking the cursor — the very condition Partial exists
 * to report — every run ends Partial, so the dial would have shown a stale timestamp, or nothing,
 * while fresh rows landed on every run.
 *
 * <p>Both directions, because the predicate has to keep refusing as well: a {@code Failed} run
 * wrote nothing it can vouch for and must not date anything.
 */
@QuarkusTest
class ScopeFreshnessPartialIT {

    private static final long TENANT = 1L;
    private static final String SCOPE = "freshness-probe";

    @Inject ScopeFreshness freshness;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private UUID connectorId;

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seedConnector() {
        execSql("DELETE FROM connector.connector_run WHERE connector_id IN "
              + "(SELECT id FROM connector.connector WHERE name LIKE 'freshness-probe-%')");
        execSql("DELETE FROM connector.connector WHERE name LIKE 'freshness-probe-%'");
        connectorId = UUID.randomUUID();
        execSql("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, created_at, updated_at) "
              + "VALUES ('" + connectorId + "', " + TENANT + ", 'freshness-probe-" + System.nanoTime()
              + "', 'kafka', '{}'::jsonb, true, ARRAY['" + SCOPE + "'], now(), now())");
    }

    private void seedRun(String status, String endedAt) {
        execSql("INSERT INTO connector.connector_run "
              + "(id, tenant_id, connector_id, status, sync_mode, started_at, ended_at, triggered_by) "
              + "VALUES ('" + UUID.randomUUID() + "', " + TENANT + ", '" + connectorId + "', '"
              + status + "', 'Incremental', now(), "
              + (endedAt == null ? "NULL" : "'" + endedAt + "'::timestamptz") + ", 'MANUAL')");
    }

    @Test
    void a_partial_run_dates_the_scope_because_it_landed_rows() {
        seedRun("Partial", "2026-09-12 10:00:00+00");

        assertThat(freshness.lastCompletedRun(TENANT, SCOPE))
            .as("a partial run wrote everything it read — the data IS as of then")
            .isPresent();
    }

    @Test
    void the_newest_landing_wins_whichever_of_the_two_it_was() {
        seedRun("Completed", "2026-09-11 10:00:00+00");
        seedRun("Partial", "2026-09-12 10:00:00+00");

        assertThat(freshness.lastCompletedRun(TENANT, SCOPE).orElseThrow().toString())
            .as("the partial run is the more recent landing, so it is the answer")
            .startsWith("2026-09-12T10:00");
    }

    @Test
    void a_landed_run_with_no_end_time_does_not_hide_an_older_dated_one() {
        // finalizeRun always stamps ended_at, but the schema does not demand it, and a newest-first
        // walk meets a NULL first (#907).
        seedRun("Completed", "2026-09-11 10:00:00+00");
        seedRun("Completed", null);

        assertThat(freshness.lastCompletedRun(TENANT, SCOPE).orElseThrow().toString())
            .as("a run with no end time dates nothing, and does not shadow one that has it")
            .startsWith("2026-09-11T10:00");
    }

    @Test
    void a_failed_run_dates_nothing() {
        seedRun("Failed", "2026-09-12 10:00:00+00");

        assertThat(freshness.lastCompletedRun(TENANT, SCOPE))
            .as("a failed run cannot vouch for what landed — the predicate must still refuse")
            .isEmpty();
    }
}
