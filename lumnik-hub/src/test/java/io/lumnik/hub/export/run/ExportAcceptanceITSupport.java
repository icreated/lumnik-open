package io.lumnik.hub.export.run;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.rls.RlsEnforcedPgResource;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.hub.testsupport.GatedRelay;
import io.lumnik.hub.testsupport.StallProbe;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.BeforeEach;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembled-system harness of #683: {@link ExportRunITSupport}'s seeded entity and runner
 * seams, widened to what an adversarial proof needs — a second tenant, credentials resolved from
 * the tenant's own platform secret registry (never a JVM property), a destination reached through
 * a {@link GatedRelay} so a run can be held with its artifact half-sent, the DBA's hand to break
 * the snapshot read at that moment, and a sweep of every channel a secret could leave through.
 *
 * <p>Every subclass runs under {@code RlsEnforcedProfile}: the default datasource is the non-owner
 * {@code lumnik_app} and the snapshot is read as the genuine {@code lumnik_readonly}, so tenant
 * isolation is the database's, not a filter the test could have mocked — and the readonly
 * datasource is JTA-enlisted as in production, which is what makes the JDBC fetch size (and so
 * the bounded-memory claim) real.
 */
abstract class ExportAcceptanceITSupport extends ExportRunITSupport {

    static final long OTHER_TENANT = 2L;
    /** The platform-registry names the destination blocks reference; one set per transport. */
    static final String SECRET_NAME_PREFIX = "EXPORT_ACCEPT_683_";

    @Inject ExportScheduler scheduler;

    /** The relay every destination block of the subclass points at. */
    abstract GatedRelay relay();

    /** Registry secret name → value, registered for both tenants before each test. */
    abstract Map<String, String> secrets();

    /** Client→server bytes after which a held upload has already sent part of the artifact. */
    abstract long holdAfterBytes();

    /** Rows to add so a run is still reading its snapshot when the relay holds it. */
    abstract int rowsToOutlastTheHold();

    /** While held: the final artifact is not visible, and the temporary one already holds bytes. */
    abstract void assertInFlight(String target) throws Exception;

    /** Places an artifact lumnik did not write at {@code target}. */
    abstract void placeForeign(String target, byte[] bytes) throws Exception;

    @BeforeEach
    void registerSecrets() {
        for (long tenant : new long[] {TENANT, OTHER_TENANT})
            secrets().forEach((name, value) -> registerSecret(tenant, name, value));
    }

