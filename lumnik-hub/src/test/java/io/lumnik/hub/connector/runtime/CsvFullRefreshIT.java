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
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1195: a local csv connector reading ONE plain file holds the source's state, so a run that read
 * the whole file and succeeded removes the rows the file no longer holds — guarded. A drop folder (a
 * glob) and a file the run did not re-read never remove anything.
 */
@QuarkusTest
class CsvFullRefreshIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private List<String> codes(String table) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT code || '=' || label FROM connector." + table
                 + " WHERE tenant_id = 1 ORDER BY code, id")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID seed(String path, String table, Map<String, Object> refresh) {
        return seed(path, table, refresh, Map.of());
    }

    private UUID seed(String path, String table, Map<String, Object> refresh, Map<String, Object> extra) {
        Map<String, Object> transport = Map.of("kind", "local", "path", path);
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("refresh-" + System.nanoTime()); c.setTypeId("csv-file");
                Map<String, Object> config = new HashMap<>(Map.of(
                    "kind", "csv-file", "transport", transport, "parser", Map.of("has_header", true)));
                if (refresh != null) config.put("refresh", refresh);
                config.putAll(extra);
                c.setConfig(config);
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath(path); ep.setEnabled(true);
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

    private static int stamp = 0;

    /** Writes the file and moves its modification time forward, so the reader sees a new version. */
    private static void write(Path file, String... rows) throws Exception {
        Files.writeString(file, "code,label\n" + String.join("\n", rows) + (rows.length > 0 ? "\n" : ""));
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(++stamp)));
    }

    private RunStatus run(UUID id) {
        return engine.runSync(id, new RunTrigger.Manual(1L));
    }

    private static List<String> rows(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(String.format("C%02d,v", i));
        return out;
    }

    /** records_in and files_unchanged of the connector's latest run. */
    private long[] latestRun(UUID id) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT records_in, files_unchanged FROM connector.connector_run "
                 + "WHERE connector_id = '" + id + "' ORDER BY ended_at DESC NULLS LAST, started_at DESC LIMIT 1")) {
            rs.next();
            return new long[] {rs.getLong(1), rs.getLong(2)};
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** records_removed of the connector's latest run: what `lm run get` reports as removed. */
    private long removed(UUID id) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT records_removed FROM connector.connector_run "
                 + "WHERE connector_id = '" + id + "' ORDER BY ended_at DESC NULLS LAST, started_at DESC LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** #1371: a copy that keeps the mtime (docker cp, cp -p, rsync -t) and the size — the bytes decide. */
    @Test
    void a_file_replaced_with_the_same_size_and_mtime_is_read_again(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        FileTime kept = FileTime.from(Instant.parse("2026-02-01T00:00:00Z"));
        Files.writeString(file, "code,label\nA1,x\n");
        Files.setLastModifiedTime(file, kept);
        String table = "refresh_same_stat_" + Long.toHexString(System.nanoTime());
        UUID id = seed(file.toString(), table, null);
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        Files.writeString(file, "code,label\nA1,y\n");
        Files.setLastModifiedTime(file, kept);
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(latestRun(id)[0]).isEqualTo(1);
        assertThat(codes("t_" + table)).containsExactly("A1=y");
    }

    /** #1371: a run that reads nothing because the file did not change says so. */
    @Test
    void an_unchanged_file_is_counted_on_the_run_that_skipped_it(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(3).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_counted_" + Long.toHexString(System.nanoTime()), null);
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(latestRun(id)).containsExactly(3, 0);

        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(latestRun(id)).containsExactly(0, 1);
    }

    @Test
    void a_row_removed_from_the_file_leaves_the_table(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_removed", null);
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        List<String> nine = rows(10).subList(0, 9);
        write(file, nine.toArray(String[]::new));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(codes("t_refresh_removed")).hasSize(9).doesNotContain("C09=v");
        assertThat(removed(id)).isEqualTo(1);
    }

    @Test
    void a_changed_value_is_not_reported_as_a_removed_row(@TempDir Path dir) throws Exception {
        // #1544: a changed row is one version met and one unmet. The old version goes, but the source
        // still holds the row: the run must not say it lost one.
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_one_changed", null);
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        List<String> changed = new ArrayList<>(rows(10));
        changed.set(3, "C03,w");
        write(file, changed.toArray(String[]::new));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(codes("t_refresh_one_changed")).hasSize(10).contains("C03=w").doesNotContain("C03=v");
        assertThat(removed(id)).isZero();
    }

    @Test
    void a_row_lost_beside_a_changed_one_counts_once(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_lost_and_changed", null);
        run(id);

        List<String> next = new ArrayList<>(rows(10).subList(0, 9));
        next.set(3, "C03,w");
        write(file, next.toArray(String[]::new));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(codes("t_refresh_lost_and_changed")).hasSize(9).contains("C03=w").doesNotContain("C09=v");
        assertThat(removed(id)).isEqualTo(1);
    }

    @Test
    void a_row_another_connector_landed_in_the_table_is_never_removed(@TempDir Path dir) throws Exception {
        // #1199 tolerates a sharing that predates the refusal of a second writer: its rows are not
        // this file's to remove, nor to count against the threshold
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_shared", null);
        run(id);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector.t_refresh_shared (_row_hash, _source, tenant_id, code, label) "
                + "VALUES ('legacy-other', 'jdbc:other', 1, 'X99', 'kept')");
        }

        write(file, rows(10).subList(0, 9).toArray(String[]::new));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(codes("t_refresh_shared")).hasSize(10).contains("X99=kept").doesNotContain("C09=v");
    }

    @Test
    void a_value_that_reverts_shows_its_reverted_value(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        List<String> base = rows(9);
        List<String> a = new ArrayList<>(base); a.add("K,A");
        List<String> b = new ArrayList<>(base); b.add("K,B");
        write(file, a.toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_revert", null);
        run(id);
        write(file, b.toArray(String[]::new));
        run(id);
        write(file, a.toArray(String[]::new));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(codes("t_refresh_revert")).contains("K=A").doesNotContain("K=B").hasSize(10);
    }

    @Test
    void a_refresh_that_changes_every_value_is_not_a_removal(@TempDir Path dir) throws Exception {
        // #1195 review: every row of a new export can differ (a date the export stamps on each
        // line). The table does not shrink, so the guard has nothing to say: the old versions go.
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_churn", null);
        run(id);

        write(file, rows(10).stream().map(r -> r.replace(",v", ",w")).toArray(String[]::new));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        assertThat(codes("t_refresh_churn")).hasSize(10).allMatch(c -> c.endsWith("=w"));
        assertThat(removed(id)).isZero();
    }

    @Test
    void a_run_with_rows_it_could_not_read_removes_nothing(@TempDir Path dir) throws Exception {
        // #1195 review: a row dead-lettered this run is still in the file; its last good version must
        // not be removed because the parser could not read the new one.
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_dlq", null);
        run(id);

        List<String> broken = new ArrayList<>(rows(10).subList(0, 9));
        broken.add("C09,v,unexpected,extra");
        write(file, broken.toArray(String[]::new));
        RunStatus refused = run(id);

        assertThat(refused).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) refused).message()).contains("dead letter").contains("nothing was removed");
        assertThat(codes("t_refresh_dlq")).hasSize(10).contains("C09=v");
    }

    @Test
    void a_row_validate_row_drops_removes_nothing_and_the_message_says_so(@TempDir Path dir) throws Exception {
        // #1253: a declared validation drop is not an unreadable row, but it is still a row of the file
        // whose last good version must not go; the refusal names it and the gesture.
        Path file = dir.resolve("ref.csv");
        write(file, "C1,Acme Corp", "C2,");
        UUID id = seed(file.toString(), "refresh_validate", null, Map.of("mapping", Map.of("row",
            List.of(Map.of("validate-row", Map.of("required", List.of("code", "label")))))));
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);

        write(file, "C1,Acme SA", "C2,");
        RunStatus refused = run(id);

        assertThat(refused).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) refused).message()).endsWith("): a full refresh removes"
            + " nothing while a row of the file is in the dead letters, whatever its reason (unreadable, dropped"
            + " by validate-row…) — nothing was removed. Fix the cause each dead letter reports"
            + " (lm dlq get <id>) and run again.");
        assertThat(codes("t_refresh_validate")).containsExactly("C1=Acme Corp", "C1=Acme SA");
    }

    @Test
    void an_empty_file_fails_the_run_and_keeps_the_table(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(5).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_empty", null);
        run(id);

        write(file);
        RunStatus second = run(id);
        assertThat(second).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) second).message()).contains("empty").contains("nothing was removed");
        assertThat(codes("t_refresh_empty")).hasSize(5);
    }

    @Test
    void an_empty_file_on_a_table_older_than_the_refresh_is_refused_not_an_sql_error(@TempDir Path dir) throws Exception {
        // #1214 review: a table created before #1195 gets _seen_run from the first chunk that writes a
        // row; an empty first refresh writes none, and the guard must still speak — not the database.
        Path file = dir.resolve("ref.csv");
        write(file, rows(5).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_legacy_empty", null);
        run(id);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE connector.t_refresh_legacy_empty DROP COLUMN _seen_run");
        }

        write(file);
        RunStatus second = run(id);

        assertThat(second).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) second).message()).contains("empty").doesNotContain("does not exist");
        assertThat(codes("t_refresh_legacy_empty")).hasSize(5);
    }

    @Test
    void a_drop_beyond_the_default_threshold_fails_and_removes_nothing_until_raised(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_threshold", null);
        run(id);

        write(file, rows(10).subList(0, 6).toArray(String[]::new));   // 4 of 10 = 40% > 30%
        RunStatus refused = run(id);
        assertThat(refused).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) refused).message())
            .contains("4 of 10 rows").contains("30%").contains("nothing was removed");
        assertThat(codes("t_refresh_threshold")).hasSize(10);

        // The integrator raises the threshold and runs again: the same, unchanged file is re-read
        // in full — a refused run must not leave a position that skips it.
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                Connector c = connectors.findById(id);
                Map<String, Object> config = new HashMap<>(c.getConfig());
                config.put("refresh", Map.of("max_removed", "50%"));
                c.setConfig(config);
                utx.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(codes("t_refresh_threshold")).hasSize(6);
    }

    @Test
    void the_threshold_can_be_switched_off(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(10).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_off", Map.of("max_removed", "off"));
        run(id);
        write(file, "C00,v");
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(codes("t_refresh_off")).containsExactly("C00=v");
    }

    @Test
    void an_unchanged_file_is_not_re_read_and_removes_nothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ref.csv");
        write(file, rows(5).toArray(String[]::new));
        UUID id = seed(file.toString(), "refresh_unchanged", null);
        run(id);
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(codes("t_refresh_unchanged")).hasSize(5);
    }

    @Test
    void a_drop_folder_never_removes_rows(@TempDir Path dir) throws Exception {
        write(dir.resolve("2026-01-01.csv"), rows(5).toArray(String[]::new));
        UUID id = seed(dir.resolve("*.csv").toString(), "refresh_glob", null);
        run(id);
        write(dir.resolve("2026-01-02.csv"), "Z,new");
        assertThat(run(id)).isInstanceOf(RunStatus.Completed.class);
        assertThat(codes("t_refresh_glob")).hasSize(6);
    }
}
