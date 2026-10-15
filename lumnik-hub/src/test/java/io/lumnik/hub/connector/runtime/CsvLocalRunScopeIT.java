package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #898 (d): a local folder of many files runs through the engine, which hands the local reader a usable run scope.
 * Twelve files of 100 rows, chunks of 500: three chunks. Rows are conserved, and a second run changes nothing. (How
 * many listings a run makes is pinned at reader level, in {@code CsvGlobListingScopeTest}.)
 */
@QuarkusTest
class CsvLocalRunScopeIT {

    private static final int FILES = 12;
    private static final int PER_FILE = 100;

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private long count(String table) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM connector." + table + " WHERE tenant_id = 1")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID seedConnector(Path dir, String table) {
        Map<String, Object> transport = Map.of("kind", "local", "path", dir.resolve("*.csv").toString());
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("scope-local-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport, "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath(dir.resolve("*.csv").toString()); ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* no transaction to roll back */ }
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_folder_of_many_files_runs_through_the_scope_and_conserves_its_rows(@TempDir Path dir) throws Exception {
        for (int f = 0; f < FILES; f++) {
            StringBuilder csv = new StringBuilder("email,name\n");
            for (int r = 0; r < PER_FILE; r++) {
                csv.append("f").append(f).append("r").append(r).append("@example.com,User").append(r).append('\n');
            }
            Files.writeString(dir.resolve(String.format("part-%02d.csv", f)), csv.toString());
        }
        UUID id = seedConnector(dir, "scope_local_many");

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(count("t_scope_local_many")).as("every row arrived").isEqualTo((long) FILES * PER_FILE);

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(count("t_scope_local_many")).as("a second run adds nothing").isEqualTo((long) FILES * PER_FILE);
    }

    @Test
    void a_file_larger_than_a_chunk_can_be_archived_once_its_run_completed(@TempDir Path dir) throws Exception {
        StringBuilder csv = new StringBuilder("email,name\n");
        for (int r = 0; r < 600; r++) csv.append("big").append(r).append("@example.com,User").append(r).append('\n');
        Files.writeString(dir.resolve("a.csv"), csv.toString());
        UUID id = seedConnector(dir, "scope_local_archive");

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(count("t_scope_local_archive")).isEqualTo(600L);

        Files.delete(dir.resolve("a.csv"));   // archived by an external job once the run completed
        Files.writeString(dir.resolve("b.csv"), "email,name\nnew1@example.com,N1\nnew2@example.com,N2\n");

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(count("t_scope_local_archive")).as("the next file is read, nothing is lost").isEqualTo(602L);
    }

    private static String rows(String prefix, int from, int count) {
        StringBuilder csv = new StringBuilder("email,name\n");
        for (int r = from; r < from + count; r++) csv.append(prefix).append(r).append("@example.com,U").append(r).append('\n');
        return csv.toString();
    }

    private long read(UUID id) {
        return ((RunStatus.Completed) engine.runSync(id, new RunTrigger.Manual(1L))).records();
    }

    /** The run-by-run tables of docs/connectors/csv.md, executed. */
    @Test
    void a_drop_folder_reads_each_row_once_as_the_docs_table_says(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("2026-09-01.csv"), rows("a", 0, 1200));
        UUID id = seedConnector(dir, "scope_local_docs");

        assertThat(read(id)).as("run 1: first read").isEqualTo(1200);
        assertThat(read(id)).as("run 2: the same file").isZero();
        Files.writeString(dir.resolve("2026-09-02.csv"), rows("b", 0, 10));
        assertThat(read(id)).as("run 3: only the new file").isEqualTo(10);
        Files.writeString(dir.resolve("2026-08-31.csv"), rows("c", 0, 5));
        assertThat(read(id)).as("run 4: sorts before the cursor's file, never read").isZero();
        assertThat(count("t_scope_local_docs")).isEqualTo(1210L);
    }

    @Test
    void a_small_file_is_read_once_and_again_in_full_when_it_changes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("clients.csv"), rows("x", 0, 2));
        UUID id = seedConnector(dir, "scope_local_small");

        assertThat(read(id)).isEqualTo(2);
        assertThat(read(id)).isZero();
        Files.writeString(dir.resolve("clients.csv"), rows("x", 0, 3));
        assertThat(read(id)).as("a changed file is read again in full").isEqualTo(3);
        assertThat(read(id)).as("and then not at all").isZero();
        assertThat(count("t_scope_local_small")).isEqualTo(3L);
    }
}
