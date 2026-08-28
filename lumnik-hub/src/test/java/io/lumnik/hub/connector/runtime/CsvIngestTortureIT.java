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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic full-run torture suite for the CSV connector.
 *
 * <p>Covers: headerless ingestion, semicolon/latin1 decoding, idempotent re-run,
 * empty/header-only files, and per-row tenant_id stamping.
 *
 * <p>Each test manages its own connector+endpoint and drops its target table in {@code @BeforeEach}.
 */
@QuarkusTest
class CsvIngestTortureIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void dropAllTables() {
        execSql("DROP TABLE IF EXISTS connector.t_tort_headerless");
        execSql("DROP TABLE IF EXISTS connector.t_tort_semi");
        execSql("DROP TABLE IF EXISTS connector.t_tort_idem");
        execSql("DROP TABLE IF EXISTS connector.t_tort_empty");
        execSql("DROP TABLE IF EXISTS connector.t_tort_hdronly");
        execSql("DROP TABLE IF EXISTS connector.t_tort_tenant");
    }

    // ── Test 1 ──────────────────────────────────────────────────────────────────

    /**
     * Headerless CSV with explicit column names: both rows must land.
     */
    @Test
    void headerless_with_columns_ingests_both_rows() throws Exception {
        Path csv = Files.createTempFile("tort_headerless_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "1,alice@x.com,FR\n2,bob@x.com,BE\n");

        UUID id = persistCsvConnector(
            "tort-headerless",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("has_header", false, "columns", List.of("row_id", "email", "country")),
            "tort_headerless"
        );

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));

        assertThat(status).as("run must complete").isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_tort_headerless"))
            .as("both rows must be persisted")
            .isEqualTo(2L);
    }

    // ── Test 2 ──────────────────────────────────────────────────────────────────

    /**
     * Semicolon-delimited file written in ISO-8859-1 bytes: accented characters must survive.
     */
    @Test
    void semicolon_latin1_decodes_accents() throws Exception {
        Path csv = Files.createTempFile("tort_semi_", ".csv");
        csv.toFile().deleteOnExit();
        Files.write(csv, "row_id;city\n1;Genève\n2;Zürich\n".getBytes(StandardCharsets.ISO_8859_1));

        UUID id = persistCsvConnector(
            "tort-semi",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("delimiter", ";", "encoding", "ISO-8859-1"),
            "tort_semi"
        );

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));

        assertThat(status).as("run must complete").isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_tort_semi WHERE city = 'Genève'"))
            .as("Genève must be decoded correctly from ISO-8859-1")
            .isEqualTo(1L);
    }

    // ── Test 3 ──────────────────────────────────────────────────────────────────

    /**
     * Running the same file twice must NOT duplicate rows — idempotent via _row_hash UNIQUE.
     */
    @Test
    void rerun_same_file_is_idempotent() throws Exception {
        Path csv = Files.createTempFile("tort_idem_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,a@x.com\n2,b@x.com\n");

        UUID id = persistCsvConnector(
            "tort-idem",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("has_header", true),
            "tort_idem"
        );

        RunStatus s1 = engine.runSync(id, new RunTrigger.Manual(1L));
        long countAfterFirst = scalar("SELECT count(*) FROM connector.t_tort_idem");

        RunStatus s2 = engine.runSync(id, new RunTrigger.Manual(1L));
        long countAfterSecond = scalar("SELECT count(*) FROM connector.t_tort_idem");

        assertThat(s1).as("first run must complete").isInstanceOf(RunStatus.Completed.class);
        assertThat(s2).as("second run must complete").isInstanceOf(RunStatus.Completed.class);
        assertThat(countAfterSecond)
            .as("re-run must not duplicate rows (idempotent via _row_hash)")
            .isEqualTo(countAfterFirst);
    }

    // ── Test 4 ──────────────────────────────────────────────────────────────────

    /**
     * Empty file and header-only file: both must complete with zero data rows.
     *
     * <p>For the empty file the table may not be created (DynamicTableWriter skips empty batches);
     * we guard the count with {@code to_regclass}. For the header-only file the table may also not
     * be created (no data rows → empty batch → skip), so we guard that count the same way.
     */
    @Test
    void empty_and_header_only_complete_with_zero_rows() throws Exception {
        // Connector A: completely empty file
        Path emptyFile = Files.createTempFile("tort_empty_", ".csv");
        emptyFile.toFile().deleteOnExit();
        Files.writeString(emptyFile, "");

        UUID emptyId = persistCsvConnector(
            "tort-empty",
            Map.of("kind", "local", "path", emptyFile.toAbsolutePath().toString()),
            Map.of("has_header", true),
            "tort_empty"
        );

        RunStatus emptyStatus = engine.runSync(emptyId, new RunTrigger.Manual(1L));
        assertThat(emptyStatus).as("empty file run must complete").isInstanceOf(RunStatus.Completed.class);

        // Guard: table may not exist for an empty file (no rows → no DDL)
        String emptyTableExists = scalarText(
            "SELECT to_regclass('connector.t_tort_empty')::text");
        if (emptyTableExists != null) {
            assertThat(scalar("SELECT count(*) FROM connector.t_tort_empty"))
                .as("empty file must produce 0 rows")
                .isEqualTo(0L);
        }

        // Connector B: header row only, no data rows
        Path hdrOnly = Files.createTempFile("tort_hdronly_", ".csv");
        hdrOnly.toFile().deleteOnExit();
        Files.writeString(hdrOnly, "row_id,email\n");

        UUID hdrId = persistCsvConnector(
            "tort-hdronly",
            Map.of("kind", "local", "path", hdrOnly.toAbsolutePath().toString()),
            Map.of("has_header", true),
            "tort_hdronly"
        );

        RunStatus hdrStatus = engine.runSync(hdrId, new RunTrigger.Manual(1L));
        assertThat(hdrStatus).as("header-only file run must complete").isInstanceOf(RunStatus.Completed.class);

        // For header-only the table also may not exist (no data rows → empty batch → skip)
        String hdrTableExists = scalarText(
            "SELECT to_regclass('connector.t_tort_hdronly')::text");
        if (hdrTableExists != null) {
            assertThat(scalar("SELECT count(*) FROM connector.t_tort_hdronly"))
                .as("header-only file must produce 0 data rows")
                .isEqualTo(0L);
        }
    }

    // ── Test 5 ──────────────────────────────────────────────────────────────────

    /**
     * Every written row must carry tenant_id = 1 (the run tenant).
     */
    @Test
    void written_rows_carry_tenant_id() throws Exception {
        Path csv = Files.createTempFile("tort_tenant_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,label\n1,alpha\n2,beta\n3,gamma\n");

        UUID id = persistCsvConnector(
            "tort-tenant",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("has_header", true),
            "tort_tenant"
        );

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));

        assertThat(status).as("run must complete").isInstanceOf(RunStatus.Completed.class);

        long total  = scalar("SELECT count(*) FROM connector.t_tort_tenant");
        long tenant = scalar("SELECT count(*) FROM connector.t_tort_tenant WHERE tenant_id = 1");

        assertThat(total).as("3 rows must be written").isEqualTo(3L);
        assertThat(tenant)
            .as("all rows must carry tenant_id = 1")
            .isEqualTo(total);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Persists a Connector + ConnectorEndpoint inside a system transaction and returns the
     * connector UUID. Mirrors the pattern in {@link CsvConnectorWritesIT}.
     *
     * @param connectorName unique name for this test's connector
     * @param transport     transport block (kind + path + optional extras)
     * @param parser        parser options (has_header, delimiter, encoding, columns, …)
     * @param table         endpoint table name → endpoint config {name: <table>}
     */
    private UUID persistCsvConnector(
        String connectorName,
        Map<String, Object> transport,
        Map<String, Object> parser,
        String table
    ) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();

                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", transport);
                cfg.put("parser", parser);

                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName(connectorName + "-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(cfg);
                connectors.persist(c);
                connectors.flush();

                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(transport.getOrDefault("path", "").toString());
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                endpoints.flush();

                utx.commit();
                return c.getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String scalarText(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
