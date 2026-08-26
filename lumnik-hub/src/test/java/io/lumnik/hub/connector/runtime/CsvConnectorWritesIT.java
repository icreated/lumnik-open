package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-run IT: proves the CSV connector WRITES rows into {@code connector.t_csv_writes}.
 *
 * <p>Acceptance gate for the SPINE task:
 * <ol>
 *   <li>Write a temp CSV file with a header + 2 data rows.</li>
 *   <li>Persist a {@code Connector} (typeId {@code csv-file}, tenant 1) with transport/parser
 *       config, and a {@code ConnectorEndpoint} with {@code config = {name: csv_writes}}
 *       (what Change 1 produces at apply time; set directly here to prove the runtime spine).</li>
 *   <li>Run synchronously — assert {@code RunStatus.Completed}.</li>
 *   <li>Assert 2 rows landed in {@code connector.t_csv_writes}.</li>
 * </ol>
 */
@QuarkusTest
class CsvConnectorWritesIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private Path csvFile;

    @BeforeEach
    void setup() throws Exception {
        csvFile = Files.createTempFile("csv_writes_it_", ".csv");
        csvFile.toFile().deleteOnExit();
        // Avoid column name "id" — it collides with DynamicTableWriter's BIGSERIAL system column.
        Files.writeString(csvFile, "row_id,email\n1,a@x\n2,b@x\n");

        execSql(adminDs, "DROP TABLE IF EXISTS connector.t_csv_writes");
        // The registry is a DB table keyed by (tenant, mapping); RunCompletedPayloadIT shares the
        // same t_csv_writes target. Drop the stale registry row so this run re-creates the table
        // from scratch — a leftover registry row skips createSchema → "relation does not exist".
        execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name = 't_csv_writes'");
    }

    @Test
    void csvConnector_writesRows_toHubTable() throws Exception {
        String filePath = csvFile.toAbsolutePath().toString();

        java.util.UUID connectorId = runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("csv-writes-" + System.nanoTime());
            c.setTypeId("csv-file");
            c.setConfig(Map.of(
                "kind", "csv-file",
                "transport", Map.of("kind", "local", "path", filePath),
                "parser",    Map.of("has_header", true)
            ));
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath(filePath);
            ep.setEnabled(true);
            ep.setConfig(Map.of("name", "csv_writes"));
            endpoints.persist(ep);
            endpoints.flush();

            return c.getId();
        });

        RunStatus s = engine.runSync(connectorId, new RunTrigger.Manual(1L));

        assertThat(s)
            .as("connector run must complete successfully")
            .isInstanceOf(RunStatus.Completed.class);

        assertThat(scalar(adminDs, "SELECT count(*) FROM connector.t_csv_writes"))
            .as("2 CSV rows must be persisted to connector.t_csv_writes")
            .isEqualTo(2L);
    }
}
