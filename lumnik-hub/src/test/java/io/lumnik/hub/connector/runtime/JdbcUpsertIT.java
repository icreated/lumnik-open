package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1196: a jdbc-generic table holds one row per declared key. A changed source row replaces its
 * previous version, A → B → A reads A, and a table written before #1196 (one row per VERSION) is
 * moved to one row per key by its first run, which reports what it removed.
 */
@QuarkusTest
class JdbcUpsertIT {

    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";
    private static final String SOURCE = "jdbc:src_up";

    @Inject ChunkExecutor exec;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private String jdbcUrl;
    private Connector connector;
    private Map<String, Object> endpointConfig;
    private ConnectorEndpoint endpoint;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        sql("DROP TABLE IF EXISTS public.src_up");
        sql("DROP TABLE IF EXISTS connector.t_src_up");
        sql("DELETE FROM connector.dynamic_table WHERE table_name = 't_src_up'");
        sql("CREATE TABLE public.src_up (src_up_id bigint primary key, label text)");
        sql("INSERT INTO public.src_up VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        sql("GRANT SELECT ON public.src_up TO " + DB_USER);
        connector = null;
        endpointConfig = Map.of("schema", "public", "name", "src_up", "mode", "full", "pk_columns", List.of("src_up_id"));
    }

    @Test
    void a_changed_row_replaces_its_previous_version() {
        run();
        sql("UPDATE public.src_up SET label = 'a2' WHERE src_up_id = 1");

        ConnectorRun second = run();

        assertThat(rows()).containsExactly("1=a2", "2=b", "3=c");
        assertThat(scalar("SELECT count(*) FROM connector.t_src_up WHERE _run_id = '" + second.getId() + "'"))
            .as("the changed row names the run that brought its value").isEqualTo(1);
    }

    @Test
    void a_value_that_comes_back_reads_back() {
        run();
        sql("UPDATE public.src_up SET label = 'B' WHERE src_up_id = 2");
        run();
        sql("UPDATE public.src_up SET label = 'b' WHERE src_up_id = 2");

        run();

        assertThat(rows()).as("A → B → A shows A").containsExactly("1=a", "2=b", "3=c");
    }

    @Test
    void a_rerun_over_an_unchanged_source_touches_no_row() {
        ConnectorRun first = run();
        String before = snapshot();

        ConnectorRun again = run();

        assertThat(snapshot()).as("same ids, same run, same ingestion time").isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM connector.t_src_up WHERE _run_id = '" + first.getId() + "'")).isEqualTo(3);
        assertThat(again.getRecordsRemoved()).isZero();
    }

    @Test
    void a_table_written_before_1196_keeps_the_newest_row_per_key_and_reports_the_rest() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        legacyTable();
        // Key 1 in two versions, key 3 in three: rows no reader shows since #1185.
        legacyRow(1L, SOURCE, r1, 1, "a-old");
        legacyRow(1L, SOURCE, r2, 1, "a");
        legacyRow(1L, SOURCE, r1, 2, "b");
        legacyRow(1L, SOURCE, r1, 3, "c-old");
        legacyRow(1L, SOURCE, r2, 3, "c-older-than-r3");
        legacyRow(1L, SOURCE, r3, 3, "c");
        // Another source on the same table keeps every row it wrote.
        legacyRow(1L, "csv:other", r1, 1, "x");
        legacyRow(1L, "csv:other", r2, 1, "y");
        // So does another tenant's connector of the same name: its source may key its rows otherwise.
        legacyRow(2L, SOURCE, r1, 1, "t2-x");
        legacyRow(2L, SOURCE, r2, 1, "t2-y");

        ConnectorRun first = run();

        assertThat(first.getRecordsRemoved()).as("1 older version of key 1, 2 of key 3").isEqualTo(3);
        assertThat(rows()).containsExactly("1=a", "2=b", "3=c");
        assertThat(scalar("SELECT count(*) FROM connector.t_src_up WHERE _source = 'csv:other'")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM connector.t_src_up WHERE tenant_id = 2")).isEqualTo(2);
    }

