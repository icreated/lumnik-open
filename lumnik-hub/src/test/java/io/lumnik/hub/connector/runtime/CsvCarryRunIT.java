package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.mapping.hooks.TestCarryHook;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.testsupport.EventItSupport;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1452 A, end to end: a real csv-file connector whose mapping carries values row to row ({@code test-carry}, after a
 * {@code parse-date} so the carried value is a LocalDate) — across chunks of 500, reset at each file of a glob, through
 * a failed run and its resume, and never under a mapping changed in between.
 */
@QuarkusTest
class CsvCarryRunIT {

    private static final List<Object> CELL = List.of(
        Map.of("parse-date", Map.of("column", "d", "formats", List.of("yyyyMMdd"))),
        Map.of("test-carry", Map.of("column", "d")));

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    record Ids(UUID connector, UUID endpoint) {}

    @AfterEach
    void stopFailing() {
        TestCarryHook.failWhen = null;
    }

    /** 1200 rows, three chunks of 500: one sequence, each row's prev the previous row's date, typed DATE. */
    @Test
    void carries_across_chunks_in_file_order() throws Exception {
        Ids ids = connector("carry_chunks", csv(1200).toString(), CELL);
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Completed.class);

        assertThat(scalarText("SELECT count(*) FROM connector.t_carry_chunks WHERE seq = row_no::bigint"))
            .isEqualTo("1200");
        assertThat(scalarText("SELECT prev::text FROM connector.t_carry_chunks WHERE row_no = '501'"))
            .isEqualTo(scalarText("SELECT d::text FROM connector.t_carry_chunks WHERE row_no = '500'"));
        assertThat(scalarText("SELECT data_type FROM information_schema.columns WHERE table_schema = 'connector' "
            + "AND table_name = 't_carry_chunks' AND column_name = 'prev'")).isEqualTo("date");
    }

    /**
     * #1452 B: fill-down itself, on a flat file of headers and their lines (`lines: true`), 1200 lines over three
     * chunks: every line carries its own header's order number, across the chunk boundaries.
     */
    @Test
    void fill_down_carries_each_header_onto_its_lines_across_chunks() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int group = 0; group < 80; group++) {
            sb.append("H;ORD-").append(group).append('\n');
            for (int line = 1; line < 15; line++) sb.append("D;").append(group).append('-').append(line).append('\n');
        }
        Path file = Files.createTempFile("fill_down_it_", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, sb.toString());

        Map<String, Object> cfg = config(file.toString(), List.of(
            Map.of("regex-extract", Map.of("column", "line", "pattern", "^(H|D);(?:(ORD-\\d+)|(\\d+-\\d+))$",
                "groups", Map.of("1", "kind", "2", "order_no", "3", "item"))),
            Map.of("fill-down", Map.of("columns", List.of("order_no"),
                "reset-when", Map.of("column", "kind", "in", List.of("H"))))));
        cfg.put("parser", Map.of("lines", true));
        Ids ids = connector("fill_down_run", file.toString(), cfg);
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Completed.class);

        assertThat(scalarText("SELECT count(*) FROM connector.t_fill_down_run WHERE kind = 'D'")).isEqualTo("1120");
        assertThat(scalarText("SELECT count(*) FROM connector.t_fill_down_run WHERE kind = 'D' "
            + "AND order_no IS DISTINCT FROM 'ORD-' || split_part(item, '-', 1)")).isEqualTo("0");
    }

    /** Two files of one glob, small enough to share a chunk without the hook: the second starts empty. */
    @Test
    void starts_empty_at_each_file() throws Exception {
        Path dir = Files.createTempDirectory("carry_files_");
        Files.writeString(dir.resolve("a.csv"), "row_no,d,f\n1,20260101,a\n2,20260102,a\n3,20260103,a\n");
        Files.writeString(dir.resolve("b.csv"), "row_no,d,f\n1,20260201,b\n2,20260202,b\n3,20260203,b\n");
        Ids ids = connector("carry_files", dir.resolve("*.csv").toString(), CELL);
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Completed.class);

        assertThat(scalarText("SELECT string_agg(f || seq, ',' ORDER BY f, seq) FROM connector.t_carry_files"))
            .isEqualTo("a1,a2,a3,b1,b2,b3");
        assertThat(scalarText("SELECT count(*) FROM connector.t_carry_files WHERE seq = 1 AND prev IS NULL"))
            .isEqualTo("2");
    }

    /** A header-only file between two others: the run reads all three, it does not end "Completed" on the empty one. */
    @Test
    void an_empty_file_in_the_glob_does_not_end_the_run() throws Exception {
        Path dir = Files.createTempDirectory("carry_empty_");
        Files.writeString(dir.resolve("a.csv"), "row_no,d,f\n1,20260101,a\n2,20260102,a\n");
        Files.writeString(dir.resolve("b.csv"), "row_no,d,f\n");
        Files.writeString(dir.resolve("c.csv"), "row_no,d,f\n1,20260301,c\n");
        Ids ids = connector("carry_empty", dir.resolve("*.csv").toString(), CELL);
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Completed.class);

        assertThat(scalarText("SELECT string_agg(f || seq, ',' ORDER BY f, seq) FROM connector.t_carry_empty"))
            .isEqualTo("a1,a2,c1");
    }

    /**
     * A run that fails at row 700 (chunk 2) and is resumed lands exactly what a run that never stopped lands: values,
     * column types and row hashes.
     */
    @Test
    void a_resumed_run_lands_what_a_continuous_one_does() throws Exception {
        // Two connectors over one file, each inferring its own table: the row hash covers the row's data only.
        String file = csv(1200).toString();
        Ids continuous = connector("carry_continuous", file, CELL);
        assertThat(runAgain(continuous)).isInstanceOf(RunStatus.Completed.class);

        Ids ids = connector("carry_resume", file, CELL);
        TestCarryHook.failWhen = "700";
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Failed.class);
        assertThat(scalarText("SELECT count(*) FROM connector.t_carry_resume")).as("chunk 1 committed").isEqualTo("500");
        TestCarryHook.failWhen = null;
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Completed.class);

        assertThat(snapshot("carry_resume")).isEqualTo(snapshot("carry_continuous"));
    }

    /** A changed mapping never reuses a carry mid-file; after reset-cursor the file is read from its start. */
    @Test
    void a_changed_mapping_refuses_to_resume_mid_file() throws Exception {
        Ids ids = connector("carry_changed", csv(1200).toString(), CELL);
        TestCarryHook.failWhen = "700";
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Failed.class);
        TestCarryHook.failWhen = null;
        String positionBefore = cursorValue(ids);

        changeMapping(ids, List.of(
            Map.of("parse-date", Map.of("column", "d", "formats", List.of("yyyyMMdd", "yyyy-MM-dd"))),
            Map.of("test-carry", Map.of("column", "d"))));
        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Failed.class);
        assertThat(lastError(ids)).contains("lm endpoint reset-cursor");
        assertThat(cursorValue(ids)).as("the refusal moves nothing").isEqualTo(positionBefore);

        given().headers(TestIdentity.tenantAndUser(1))
            .post("/api/platform/connectors/" + ids.connector() + "/endpoints/" + ids.endpoint() + "/reset-cursor")
            .then().statusCode(200);
        assertThat(scalarText("SELECT count(*) FROM connector.connector_cursor WHERE connector_id = '"
            + ids.connector() + "'")).as("the door deletes the row, its carry with it").isEqualTo("0");

        assertThat(runAgain(ids)).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalarText("SELECT count(*) FROM connector.t_carry_changed WHERE seq <> row_no::bigint"))
            .isEqualTo("0");
        assertThat(scalarText("SELECT count(DISTINCT row_no) FROM connector.t_carry_changed")).isEqualTo("1200");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** "row_no,d" + n rows: row_no 1..n, d = 2026-01-01 + (i mod 365) days, as yyyyMMdd. */
    private static Path csv(int n) throws Exception {
        StringBuilder sb = new StringBuilder("row_no,d\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (int i = 1; i <= n; i++) {
            sb.append(i).append(',').append(LocalDate.of(2026, 1, 1).plusDays(i % 365).format(f)).append('\n');
        }
        Path file = Files.createTempFile("carry_it_", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, sb.toString());
        return file;
    }

    /** A csv-file connector over {@code path} writing connector.t_<table>, its mapping's cell tier {@code cell}. */
    private Ids connector(String table, String path, List<Object> cell) {
        return connector(table, path, config(path, cell));
    }

    private Ids connector(String table, String path, Map<String, Object> config) {
        EventItSupport.execSql(adminDs, "DROP TABLE IF EXISTS connector.t_" + table);
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("carry-" + System.nanoTime());
            c.setTypeId("csv-file");
            c.setConfig(config);
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath(path);
            ep.setEnabled(true);
            ep.setConfig(Map.of("name", table));
            endpoints.persist(ep);
            endpoints.flush();
            return new Ids(c.getId(), ep.getId());
        }));
    }

    private static Map<String, Object> config(String path, List<Object> cell) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("kind", "csv-file");
        cfg.put("transport", Map.of("kind", "local", "path", path));
        cfg.put("parser", Map.of("has_header", true, "delimiter", ","));
        cfg.put("mapping", Map.of("cell", cell));
        return cfg;
    }

    private void changeMapping(Ids ids, List<Object> cell) {
        TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
            Connector c = connectors.findById(ids.connector());
            c.setConfig(config(String.valueOf(((Map<?, ?>) c.getConfig().get("transport")).get("path")), cell));
        }));
    }

    private RunStatus runAgain(Ids ids) {
        return engine.runSync(ids.connector(), new RunTrigger.Manual(1L));
    }

    /** row_no, seq, prev and the row hash in file order, then the column types — one comparable text. */
    private String snapshot(String table) {
        return scalarText("SELECT string_agg(row_no || '|' || seq || '|' || coalesce(prev::text, '-') || '|' || _row_hash, "
            + "E'\\n' ORDER BY row_no::int) FROM connector.t_" + table)
            + "\n" + scalarText("SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name) "
            + "FROM information_schema.columns WHERE table_schema = 'connector' AND table_name = 't_" + table + "' "
            + "AND column_name IN ('row_no', 'd', 'seq', 'prev')");
    }

    private String cursorValue(Ids ids) {
        return scalarText("SELECT cursor_value::text FROM connector.connector_cursor WHERE endpoint_id = '"
            + ids.endpoint() + "'");
    }

    private String lastError(Ids ids) {
        return scalarText("SELECT error_message FROM connector.connector_run WHERE connector_id = '" + ids.connector()
            + "' ORDER BY started_at DESC LIMIT 1");
    }

    private String scalarText(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
