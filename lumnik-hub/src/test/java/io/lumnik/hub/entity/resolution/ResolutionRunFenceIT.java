package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ResolutionRunFenceIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @Test
    void theHibernateInsertPathGetsTheSameDefaultBecauseTheColumnIsUnmapped() throws Exception {
        UUID run = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(748_002L);
                c.setName("fence748-jpa-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file"));
                connectors.persist(c);
                connectors.flush();

                ConnectorRun r = new ConnectorRun();
                r.setTenantId(748_002L);
                r.setConnectorId(c.getId());
                r.setStatus("Running");
                r.setSyncMode("full");
                r.setTriggeredBy("Manual");
                runs.persist(r);
                runs.flush();
                utx.commit();
                return r.getId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
                throw new RuntimeException(e);
            }
        });
        assertThat(fenceOf(run)).isNotNull();
    }

    @Test
    void theDefaultIsSetAfterTheColumnExistsAndBothPartialIndexesAreThere() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'connector' "
                    + "AND tablename = 'connector_run' AND indexname LIKE 'idx_run_resolution_fence%'")) {
                while (rs.next()) indexes.add(rs.getString(1));
            }
            assertThat(indexes).containsExactlyInAnyOrder("idx_run_resolution_fence", "idx_run_resolution_fence_unknown");
            try (ResultSet rs = st.executeQuery("SELECT column_default FROM information_schema.columns WHERE "
                    + "table_schema = 'connector' AND table_name = 'connector_run' AND column_name = 'resolution_fence_at'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("clock_timestamp");
            }
        }
    }

    private Instant fenceOf(UUID run) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT resolution_fence_at FROM connector.connector_run WHERE id = ?")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        }
    }
}