    @Test
    void the_first_run_moves_the_table_even_when_it_reads_no_row() {
        // A watermark run after the upgrade usually reads nothing: its cursor is past every row.
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        legacyTable();
        legacyRow(1L, SOURCE, r1, 1, "a-old");
        legacyRow(1L, SOURCE, r2, 1, "a");
        sql("DELETE FROM public.src_up");

        ConnectorRun first = run();

        assertThat(first.getRecordsIn()).isZero();
        assertThat(first.getRecordsRemoved()).isEqualTo(1);
        assertThat(rows()).containsExactly("1=a");
    }

    @Test
    void a_key_that_one_run_read_twice_with_two_values_is_not_a_key_and_nothing_is_removed() {
        UUID r1 = UUID.randomUUID();
        legacyTable();
        legacyRow(1L, SOURCE, r1, 1, "line-1");
        legacyRow(1L, SOURCE, r1, 1, "line-2");

        AtomicReference<ChunkResult> result = new AtomicReference<>();
        ConnectorRun refused = run(result);

        assertThat(result.get()).isInstanceOf(ChunkResult.Failure.class);
        assertThat(((ChunkResult.Failure) result.get()).message())
            .contains("pk_columns").contains("src_up_id").contains("nothing was removed");
        assertThat(refused.getRecordsRemoved()).isZero();
        assertThat(scalar("SELECT count(*) FROM connector.t_src_up WHERE label LIKE 'line-%'")).isEqualTo(2);
    }

    @Test
    void a_read_that_brings_one_key_twice_with_two_values_is_refused() {
        sql("ALTER TABLE public.src_up DROP CONSTRAINT src_up_pkey");
        sql("INSERT INTO public.src_up VALUES (1, 'a-second-line')");

        AtomicReference<ChunkResult> result = new AtomicReference<>();
        run(result);

        assertThat(result.get()).isInstanceOf(ChunkResult.Failure.class);
        assertThat(((ChunkResult.Failure) result.get()).message()).contains("pk_columns").contains("src_up_id");
    }

    @Test
    void a_key_repeated_across_a_chunk_boundary_is_refused_though_the_paging_would_skip_its_second_row() {
        // #1215 review: rows 500 and 501 share key 500. The first chunk ends on one of them, and
        // resuming past key 500 would never read the other: no chunk holds both.
        sql("DROP TABLE public.src_up");
        sql("CREATE TABLE public.src_up (src_up_id bigint, label text)");
        sql("INSERT INTO public.src_up SELECT g, 'row-' || g FROM generate_series(1, 500) g");
        sql("INSERT INTO public.src_up VALUES (500, 'row-500-again')");
        sql("GRANT SELECT ON public.src_up TO " + DB_USER);

        AtomicReference<ChunkResult> result = new AtomicReference<>();
        run(result);

        assertThat(result.get()).isInstanceOf(ChunkResult.Failure.class);
        assertThat(((ChunkResult.Failure) result.get()).message()).contains("pk_columns (src_up_id)");
    }

    @Test
    void a_watermark_run_that_reads_one_key_in_two_chunks_with_two_values_is_refused() {
        // #1215 review: a watermark reader pages on (updated_at, key), so two rows of one key sit in
        // any two chunks; the upsert would keep the later and lose the other without a word.
        sql("DROP TABLE public.src_up");
        sql("CREATE TABLE public.src_up (src_up_id bigint, label text, updated_at timestamptz)");
        sql("INSERT INTO public.src_up SELECT g, 'row-' || g, '2026-01-01'::timestamptz + g * interval '1 minute' "
            + "FROM generate_series(1, 500) g");
        sql("INSERT INTO public.src_up VALUES (1, 'row-1-again', '2026-02-01')");
        sql("GRANT SELECT ON public.src_up TO " + DB_USER);
        endpointConfig = Map.of("schema", "public", "name", "src_up", "mode", "watermark",
            "watermark_column", "updated_at", "pk_columns", List.of("src_up_id"));

        AtomicReference<ChunkResult> result = new AtomicReference<>();
        run(result);

        assertThat(result.get()).isInstanceOf(ChunkResult.Failure.class);
        assertThat(((ChunkResult.Failure) result.get()).message()).contains("pk_columns (src_up_id)");
        assertThat(rows()).as("the first value of key 1 is not replaced by the second").contains("1=row-1");
    }

    // ── harness ────────────────────────────────────────────────────────────────

    private ConnectorRun run() {
        AtomicReference<ChunkResult> result = new AtomicReference<>();
        ConnectorRun r = run(result);
        assertThat(result.get()).as("%s", result.get()).isInstanceOf(ChunkResult.Success.class);
        return r;
    }

