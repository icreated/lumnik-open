package io.lumnik.hub.export.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.export.EntitySnapshotStreamer;
import io.lumnik.hub.export.ExportManifest;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.export.SnapshotStats;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.StallProbe;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport-independent half of the export-run ITs: a seeded entity, an applied manifest, a
 * run driven through {@link ExportRunner} and awaited on its worker's Future, then the ledger row
 * and the audit outbox read back. Each transport subclass supplies its destination block and how
 * to read the remote side; the scenarios themselves are written once, in
 * {@link ExportRunScenarios}.
 */
abstract class ExportRunITSupport {

    static final long TENANT = 1L;
    static final String SECRET_ENV_PREFIX = "EXPORT_RUN_IT_";

    @Inject ExportRunner runner;
    @Inject ExportRunLedger ledger;
    @Inject ExportManifestStore store;
    @Inject EntityRegistry registry;
    @Inject EntitySnapshotStreamer streamer;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

    /** The entity's name; its source table and view are derived from it. */
    abstract String entity();

    /** The {@code spec.destination} block, indented for the manifest, at {@code target}. */
    abstract String destination(String target, boolean wrongCredential);

    /** The final artifact's bytes, or null when nothing was published there. */
    abstract byte[] remote(String target) throws Exception;

    /** The receipt's location for {@code target}. */
    abstract String location(String target);

    /** No temporary artifact (.part, open multipart upload) is left for {@code target}. */
    abstract void assertNoLeftover(String target) throws Exception;

    /** A transport-specific receipt check (S3's etag). */
    void assertReceipt(ExportRunView run) {}

    /** The known secret a wrong-credential destination resolves to. */
    static final String WRONG_SECRET = "wrong-credential-canary";

    private String table() {
        return "t_" + entity().toLowerCase(Locale.ROOT) + "_erp";
    }

    private String entityYaml(String scope) {
        return """
            kind: Entity
            name: %s
            match: [code]
            scopes: [%s]
            sources:
              - table: %s
                map:
                  cust_id: code
                  cust_name: name
            """.formatted(entity(), scope, table());
    }

    @BeforeEach
    void seedEntity() throws Exception {
        EntitySourceTableFixture.create(adminDs, table(), "v_" + entity().toLowerCase(Locale.ROOT),
            "cust_id", "cust_name");
        applyEntity("sales");
        seed("('1', 'Ann'), ('2', 'Bob'), ('3', 'Cid')");
    }

    void applyEntity(String scope) {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(entityYaml(scope))
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    void seed(String values) {
        Jdbc.update(adminDs, "seed " + table(),
            "INSERT INTO connector." + table() + " (tenant_id, cust_id, cust_name) VALUES "
                + values.replace("('", "(" + TENANT + ", '"),
            ps -> {});
    }

    String applyExport(String target, boolean wrongCredential) {
        return applyExport(target, wrongCredential, "");
    }

    /** {@code formatExtra}: further {@code spec.format} lines, indented for the manifest. */
    String applyExport(String target, boolean wrongCredential, String formatExtra) {
        return applyExport(target, wrongCredential, formatExtra, "");
    }

    /** {@code specExtra}: further {@code spec} lines (a schedule), indented for the manifest. */
    String applyExport(String target, boolean wrongCredential, String formatExtra, String specExtra) {
        String name = "export-run-" + UUID.randomUUID().toString().substring(0, 8);
        reapply(name, target, wrongCredential, formatExtra, specExtra);
        return name;
    }

    /** Applies (or re-applies, keeping its id) export {@code name} writing to {@code target}. */
    void reapply(String name, String target, boolean wrongCredential) {
        reapply(name, target, wrongCredential, "", "");
    }

    private void reapply(String name, String target, boolean wrongCredential, String formatExtra,
                         String specExtra) {
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata:
              name: %s
            spec:
              scope: sales
              source:
                entity: %s
              columns: [code, name]
              order: [code]
              format:
                kind: csv
            %s
              destination:
            %s
              mode: snapshot
            %s
            """.formatted(name, entity(), formatExtra, destination(target, wrongCredential), specExtra);
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(yaml)
            .post("/api/exports/apply")
            .then().statusCode(200);
    }

    /** The stored manifest row, as a door reads it before authorizing a run. */
    ExportManifestStore.Row row(String name) {
        return TenantScope.get(TENANT, () -> QuarkusTransaction.requiringNew()
            .call(() -> store.find(TENANT, name))).orElseThrow();
    }

    /** Starts a manual run resolved at {@code at}, awaits its worker, returns the ledger row. */
    ExportRunView run(String name, Instant at) throws Exception {
        return await(name, runner.launch(
            new ExportRunRequest(TENANT, name, ExportTrigger.MANUAL, 7L, null, at, 1, null)));
    }

    /** Awaits a launch that must have started, and returns its ledger row. */
    ExportRunView await(String name, ExportRunner.Launch launch) throws Exception {
        assertThat(launch.result()).isInstanceOf(StartResult.Started.class);
        StallProbe.diagnosing("export worker " + name, () -> launch.worker().get(60, TimeUnit.SECONDS));
        UUID runId = ((StartResult.Started) launch.result()).runId();
        return TenantScope.get(TENANT, () -> QuarkusTransaction.requiringNew()
            .call(() -> ledger.find(TENANT, name, runId))).orElseThrow();
    }

    /** What the streamer produces locally over the same data: the bytes and their stats. */
    record Local(byte[] bytes, SnapshotStats stats) {}

    Local local() {
        return TenantScope.get(TENANT, () -> QuarkusTransaction.requiringNew().call(() -> {
            EntityManifest e = registry.findReloadingIfMissing(entity());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, TENANT);
                SnapshotStats stats = streamer.stream(c, e, List.of("code", "name"), List.of("code"),
                    ExportManifest.Format.DEFAULT, out);
                return new Local(out.toByteArray(), stats);
            }
        }));
    }

    /** Every audit outbox row of this export (APPLY included), as "OPERATION changes-json". */
    List<String> audit(String name) {
        return Jdbc.query(adminDs, "audit " + name,
            "SELECT operation, changes::text FROM core.audit_outbox WHERE entity_key = ?",
            ps -> ps.setString(1, name),
            rs -> rs.getString(1) + " " + rs.getString(2));
    }

    long runRows(String name) {
        return Jdbc.queryOne(adminDs, "runRows " + name,
            "SELECT count(*) FROM connector.export_run WHERE export_name = ?",
            ps -> ps.setString(1, name),
            rs -> rs.getLong(1)).orElseThrow();
    }

    UUID exportId(String name) {
        return Jdbc.queryOne(adminDs, "exportId " + name,
            "SELECT id FROM connector.export_manifest WHERE tenant_id = ? AND name = ?",
            ps -> {
                ps.setLong(1, TENANT);
                ps.setString(2, name);
            },
            rs -> rs.getObject(1, UUID.class)).orElseThrow();
    }

    static final Instant AT = Instant.parse("2026-09-24T04:00:00Z");
}
