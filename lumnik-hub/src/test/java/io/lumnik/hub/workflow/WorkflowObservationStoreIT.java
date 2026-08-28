package io.lumnik.hub.workflow;

import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkflowObservationStoreIT {

    @Inject WorkflowObservationStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final long TENANT = 7401L;
    private static final String WF = "wf-obs-it";

    @Test
    void full_lifecycle_insert_transition_alert_and_cross_tenant_binding() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        store.insertNew(TENANT, WF, "CMD-1", "EN_COURS", t0);
        Map<String, WorkflowObservationStore.Observation> all = store.loadAll(TENANT, WF);
        assertThat(all).containsKey("CMD-1");
        assertThat(all.get("CMD-1").state()).isEqualTo("EN_COURS");
        assertThat(all.get("CMD-1").since()).isEqualTo(t0);
        assertThat(all.get("CMD-1").firstObservedAt()).isEqualTo(t0);
        assertThat(all.get("CMD-1").alertedAt()).isNull();

        store.markAlerted(TENANT, WF, "CMD-1", t0.plusSeconds(60));
        assertThat(store.loadAll(TENANT, WF).get("CMD-1").alertedAt()).isNotNull();

        Instant t1 = t0.plusSeconds(120);
        store.recordTransition(TENANT, WF, "CMD-1", "EN_COURS", "TERMINE", t1);
        WorkflowObservationStore.Observation after = store.loadAll(TENANT, WF).get("CMD-1");
        assertThat(after.state()).isEqualTo("TERMINE");
        assertThat(after.since()).isEqualTo(t1);
        assertThat(after.firstObservedAt()).isEqualTo(t0);   // first breath survives
        assertThat(after.alertedAt()).isNull();              // reset on change

        // exactly one transition row, with the right shape
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT from_state, to_state FROM core.workflow_transition "
                     + "WHERE tenant_id = ? AND workflow_name = ? AND doc_key = ?")) {
            ps.setLong(1, TENANT); ps.setString(2, WF); ps.setString(3, "CMD-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("EN_COURS");
                assertThat(rs.getString(2)).isEqualTo("TERMINE");
                assertThat(rs.next()).isFalse();
            }
        }

        assertThat(store.loadAll(7402L, WF)).isEmpty(); // explicit tenant binding
    }

    @Test
    void listAll_is_tenant_bound_stillest_first_and_limited() throws Exception {
        try (var c = adminDs.getConnection(); var st = c.createStatement()) {
            st.execute("DELETE FROM core.workflow_observation WHERE doc_key LIKE 'LSTALL-%'");
        }
        Instant now = Instant.now();
        store.insertNew(1L, "wf-a", "LSTALL-OLD", "draft", now.minusSeconds(3600));
        store.insertNew(1L, "wf-b", "LSTALL-MID", "sent",  now.minusSeconds(1800));
        store.insertNew(1L, "wf-a", "LSTALL-NEW", "paid",  now.minusSeconds(60));
        store.insertNew(2L, "wf-a", "LSTALL-T2",  "draft", now.minusSeconds(7200)); // other tenant

        var rows = store.listAll(1L, 10).stream()
            .filter(r -> r.docKey().startsWith("LSTALL-")).toList();
        Assertions.assertThat(rows)
            .extracting(WorkflowObservationStore.ObservationRow::docKey)
            .containsExactly("LSTALL-OLD", "LSTALL-MID", "LSTALL-NEW"); // since ASC, no tenant-2 row

        var limited = store.listAll(1L, 1);
        Assertions.assertThat(limited).hasSize(1);
    }
}