    /** One run of the endpoint: a fresh run row, every chunk to the end of the stream. */
    private ConnectorRun run(AtomicReference<ChunkResult> last) {
        UUID runId = TenantContext.callAsSystem(() -> inTx(() -> {
            if (connector == null) persistConnector();
            ConnectorRun run = new ConnectorRun();
            run.setTenantId(1L);
            run.setConnectorId(connector.getId());
            run.setEndpointId(endpoint.getId());
            run.setStatus("Running");
            run.setSyncMode("FullRefresh");
            run.setStartedAt(Instant.now());
            run.setTriggeredBy("MANUAL");
            runs.persist(run);
            runs.flush();
            return run.getId();
        }));
        ConnectorCursor cursor = ConnectorCursor.empty();
        while (true) {
            ConnectorCursor in = cursor;
            ChunkResult r = TenantContext.callAsSystem(() -> inTx(
                () -> exec.executeChunk(runs.findById(runId), registry.find("jdbc-generic"), endpoint, in, null)));
            last.set(r);
            if (!(r instanceof ChunkResult.Success s) || s.endOfStream()) break;
            cursor = s.nextCursor();
        }
        return TenantContext.callAsSystem(() -> inTx(() -> {
            ConnectorRun ended = runs.findById(runId);
            ended.setStatus("Completed");   // the endpoint takes one run in flight at a time
            ended.setEndedAt(Instant.now());
            return ended;
        }));
    }

    /** Commits what {@code work} did, or rolls it back when it throws — never commits a half-done step. */
    private <T> T inTx(Callable<T> work) throws Exception {
        utx.begin();
        try {
            T result = work.call();
            utx.commit();
            return result;
        } catch (Exception e) {
            utx.rollback();
            throw e;
        }
    }

    private void persistConnector() {
        connector = new Connector();
        connector.setTenantId(1L);
        connector.setName("jdbc-upsert-" + System.nanoTime());
        connector.setTypeId("jdbc-generic");
        connector.setConfig(Map.of("url", jdbcUrl, "user", DB_USER, "password", DB_PASSWORD, "dialect", "postgres"));
        connectors.persist(connector);
        connectors.flush();
        endpoint = new ConnectorEndpoint();
        endpoint.setTenantId(1L);
        endpoint.setConnectorId(connector.getId());
        endpoint.setName("src_up");
        endpoint.setMethod("GET");
        endpoint.setPath("/src_up");
        endpoint.setEnabled(true);
        endpoint.setConfig(endpointConfig);
        endpoints.persist(endpoint);
        endpoints.flush();
    }

    /** The table as #1198 left it: one row per content hash, no key. */
    private void legacyTable() {
        sql("CREATE TABLE connector.t_src_up (id BIGSERIAL PRIMARY KEY, _row_hash TEXT NOT NULL, "
            + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), _source TEXT, _run_id UUID, _mapping_version INT, "
            + "tenant_id BIGINT NOT NULL, src_up_id BIGINT, label TEXT, UNIQUE (tenant_id, _row_hash))");
    }

    private void legacyRow(long tenant, String source, UUID runId, long key, String label) {
        sql("INSERT INTO connector.t_src_up (_row_hash, _source, _run_id, _mapping_version, tenant_id, src_up_id, label) "
            + "VALUES ('" + UUID.randomUUID() + "', '" + source + "', '" + runId + "', 1, " + tenant + ", " + key
            + ", '" + label + "')");
    }

    /** This source's rows of tenant 1, as {@code key=label}, by key. */
    private List<String> rows() {
        List<String> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT src_up_id, label FROM connector.t_src_up "
                 + "WHERE tenant_id = 1 AND _source = '" + SOURCE + "' ORDER BY src_up_id, id")) {
            while (rs.next()) out.add(rs.getLong(1) + "=" + rs.getString(2));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    private String snapshot() {
        StringBuilder out = new StringBuilder();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, _run_id, _ingested_at FROM connector.t_src_up ORDER BY id")) {
            while (rs.next()) out.append(rs.getLong(1)).append(' ').append(rs.getString(2)).append(' ').append(rs.getString(3)).append('\n');
        } catch (Exception e) { throw new RuntimeException(e); }
        return out.toString();
    }

    private void sql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
