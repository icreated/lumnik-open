package io.lumnik.hub.export.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.error.Throwables;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.export.EntitySnapshotStreamer;
import io.lumnik.hub.export.ExportDefinitionCheck;
import io.lumnik.hub.export.ExportManifest;
import io.lumnik.hub.export.ExportManifestParser;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.export.ExportManifestStore.Row;
import io.lumnik.hub.export.SnapshotShape;
import io.lumnik.hub.export.SnapshotStats;
import io.lumnik.hub.export.destination.FileDestination;
import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.FileDestinationRegistry;
import io.lumnik.hub.export.destination.PublishedArtifact;
import io.lumnik.hub.export.format.ColumnFormatterRegistry;
import io.lumnik.hub.export.run.ExportRunLedger.Claim;
import io.lumnik.hub.export.run.ExportRunLedger.Claimed;
import io.lumnik.hub.export.run.ExportRunLedger.NewRun;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.run.RunQueueSettings;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The one execution path of an export run: a manual request and a due cron slot both end here.
 * {@link #start} takes the export's RunLock, claims a {@code Running} row and hands both to a
 * virtual thread, all before any byte moves (with {@code lumnik.run-queue.enabled} the door instead
 * claims a {@code Queued} row and no lock, and the {@link ExportRunDispatcher} later does the rest); the worker re-checks the stored definition, resolves
 * the destination, streams the snapshot into it and commits or aborts, then writes exactly one
 * terminal state and only then releases the lock.
 *
 * <p>The lock is taken before the row exists (the #693 lesson): a {@code Running} row is never
 * visible with a free lock, so the reaper cannot mistake a run that is starting for a dead one.
 */
@ApplicationScoped
public class ExportRunner {

    private static final Logger LOG = Logger.getLogger(ExportRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Inject ExportManifestStore store;
    @Inject ExportRunLedger ledger;
    @Inject EntityRegistry registry;
    @Inject EntitySnapshotStreamer streamer;
    @Inject FileDestinationRegistry destinations;
    @Inject ColumnFormatterRegistry formatters;
    @Inject AuditRecorder auditRecorder;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject RunQueueSettings runQueue;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

    @ConfigProperty(name = "lumnik.export.snapshot.timeout-seconds", defaultValue = "1800")
    int timeoutSeconds;

    private final ExportManifestParser parser = new ExportManifestParser();
    private ExecutorService vt;

    @PostConstruct
    void init() {
        vt = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    void shutdown() {
        vt.shutdownNow();
    }

    /**
     * A start and, when it started, the worker's future — the test seam for awaiting a run, and how
     * {@link ExportScheduler} gets its run permit back (#897). It completes once the worker has
     * released the run's lock, and is already complete when nothing started.
     */
    record Launch(StartResult result, CompletableFuture<?> worker) {}

    /**
     * Claims a run synchronously on the caller's thread and returns before any byte moves. Only
     * {@link StartResult.Started} leaves a row behind; every other answer writes nothing.
     */
    public StartResult start(ExportRunRequest request) {
        return launch(request).result();
    }

    /**
     * Starts a run of exactly {@code authorized}: the manifest row a caller has already checked
     * (scope, existence) is the one claimed and executed, never a fresh read. A re-apply landing
     * between the caller's check and this call cannot swap in a definition — another scope,
     * another destination — that the caller was never authorized for; the run carries the
     * snapshot that was checked, and its manifest_sha256 says which one. With
     * {@code lumnik.run-queue.enabled} the run is queued instead of started (#952).
     */
    public StartResult start(ExportRunRequest request, Row authorized) {
        if (runQueue.enabled()) return enqueue(request, authorized);
        return launch(request, authorized).result();
    }

    /**
     * The door with {@code lumnik.run-queue.enabled} (#952, D13): one durable {@code Queued} row and nothing else — no
     * lock, no worker. {@code uq_export_run_in_flight} counts {@code Queued}, so a run already queued or running
     * answers {@code AlreadyRunning}; the dispatcher starts the row, on this replica (the door wakes it) or another.
     */
    private StartResult enqueue(ExportRunRequest request, Row row) {
        requireAuthorized(request, row);
        Instant artifactTime = request.artifactTime() != null ? request.artifactTime() : Instant.now();
        NewRun run = newRun(request, row, artifactTime);
        Claim claim = inTenant(request.tenantId(), () -> ledger.claim(run, true));
        return switch (claim) {
            case Claimed c -> new StartResult.Started(c.runId());
            case ExportRunLedger.AlreadyRunning a -> new StartResult.AlreadyRunning(a.runId());
            case ExportRunLedger.OccurrenceTaken o -> new StartResult.OccurrenceTaken();
        };
    }

    /** The scheduler's form: no caller authorized anything, so the stored row is read here. */
    Launch launch(ExportRunRequest request) {
        long tenantId = request.tenantId();
        Optional<Row> found = inTenant(tenantId, () -> store.find(tenantId, request.exportName()));
        return found.map(row -> launch(request, row)).orElseGet(() -> idle(new StartResult.NotFound()));
    }

    Launch launch(ExportRunRequest request, Row row) {
        long tenantId = request.tenantId();
        requireAuthorized(request, row);

        RunLock lock;
        try {
            lock = RunLock.tryAcquire(adminDs, row.id());
        } catch (SQLException e) {
            throw new IllegalStateException("export " + row.name() + ": could not take its run lock", e);
        }
        if (lock == null) return idle(new StartResult.AlreadyRunning(null));

        Instant artifactTime = request.artifactTime() != null ? request.artifactTime() : Instant.now();
        Claim claim;
        try {
            NewRun run = newRun(request, row, artifactTime);
            claim = inTenant(tenantId, () -> ledger.claim(run));
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
        UUID runId;
        switch (claim) {
            case Claimed c -> runId = c.runId();
            case ExportRunLedger.AlreadyRunning a -> {
                return refused(lock, new StartResult.AlreadyRunning(a.runId()));
            }
            case ExportRunLedger.OccurrenceTaken o -> {
                return refused(lock, new StartResult.OccurrenceTaken());
            }
        }

        CompletableFuture<?> worker;
        try {
            worker = startClaimed(runId, request, row, artifactTime, lock);
        } catch (RuntimeException e) {
            // Executor shut down: the worker that would close the lock never starts. The row stays
            // Running with a free lock, which is exactly what the reaper collects.
            lock.close();
            throw e;
        }
        return new Launch(new StartResult.Started(runId), worker);
    }

    /**
     * The worker of a run whose row is already {@code Running} and whose lock the caller holds (#952): it
     * owns {@code lock} from here. A throw from the submit leaves the lock to the caller.
     */
    CompletableFuture<?> startClaimed(UUID runId, ExportRunRequest request, Row row, Instant artifactTime,
                                      RunLock lock) {
        return CompletableFuture.runAsync(() -> execute(runId, request, row, artifactTime, lock), vt);
    }

    private static void requireAuthorized(ExportRunRequest request, Row row) {
        if (row.tenantId() != request.tenantId() || !row.name().equals(request.exportName()))
            throw new IllegalArgumentException("export run request does not match its authorized row");
    }

    /** What a run freezes at claim time, the same for a started run and a queued one. */
    private static NewRun newRun(ExportRunRequest request, Row row, Instant artifactTime) {
        return new NewRun(request.tenantId(), row.id(), row.name(), row.scope(), row.entityName(),
            row.destinationKind(), AuditRecorder.sha256(row.yaml()), request.trigger(), request.actorId(),
            request.occurrence(), artifactTime, request.attempt(), request.retryOf());
    }

    /** A claim the ledger refused: nothing was written, so the lock is released at once. */
    private static Launch refused(RunLock lock, StartResult result) {
        lock.close();
        return idle(result);
    }

    private static Launch idle(StartResult result) {
        return new Launch(result, CompletableFuture.completedFuture(null));
    }

    private void execute(UUID runId, ExportRunRequest request, Row row, Instant artifactTime,
                         RunLock lock) {
        // The lock outlives the terminal write: a failing run still owns its row while it records
        // the failure, so the reaper cannot take the row from under it.
        try (lock) {
            executeLocked(runId, request, row, artifactTime);
        }
    }

    private void executeLocked(UUID runId, ExportRunRequest request, Row row, Instant artifactTime) {
        var requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            Outcome outcome = deliver(runId, request, row, artifactTime);
            // vt.shutdownNow() at hub shutdown reaches this worker as a real interrupt; a flagged
            // thread would poison the terminal write below and leave the row Running until the
            // reaper. Cleared for that write only and restored after, as ConnectorEngine does.
            boolean interrupted = Thread.interrupted();
            try {
                record(runId, request, row, outcome);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        } finally {
            requestContext.deactivate();
        }
    }

    /** How the delivery ended: a receipt, or a failure already classified and aborted. */
    private sealed interface Outcome {}

    private record Delivered(SnapshotStats stats, PublishedArtifact artifact) implements Outcome {}

    private record Failed(ExportFailureCode code, Throwable cause) implements Outcome {}

    /** What the re-checked stored definition resolves to for this run. */
    private record Definition(ExportManifest manifest, EntityManifest entity, SnapshotShape shape,
                              Map<String, Object> cfg) {}

    /** Spec steps 3–6. Never throws: every failure comes back as a Failed, already aborted. */
    private Outcome deliver(UUID runId, ExportRunRequest request, Row row, Instant artifactTime) {
        long tenantId = request.tenantId();
        Definition definition;
        try {
            definition = define(tenantId, row, artifactTime);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Export run %s (%s) failed", runId, row.name());
            return new Failed(classify(e, ExportFailureCode.DEFINITION), e);
        }

        FileDestination destination = null;
        // What a failure that names no cause of its own is: the transport's until the snapshot
        // read starts, the read's while it runs, the transport's again for the commit.
        ExportFailureCode otherwise = ExportFailureCode.PUBLICATION;
        try {
            // SecretRefs reads the tenant's lm secret registry, so build() runs inside the tenant.
            FileDestination built =
                TenantScope.get(tenantId, () -> destinations.build(definition.cfg()));
            destination = built;
            OutputStream out = built.open();

            otherwise = ExportFailureCode.GENERATION;
            SnapshotStats stats = generate(tenantId, definition, out);

            otherwise = ExportFailureCode.PUBLICATION;
            return new Delivered(stats, built.commit());
        } catch (Exception e) {
            LOG.errorf(e, "Export run %s (%s) failed", runId, row.name());
            if (destination != null) {
                try {
                    destination.abort();
                } catch (RuntimeException cleanup) {
                    LOG.warnf(cleanup, "Export run %s: abort after failure also failed", runId);
                }
            }
            return new Failed(classify(e, otherwise), e);
        } finally {
            if (destination != null) {
                try {
                    destination.close();
                } catch (RuntimeException closing) {
                    LOG.warnf(closing, "Export run %s: closing the destination failed", runId);
                }
            }
        }
    }

    /**
     * Spec step 3–4: re-parse the stored definition, re-run the apply door's entity checks, and
     * resolve the destination. A definition that no longer holds is an authored ExportRunFailure.
     */
    private Definition define(long tenantId, Row row, Instant artifactTime) {
        JsonNode root;
        ExportManifest manifest;
        try {
            root = parser.readTree(row.yaml());
            manifest = parser.parse(root);
        } catch (IllegalArgumentException e) {
            throw new ExportRunFailure(ExportFailureCode.DEFINITION, e.getMessage(), e);
        }
        EntityManifest entity =
            inTenant(tenantId, () -> registry.findReloadingIfMissing(manifest.entity()));
        SnapshotShape shape = switch (ExportDefinitionCheck.resolve(manifest, entity, formatters)) {
            case ExportDefinitionCheck.Problem problem ->
                throw new ExportRunFailure(ExportFailureCode.DEFINITION, problem.sentence());
            case ExportDefinitionCheck.Holds holds -> holds.shape();
        };
        return new Definition(manifest, entity, shape, destinationConfig(root, artifactTime));
    }

    /** Spec step 5: the snapshot, on the readonly datasource under the tenant, into {@code out}. */
    private SnapshotStats generate(long tenantId, Definition definition, OutputStream out) {
        ExportManifest manifest = definition.manifest();
        return TenantScope.get(tenantId, () ->
            QuarkusTransaction.requiringNew().timeout(timeoutSeconds).call(() -> {
                try (Connection c = readonlyDs.getConnection()) {
                    TenantRlsScope.applyTo(c, tenantId);
                    return streamer.stream(c, definition.entity(), definition.shape(),
                        manifest.order(), manifest.format(), out);
                }
            }));
    }

    /**
     * The failure code, read from the failure itself: an authored ExportRunFailure carries its
     * own; a destination's exception is a collision or a publication failure wherever it was
     * thrown (a write the destination refused mid-stream is an upload failure, not a read one);
     * anything else is {@code otherwise}, the stage it happened in.
     */
    static ExportFailureCode classify(Throwable failure, ExportFailureCode otherwise) {
        List<Throwable> chain = Throwables.causeChain(failure);
        for (Throwable t : chain)
            if (t instanceof ExportRunFailure f) return f.code();
        if (chain.stream().anyMatch(FileDestinationCollisionException.class::isInstance))
            return ExportFailureCode.COLLISION;
        if (chain.stream().anyMatch(FileDestinationException.class::isInstance))
            return ExportFailureCode.PUBLICATION;
        return otherwise;
    }

    /**
     * The one terminal write, then the EGRESS audit on success. A false from the ledger means the
     * row already left Running (reaped): logged, never retried as an overwrite.
     */
    private void record(UUID runId, ExportRunRequest request, Row row, Outcome outcome) {
        long tenantId = request.tenantId();
        Instant endedAt = Instant.now();
        Delivered delivered;
        switch (outcome) {
            case Failed failed -> {
                String reason = ExportFailureText.of(failed.cause());
                if (!inTenant(tenantId, () -> ledger.fail(runId, endedAt, failed.code(), reason)))
                    LOG.errorf("Export run %s: Failed/%s not recorded — the row is no longer Running",
                        runId, failed.code().sqlValue());
                return;
            }
            case Delivered d -> delivered = d;
        }
        boolean completed;
        try {
            completed = inTenant(tenantId,
                () -> ledger.complete(runId, endedAt, delivered.stats(), delivered.artifact()));
        } catch (RuntimeException e) {
            // The artifact IS published but the receipt could not be written (the delivered-index
            // backstop, or the database going away). Neither Completed-without-receipt nor a row
            // left Running is acceptable, so fail it as interrupted — the same under-reporting,
            // "may exist" ending the reaper gives a hub that died between commit and complete.
            LOG.errorf(e, "Export run %s: published %s but could not record it", runId,
                delivered.artifact().location());
            String reason = "the artifact was published but its delivery could not be recorded: "
                + ExportFailureText.of(e);
            if (!inTenant(tenantId, () -> ledger.fail(runId, Instant.now(),
                    ExportFailureCode.INTERRUPTED, reason)))
                LOG.errorf("Export run %s: Failed/interrupted not recorded either", runId);
            return;
        }
        if (!completed) {
            LOG.errorf("Export run %s: Completed not recorded — the row is no longer Running,"
                + " though %s was published", runId, delivered.artifact().location());
            return;
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("run_id", runId.toString());
        changes.put("entity", row.entityName());
        changes.put("scope", row.scope());
        changes.put("row_count", delivered.stats().rowCount());
        changes.put("byte_count", delivered.stats().byteCount());
        changes.put("sha256", delivered.stats().sha256Hex());
        changes.put("destination", delivered.artifact().kind());
        changes.put("location", delivered.artifact().location());
        auditRecorder.record(tenantId, request.actorId(), "Export", row.name(), "EGRESS", changes);
    }

    /** {@code spec.destination} as the factories read it, with the artifact-name tokens resolved. */
    static Map<String, Object> destinationConfig(JsonNode root, Instant artifactTime) {
        Map<String, Object> cfg = MAPPER.convertValue(root.path("spec").path("destination"), MAP);
        for (String key : ArtifactName.TEMPLATED_FIELDS) {
            if (cfg.get(key) instanceof String template)
                cfg.put(key, ArtifactName.resolve(template, artifactTime));
        }
        return cfg;
    }

    /** A short transaction on the RLS default datasource under a positive tenant. */
    private static <T> T inTenant(long tenantId, Supplier<T> work) {
        return TenantScope.get(tenantId, () -> QuarkusTransaction.requiringNew().call(work::get));
    }
}