    /** Through the real {@code lm secret set} door; a secret already there from an earlier test
     *  is fine, its value is the same. */
    static void registerSecret(long tenant, String name, String value) {
        int status = given().headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"" + value + "\"}")
            .post("/api/platform/secrets")
            .statusCode();
        assertThat(status).as("register secret %s for tenant %d", name, tenant).isIn(201, 409);
    }

    // ---------------------------------------------------------------- manifests & runs

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Applies an export as {@code tenant} through {@code POST /api/exports/apply}. */
    void applyAs(long tenant, String name, String columns, String order, String target,
                 boolean wrongCredential, String specExtra) {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml").body(exportYaml(name, columns, order, target,
                wrongCredential, specExtra))
            .post("/api/exports/apply")
            .then().statusCode(200);
    }

    String exportYaml(String name, String columns, String order, String target,
                      boolean wrongCredential, String specExtra) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata:
              name: %s
            spec:
              scope: sales
              source:
                entity: %s
              columns: %s
              order: %s
              format:
                kind: csv
              destination:
            %s
              mode: snapshot
            %s
            """.formatted(name, entity(), columns, order, destination(target, wrongCredential),
                specExtra);
    }

    /** The common case: tenant 1, {@code [code, name]} ordered by code, correct credential. */
    String applyStandard(String target) {
        String name = unique("accept");
        applyAs(TENANT, name, "[code, name]", "[code]", target, false, "");
        return name;
    }

    void seedFor(long tenant, String values) {
        Jdbc.update(adminDs, "seed " + table(),
            "INSERT INTO connector." + table() + " (tenant_id, cust_id, cust_name) VALUES "
                + values.replace("('", "(" + tenant + ", '"),
            ps -> {});
    }

    /** {@code rows} more tenant-1 rows, sorted after the three seeded ones, ~80 CSV bytes each. */
    void bulk(int rows) {
        Jdbc.update(adminDs, "bulk " + table(),
            "INSERT INTO connector." + table() + " (tenant_id, cust_id, cust_name) "
          + "SELECT ?, 'r' || lpad(g::text, 8, '0'), repeat('n', 60) || g "
          + "FROM generate_series(1, ?) AS g",
            ps -> {
                ps.setLong(1, TENANT);
                ps.setInt(2, rows);
            });
    }

    String table() {
        return "t_" + entity().toLowerCase(Locale.ROOT) + "_erp";
    }

    String view() {
        return "v_" + entity().toLowerCase(Locale.ROOT);
    }

    ExportRunner.Launch launchAs(long tenant, String name, Instant at) {
        return runner.launch(new ExportRunRequest(tenant, name, ExportTrigger.MANUAL, 7L, null, at, 1,
            null));
    }

    /** Awaits a started launch of {@code tenant}'s export and reads its ledger row back. */
    ExportRunView awaitAs(long tenant, String name, ExportRunner.Launch launch) throws Exception {
        assertThat(launch.result()).isInstanceOf(StartResult.Started.class);
        StallProbe.diagnosing("export worker " + name, this::stallEvidence,
            () -> launch.worker().get(120, TimeUnit.SECONDS));
        UUID runId = ((StartResult.Started) launch.result()).runId();
        return find(tenant, name, runId).orElseThrow();
    }

    Optional<ExportRunView> find(long tenant, String name, UUID runId) {
        return TenantScope.get(tenant, () -> QuarkusTransaction.requiringNew()
            .call(() -> ledger.find(tenant, name, runId)));
    }

    ExportRunView retry(String name, ExportRunView failed) throws Exception {
        return await(name, runner.launch(new ExportRunRequest(TENANT, name, ExportTrigger.MANUAL, 7L,
            null, AT, 2, failed.id()), row(name)));
    }

    /** This export's rows, once none is Running any more (a scheduled start returns no Future). */
    List<String[]> settled(String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (true) {
            List<String[]> rows = Jdbc.query(adminDs, "settled " + name,
                "SELECT status, failure_code, trigger, triggered_by, scope, tenant_id "
              + "FROM connector.export_run WHERE export_name = ?",
                ps -> ps.setString(1, name),
                rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6)});
            if (rows.stream().noneMatch(r -> r[0].equals(ExportRunStatus.RUNNING.sqlValue())))
                return rows;
            if (System.nanoTime() > deadline) throw new AssertionError("run still Running: " + name);
            Thread.sleep(100);
        }
    }

    // ---------------------------------------------------------------- fault injection

    /** Holds the next upload once part of the artifact has left, as the subclass sizes it. */
    void holdMidUpload() {
        relay().holdAfter(holdAfterBytes());
    }

    /**
     * What the destination side can say when a wait on it gives up. The relay's own state by
     * default: a pump stuck writing points at the server, one stuck reading at the hub (#761).
     */
    String stallEvidence() {
        return relay().describe();
    }

    void awaitHeld() throws Exception {
        StallProbe.diagnosing("the relay's hold", this::stallEvidence, () -> {
            assertThat(relay().awaitHeld(Duration.ofSeconds(120)))
                .as("the upload reached the relay's hold").isTrue();
            return null;
        });
    }

    /**
     * Kills the backend serving this entity's snapshot read, as a DBA (or a crashed database)
     * would: the run's next fetch fails, mid-stream. Asserts exactly one such backend existed —
     * the read was still open, which is itself the proof the rows were not all pulled at once.
     */
    void terminateSnapshotRead() throws Exception {
        String url = ConfigProvider.getConfig()
            .getValue("quarkus.datasource.\"admin\".jdbc.url", String.class);
        try (Connection c = DriverManager.getConnection(url, RlsEnforcedPgResource.SUPERUSER,
                RlsEnforcedPgResource.SUPERUSER_PASSWORD)) {
            List<Boolean> terminated = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                   + "WHERE usename = 'lumnik_readonly' AND pid <> pg_backend_pid() "
                   + "AND query LIKE '%' || ? || '%'")) {
                ps.setString(1, view());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) terminated.add(rs.getBoolean(1));
                }
            }
            List<String> sessions = new ArrayList<>();
            if (terminated.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                         "SELECT usename || ' ' || coalesce(state, '') || ' ' || coalesce(query, '') "
                       + "FROM pg_stat_activity WHERE pid <> pg_backend_pid()");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) sessions.add(rs.getString(1));
                }
            }
            assertThat(terminated).as("the open snapshot read of %s among %s", view(), sessions)
                .containsExactly(true);
        }
    }

    /** Takes {@code name} off the schedule, whatever its stored definition still says. */
    void unschedule(String name) {
        Jdbc.update(adminDs, "unschedule " + name,
            "UPDATE connector.export_manifest SET schedule = NULL WHERE name = ?",
            ps -> ps.setString(1, name));
    }

    // ---------------------------------------------------------------- observation

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    List<String> egress(String name) {
        return audit(name).stream().filter(r -> r.startsWith("EGRESS ")).toList();
    }

    /**
     * Every place a credential could surface after a run, as text: the export's ledger rows whole,
     * every audit row of every tenant (outbox and dispatched), and each integrator read door's
     * body — the export, the run list, and each run's detail.
     */
    List<String> everyChannel(String name) {
        List<String> seen = new ArrayList<>();
        seen.addAll(Jdbc.query(adminDs, "ledger " + name,
            "SELECT row_to_json(r)::text FROM connector.export_run r WHERE export_name = ?",
            ps -> ps.setString(1, name), rs -> rs.getString(1)));
        seen.addAll(Jdbc.query(adminDs, "outbox",
            "SELECT row_to_json(a)::text FROM core.audit_outbox a", ps -> {}, rs -> rs.getString(1)));
        seen.addAll(Jdbc.query(adminDs, "events",
            "SELECT row_to_json(a)::text FROM core.audit_event a", ps -> {}, rs -> rs.getString(1)));
        seen.add(get("/api/exports/" + name));
        String runs = get("/api/exports/" + name + "/runs");
        seen.add(runs);
        for (String id : Jdbc.query(adminDs, "runIds " + name,
                "SELECT id::text FROM connector.export_run WHERE export_name = ?",
                ps -> ps.setString(1, name), rs -> rs.getString(1)))
            seen.add(get("/api/exports/" + name + "/runs/" + id));
        return seen;
    }

    private static String get(String path) {
        return given().headers(TestIdentity.tenantAndUser(TENANT)).get(path)
            .then().statusCode(200).extract().asString();
    }

    /** What the whole JVM logged while {@code action} ran: each message and its cause chain. */
    static List<String> logged(ThrowingRunnable action) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        Logger root = Logger.getLogger("");
        Handler probe = new Handler() {
            @Override public void publish(LogRecord r) {
                StringBuilder line = new StringBuilder(
                    r instanceof ExtLogRecord ext ? ext.getFormattedMessage() : r.getMessage());
                for (Throwable t = r.getThrown(); t != null; t = t.getCause())
                    line.append(" | ").append(t);
                lines.add(line.toString());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        root.addHandler(probe);
        try {
            action.run();
        } finally {
            root.removeHandler(probe);
        }
        return new ArrayList<>(lines);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
