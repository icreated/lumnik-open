package io.lumnik.hub.export.run;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.run.RunLock;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * What the two export-dispatcher ITs share (#952): the SFTP destination of {@link ExportRunSchedulingIT}, a
 * way to write a {@code Queued} run as slice 4's door will, and to read it back. Awaitility is named in full:
 * {@link ExportRunITSupport} has an {@code await(name, launch)} of its own.
 */
abstract class ExportQueuedRunITSupport extends ExportRunITSupport {

    @Inject ExportRunDispatcher dispatcher;
    @Inject ExportScheduler scheduler;

    /** The permits free before the test: every one must be back when it ends. */
    int permitsAtRest;

    @BeforeAll
    static void startSftp() throws Exception {
        ExportRunSftpIT.startSftp();
    }

    @AfterAll
    static void stopSftp() {
        ExportRunSftpIT.stopSftp();
    }

    @Override
    String entity() {
        return "ExportRunDispQueued952";
    }

    @Override
    String destination(String target, boolean wrongCredential) {
        return ExportRunSftpIT.sftpDestination(target, wrongCredential);
    }

    @Override
    byte[] remote(String target) throws Exception {
        return ExportRunSftpIT.sftpRemote(target);
    }

    @Override
    String location(String target) {
        return "/out/" + target;
    }

    @Override
    void assertNoLeftover(String target) {}

    @BeforeEach
    void clearQueue() {
        deleteQueued();
        permitsAtRest = scheduler.runPermits().availablePermits();
    }

    @AfterEach
    void restore() {
        deleteQueued();
        dispatcher.afterCommit(() -> {});
        Awaitility.await().atMost(Duration.ofSeconds(30))
            .until(() -> scheduler.runPermits().availablePermits() == permitsAtRest);
    }

    /** Every Queued export run, whoever wrote it: a dispatcher takes the oldest of ALL of them. */
    private void deleteQueued() {
        Jdbc.update(adminDs, "clear the export queue",
            "DELETE FROM connector.export_run WHERE status = 'Queued'", ps -> {});
    }

    /** A Queued run of export {@code name}: no start, an accept instant, the sha of the manifest as it stands. */
    UUID queue(String name, Instant queuedAt) {
        ExportManifestStore.Row row = row(name);
        UUID id = UUID.randomUUID();
        Jdbc.update(adminDs, "queue an export run",
            "INSERT INTO connector.export_run (id, tenant_id, export_id, export_name, scope, entity_name, "
          + "destination_kind, manifest_sha256, trigger, triggered_by, artifact_time, attempt, status, "
          + "started_at, queued_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'manual', 7, ?, 1, 'Queued', NULL, ?)",
            ps -> {
                ps.setObject(1, id);
                ps.setLong(2, TENANT);
                ps.setObject(3, row.id());
                ps.setString(4, row.name());
                ps.setString(5, row.scope());
                ps.setString(6, row.entityName());
                ps.setString(7, row.destinationKind());
                ps.setString(8, AuditRecorder.sha256(row.yaml()));
                ps.setObject(9, queuedAt.atOffset(ZoneOffset.UTC));
                ps.setObject(10, queuedAt.atOffset(ZoneOffset.UTC));
            });
        return id;
    }

    record QueuedRun(String status, Instant startedAt, Instant endedAt, Instant queuedAt,
                     String failureCode, String failureReason) {}

    QueuedRun runRow(UUID id) {
        return Jdbc.queryOne(adminDs, "runRow " + id,
            "SELECT status, started_at, ended_at, queued_at, failure_code, failure_reason "
          + "FROM connector.export_run WHERE id = ?",
            ps -> ps.setObject(1, id),
            rs -> new QueuedRun(rs.getString(1), Jdbc.instant(rs, 2), Jdbc.instant(rs, 3), Jdbc.instant(rs, 4),
                rs.getString(5), rs.getString(6))).orElseThrow();
    }

    /** Awaits the run out of Queued and Running, then reads it. */
    QueuedRun settled(UUID id) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> {
            String status = runRow(id).status();
            return !status.equals("Queued") && !status.equals("Running");
        });
        return runRow(id);
    }

    /** True once nobody holds the export's run lock (the worker closes it after its terminal write). */
    boolean lockIsFree(UUID exportId) throws Exception {
        RunLock lock = RunLock.tryAcquire(adminDs, exportId);
        if (lock == null) return false;
        lock.close();
        return true;
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
