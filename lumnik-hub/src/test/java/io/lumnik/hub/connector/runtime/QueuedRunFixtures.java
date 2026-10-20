package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.transaction.UserTransaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;

final class QueuedRunFixtures {

    record Seeded(UUID connectorId, List<UUID> endpointIds, List<Path> files) {}

    record RunRow(String status, Instant startedAt, Instant endedAt, Instant queuedAt, String errorMessage,
                  boolean resolutionExempt, long recordsOut) {}

    /** What the endpoint shows of its last run (stamped with the run's ending, by finalizeRun or by the queue). */
    record Stamp(String status, Instant at) {}

    private QueuedRunFixtures() {}

    /** A csv-file connector (tenant 1) with {@code count} endpoints, each reading its own 3-row file. */
    static Seeded csvConnector(ConnectorRepository connectors, ConnectorEndpointRepository endpoints,
                               UserTransaction utx, int count) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        Path dir = Files.createTempDirectory("queued_run_it_");
        dir.toFile().deleteOnExit();
        List<UUID> ids = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        UUID connectorId = runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("queued-run-" + suffix);
            c.setTypeId("csv-file");
            c.setConfig(Map.of("kind", "csv-file", "parser", Map.of("has_header", true)));
            connectors.persist(c);
            connectors.flush();
            for (int i = 0; i < count; i++) {
                Path file = dir.resolve("e" + i + ".csv");
                Files.writeString(file, "row_id,label\n1,a\n2,b\n3,c\n");
                file.toFile().deleteOnExit();
                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("e" + i);
                ep.setMethod("GET");
                ep.setPath(file.toString());
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", "qr" + i + "_" + suffix,
                    "transport", Map.of("kind", "local", "path", file.toString())));
                endpoints.persist(ep);
                endpoints.flush();
                ids.add(ep.getId());
                files.add(file);
            }
            return c.getId();
        });
        return new Seeded(connectorId, ids, files);
    }

    /** The witness a door would store: the hash of the definition as the dispatcher will read it. */
    static String witness(ConnectorRepository connectors, ConnectorEndpointRepository endpoints, UserTransaction utx,
                          UUID connectorId, UUID endpointId) throws Exception {
        return runInTxAsSystem(utx, () -> DefinitionHash.of(connectors.findById(connectorId), endpoints.findById(endpointId)));
    }

    /** Writes a Queued row the way slice 3's door will: no start, an accept instant, a witness. */
    static UUID queue(AgroalDataSource adminDs, UUID connectorId, UUID endpointId, String witness, Instant queuedAt) {
        UUID id = UUID.randomUUID();
        Jdbc.update(adminDs, "queue a run", "INSERT INTO connector.connector_run (id, tenant_id, connector_id, "
            + "endpoint_id, status, sync_mode, started_at, queued_at, triggered_by, definition_sha256) "
            + "VALUES (?, 1, ?, ?, 'Queued', 'Incremental', NULL, ?, 'manual', ?)",
            ps -> { ps.setObject(1, id); ps.setObject(2, connectorId); ps.setObject(3, endpointId);
                    ps.setObject(4, queuedAt.atOffset(ZoneOffset.UTC)); ps.setString(5, witness); });
        return id;
    }

    static RunRow row(AgroalDataSource adminDs, UUID runId) {
        return Jdbc.queryOne(adminDs, "run row", "SELECT status, started_at, ended_at, queued_at, error_message, "
            + "resolution_exempt, records_out FROM connector.connector_run WHERE id = ?",
            ps -> ps.setObject(1, runId),
            rs -> new RunRow(rs.getString(1), Jdbc.instant(rs, 2), Jdbc.instant(rs, 3), Jdbc.instant(rs, 4), rs.getString(5),
                rs.getBoolean(6), rs.getLong(7))).orElseThrow();
    }

    static Stamp stamp(AgroalDataSource adminDs, UUID endpointId) {
        return Jdbc.queryOne(adminDs, "endpoint stamp", "SELECT last_run_status, last_run_at "
            + "FROM connector.connector_endpoint WHERE id = ?",
            ps -> ps.setObject(1, endpointId), rs -> new Stamp(rs.getString(1), Jdbc.instant(rs, 2))).orElseThrow();
    }

    /** Every Queued row, whoever wrote it: a dispatcher takes the oldest of ALL of them. */
    static void clearQueue(AgroalDataSource adminDs) {
        Jdbc.update(adminDs, "clear the queue", "DELETE FROM connector.connector_run WHERE status = 'Queued'", ps -> {});
    }
}
