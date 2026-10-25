package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CsvSftpConnectorIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorDeadLetterRepository dlqRepo;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    static GenericContainer<?> sftp;
    static final String USER = "csvuser";
    static final String PASS = "csvpass";

    @BeforeAll
    static void start() throws Exception {
        sftp = new GenericContainer<>(SftpTestImage.PINNED).withExposedPorts(22)
            .withCommand(USER + ":" + PASS + ":::upload")
            // Wait for sshd to LISTEN, not merely for the published port to accept: the
            // docker-proxy binds that port at container start, while the entrypoint is still
            // generating host keys — a connection landing in that window is accepted and then
            // dropped, which is what a bare withExposedPorts(22) races against.
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
        sftp.start();
        sftp.copyFileToContainer(MountableFile.forClasspathResource("/csv/customers-simple.csv"),
            "/home/" + USER + "/upload/customers.csv");
        System.setProperty("C1B_SFTP_PASSWORD", PASS);   // SftpFileSourceFactory: getProperty → getenv

        // Seed incremental test: first file for c1b_sftp_inc connector (customers-simple.csv = 3 rows).
        sftp.copyFileToContainer(MountableFile.forClasspathResource("/csv/customers-simple.csv"),
            "/home/" + USER + "/upload/inc/a.csv");

        // Seed move test: one file under /upload/mv/.
        sftp.copyFileToContainer(MountableFile.forClasspathResource("/csv/customers-simple.csv"),
            "/home/" + USER + "/upload/mv/move.csv");

        // Create empty dir for the no-files test.
        sftp.execInContainer("mkdir", "-p", "/home/" + USER + "/upload/empty");

        // Pre-create the archive destination so SFTP rename doesn't fail on a missing parent dir.
        sftp.execInContainer("mkdir", "-p", "/home/" + USER + "/upload/archive");

        // Fix ownership: copyFileToContainer and mkdir run as root inside the container;
        // SFTP rename/mkdir fails with SSH_FX_PERMISSION_DENIED unless the SFTP user owns the files.
        sftp.execInContainer("chown", "-R", USER, "/home/" + USER + "/upload");
    }

    @AfterAll
    static void stop() {
        System.clearProperty("C1B_SFTP_PASSWORD");
        if (sftp != null) sftp.stop();
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private String scalar2(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getString(1); return null;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    /** Returns 0 if the table does not yet exist. */
    private long countOrZero(String table) {
        // Two-step check: first test for table existence via pg_class, then count.
        // A single CASE WHEN to_regclass(...) ... FROM connector.t_xxx fails at parse time if the
        // table does not exist — PostgreSQL plans all referenced tables eagerly.
        try (Connection c = adminDs.getConnection()) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT to_regclass('connector." + table + "')")) {
                rs.next();
                if (rs.getString(1) == null) return 0L;
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM connector." + table)) {
                rs.next(); return rs.getLong(1);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------------------
    // Happy-path SFTP run
    // -------------------------------------------------------------------------

    @Test
    void sftp_connector_run_ingests_remote_csv() {
        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus s = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(s).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp")).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Incremental cursor round-trip
    // Proves: second run reads ONLY the new file, not the already-processed one.
    // customers-simple.csv = 3 data rows; customers-semicolon.csv = 2 data rows.
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void sftp_incremental_cursor_reads_only_new_file() throws Exception {
        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/inc/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-inc-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/inc/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp_inc"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        // First run: one file (a.csv = customers-simple.csv = 3 rows).
        RunStatus run1 = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(run1).isInstanceOf(RunStatus.Completed.class);
        long afterRun1 = scalar("SELECT count(*) FROM connector.t_c1b_sftp_inc");
        assertThat(afterRun1).isEqualTo(3L);

        // Verify cursor was persisted after run1 (watermark should reference a.csv's mtime).
        String cursorJson = scalar2("SELECT cursor_value::text FROM connector.connector_cursor" +
            " WHERE connector_id = '" + id + "'");
        assertThat(cursorJson)
            .as("cursor must be persisted after run1")
            .isNotNull().contains("watermark");

        // Add second file AFTER the first run completes — guarantees a real mtime gap.
        // Write inline via exec so the container assigns the current time as mtime (MountableFile
        // preserves the classpath resource's mtime, which could equal a.csv's mtime and be excluded
        // by listSince's strict-after watermark check).
        // Use printf to emit proper newlines (alpine sh echo does not interpret \n).
        // b.csv must have rows NOT in a.csv: the writer uses OnConflict.SKIP (hash dedup), so
        // duplicate content rows are silently dropped and the delta would be 0.
        sftp.execInContainer("sh", "-c",
            "printf 'email,name,country\\ndave@example.com,Dave,IT\\neve@example.com,Eve,ES\\n'" +
            " > /home/" + USER + "/upload/inc/b.csv");

        // Second run: cursor skips a.csv, reads only b.csv.
        RunStatus run2 = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(run2).isInstanceOf(RunStatus.Completed.class);
        long afterRun2 = scalar("SELECT count(*) FROM connector.t_c1b_sftp_inc");
        long delta = afterRun2 - afterRun1;
        // KEY assertion: exactly 2 new rows (b.csv), proving the cursor excluded a.csv.
        assertThat(delta)
            .as("delta must equal b.csv row count (2), not 0 (cursor re-skip) nor 5 (cursor ignored)")
            .isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // after_process=MOVE
    // Source file disappears from /upload/mv/ after the run.
    // Archive path /upload/archive is within the writable /upload tree.
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    void sftp_after_process_move_relocates_file() throws Exception {
        // MANIFEST shape: after_process + archive_path live at the connector TOP LEVEL (siblings of
        // transport) — exactly how CsvFileConnectorApplyHandler stores them from YAML. (A transport-
        // nested copy would mask the real wiring.)
        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/mv/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-mv-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true),
                    "after_process", "move",          // top-level, like the manifest
                    "archive_path", "/upload/archive"));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/mv/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp_mv"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus run1 = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(run1).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_mv")).isGreaterThan(0L);

        // Direct proof the file was MOVED (not the dedup-masked "no new rows"): the source is gone
        // from /upload/mv and present under /upload/archive.
        var srcGone = sftp.execInContainer("sh", "-c", "test ! -e /home/" + USER + "/upload/mv/move.csv && echo GONE || echo PRESENT");
        assertThat(srcGone.getStdout().trim()).as("source file removed from /upload/mv after MOVE").isEqualTo("GONE");
        // Archived under its name and its mtime (#1382): a name that comes back every night never collides.
        var archived = sftp.execInContainer("sh", "-c", "ls /home/" + USER + "/upload/archive/move.*Z.csv >/dev/null 2>&1 && echo YES || echo NO");
        assertThat(archived.getStdout().trim()).as("source file relocated under /upload/archive").isEqualTo("YES");
    }

    // -------------------------------------------------------------------------
    // No files / empty dir
    // Proves: an empty source dir yields Completed (EndOfStream), not an exception.
    // -------------------------------------------------------------------------

    @Test
    @Order(30)
    void sftp_empty_dir_completes_cleanly() {
        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/empty/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-empty-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/empty/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp_empty"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        assertThat(countOrZero("t_c1b_sftp_empty")).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Same-watermark second: two files sharing an identical mtime must BOTH be
    // ingested. The watermark+lastBatch cursor only works if listSince() is
    // INCLUSIVE at the boundary (>= cursor); a strict isAfter would advance the
    // watermark past the first file's second and permanently skip its sibling.
    // -------------------------------------------------------------------------

    @Test
    @Order(40)
    void sftp_two_files_same_mtime_both_ingested() throws Exception {
        // Two single-row files under /upload/eq, forced to the SAME mtime.
        sftp.execInContainer("sh", "-c", "mkdir -p /home/" + USER + "/upload/eq && "
            + "printf 'row_id,email\\n1,a@x\\n' > /home/" + USER + "/upload/eq/a.csv && "
            + "printf 'row_id,email\\n2,b@x\\n' > /home/" + USER + "/upload/eq/b.csv && "
            + "touch -d '2021-01-01 00:00:00' /home/" + USER + "/upload/eq/a.csv /home/" + USER + "/upload/eq/b.csv");

        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/eq/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-eq-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/eq/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp_eq"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        // BOTH files (1 row each) must land in a single run — proves inclusive listSince + dedup.
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_eq")).isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // #1381: a file rewritten under the same name with the SAME size and a newer mtime is a new
    // version — an sftp file has no etag, so only its mtime tells it apart. It was taken as
    // already read: the run ended Completed with IN 0.
    // -------------------------------------------------------------------------

    @Test
    @Order(45)
    void sftp_same_name_same_size_rewritten_later_is_read_again() throws Exception {
        String dir = "/home/" + USER + "/upload/rw";
        sftp.execInContainer("sh", "-c", "mkdir -p " + dir + " && printf 'row_id,email\\n1,a@x\\n' > " + dir + "/day.csv"
            + " && touch -d '2021-01-01 00:00:00' " + dir + "/day.csv");

        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/rw/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-rw-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/rw/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp_rw"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_rw")).isEqualTo(1L);

        // The next day's export: the same name, the same byte count, another row.
        sftp.execInContainer("sh", "-c", "printf 'row_id,email\\n2,b@x\\n' > " + dir + "/day.csv"
            + " && touch -d '2021-01-02 00:00:00' " + dir + "/day.csv");

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_rw"))
            .as("the rewritten file is read again, not taken as already processed").isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // #1382: a carrier drops the same name every night. Each night is archived under its own name,
    // and a file that cannot be archived (here: an archive folder the user cannot write) is said on
    // the run instead of a WARN in the hub's log only.
    // -------------------------------------------------------------------------

    @Test
    @Order(46)
    void sftp_the_same_name_every_night_is_archived_each_time_and_a_failed_archive_is_said() throws Exception {
        String home = "/home/" + USER + "/upload";
        sftp.execInContainer("sh", "-c", "mkdir -p " + home + "/night " + home + "/night-archive && "
            + "chown " + USER + " " + home + "/night " + home + "/night-archive && "
            + "printf 'row_id,email\\n1,a@x\\n' > " + home + "/night/day.csv && touch -d '2021-01-01 00:00:00' " + home + "/night/day.csv");

        UUID id = moveConnector("night", "/upload/night-archive", "c1b_sftp_night");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);

        sftp.execInContainer("sh", "-c", "printf 'row_id,email\\n2,b@x\\n3,c@x\\n' > " + home + "/night/day.csv"
            + " && touch -d '2021-01-02 00:00:00' " + home + "/night/day.csv");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);

        assertThat(sftp.execInContainer("sh", "-c", "ls " + home + "/night-archive | wc -l").getStdout().trim())
            .as("both nights archived, each under its own name").isEqualTo("2");
        assertThat(sftp.execInContainer("sh", "-c", "ls " + home + "/night | wc -l").getStdout().trim())
            .as("the drop folder is empty").isEqualTo("0");
        assertThat(lastRun(id, "files_not_archived")).isZero();

        // The archive folder becomes read-only: the next night is read, stays, and the run says so.
        sftp.execInContainer("sh", "-c", "chown root " + home + "/night-archive && chmod 555 " + home + "/night-archive && "
            + "printf 'row_id,email\\n4,d@x\\n' > " + home + "/night/day.csv && touch -d '2021-01-03 00:00:00' " + home + "/night/day.csv");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_night")).isEqualTo(4L);
        assertThat(lastRun(id, "files_not_archived")).as("the file left in the drop folder is said").isEqualTo(1L);
    }

    // #1385: a night whose archive failed stays "to archive" in the cursor; the next night dropped under
    // the same name must be read — the archive sweep moved it by path, unread, and the run read nothing.
    @Test
    @Order(47)
    void sftp_a_new_file_under_the_name_of_one_not_archived_is_read_not_archived_away() throws Exception {
        String home = "/home/" + USER + "/upload";
        sftp.execInContainer("sh", "-c", "mkdir -p " + home + "/again " + home + "/again-archive && "
            + "chown " + USER + " " + home + "/again " + home + "/again-archive && "
            + "printf 'row_id,email\\n1,a@x\\n' > " + home + "/again/day.csv && touch -d '2021-02-01 00:00:00' " + home + "/again/day.csv");
        UUID id = moveConnector("again", "/upload/again-archive", "c1b_sftp_again");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);

        // Night 2 is read, but the archive folder is read-only: it stays, "to archive".
        sftp.execInContainer("sh", "-c", "chown root " + home + "/again-archive && chmod 555 " + home + "/again-archive && "
            + "printf 'row_id,email\\n2,b@x\\n' > " + home + "/again/day.csv && touch -d '2021-02-02 00:00:00' " + home + "/again/day.csv");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(lastRun(id, "files_not_archived")).isEqualTo(1L);

        // The carrier fixes its folder and drops night 3 under the same name.
        sftp.execInContainer("sh", "-c", "chown " + USER + " " + home + "/again-archive && chmod 755 " + home + "/again-archive && "
            + "printf 'row_id,email\\n3,c@x\\n30,cc@x\\n' > " + home + "/again/day.csv && touch -d '2021-02-03 00:00:00' " + home + "/again/day.csv");
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);

        assertThat(lastRun(id, "records_in")).as("night 3 is read, not archived away unread").isEqualTo(2L);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_again")).isEqualTo(4L);
        assertThat(sftp.execInContainer("sh", "-c", "ls " + home + "/again-archive").getStdout())
            .as("night 1 and night 3 archived under their own names").contains("day.20210201T000000Z.csv", "day.20210203T000000Z.csv");
        assertThat(sftp.execInContainer("sh", "-c", "ls " + home + "/again | wc -l").getStdout().trim()).isEqualTo("0");
    }

    private UUID moveConnector(String dir, String archive, String table) {
        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/" + dir + "/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-" + dir + "-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport, "parser", Map.of("has_header", true),
                    "after_process", "move", "archive_path", archive));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/" + dir + "/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private long lastRun(UUID connector, String column) {
        return scalar("SELECT " + column + " FROM connector.connector_run WHERE connector_id = '" + connector
            + "' ORDER BY started_at DESC LIMIT 1");
    }

    // -------------------------------------------------------------------------
    // Malformed row DLQ parity for SFTP transport
    // Proves: a CSV with one short row dead-letters the bad row while good rows land.
    // -------------------------------------------------------------------------

    @Test
    @Order(50)
    void sftp_malformed_row_dead_lettered_good_rows_land() throws Exception {
        // Drop table so reruns are clean.
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector.t_sftp_dlq");
        }

        // Upload a CSV with one short row (wrong column count) via exec.
        // printf interprets \n as newlines in alpine sh.
        sftp.execInContainer("sh", "-c",
            "mkdir -p /home/" + USER + "/upload/dlq && "
            + "printf 'row_id,email\\n1,a@x.com\\nBROKEN_ONE_FIELD\\n3,c@x.com\\n'"
            + " > /home/" + USER + "/upload/dlq/malformed.csv && "
            + "chown -R " + USER + " /home/" + USER + "/upload/dlq");

        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/dlq/*.csv", "password_env", "C1B_SFTP_PASSWORD",
            "strict_host_key", false);

        UUID connectorId = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-dlq-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/dlq/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "sftp_dlq"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(status).isNotInstanceOf(RunStatus.Failed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_sftp_dlq")).isEqualTo(2L);
        var pending = TenantContext.callAsSystem(() -> dlqRepo.findPendingByConnector(connectorId, 10));
        assertThat(pending).isNotEmpty();
        assertThat(pending.get(0).getSkipReason()).isEqualTo("MalformedSource");
        // The DLQ entry must reference the REMOTE source path, not the deleted local temp download.
        assertThat(pending.get(0).getErrorMessage())
            .as("DLQ must point to the remote source file, not the ephemeral /tmp download")
            .contains("malformed.csv")
            .doesNotContain("csv_conn_remote_");
    }
}
