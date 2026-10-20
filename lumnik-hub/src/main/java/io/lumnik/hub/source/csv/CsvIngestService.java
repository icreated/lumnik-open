package io.lumnik.hub.source.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.api.sources.CsvIngestResult.PromoteColumn;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.csv.CsvFileReader;
import io.lumnik.hub.connector.csv.CsvFileReader.Config;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.spec.MappingTarget.HybridJsonb;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.ask.DiscoveryService;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.api.connector.ConnectorScope;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.naming.JsonPaths;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.hook.FileSourceHookRunner;
import io.lumnik.hub.connector.transport.hook.HookScriptRefusedException;
import io.lumnik.hub.connector.transport.hook.HookSpec;
import io.lumnik.hub.connector.transport.metrics.FileSourceMetrics;
import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.extraction.spi.ExtractionSink;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One-shot CSV ingest: schema inference → dormant connector + endpoint → rows written to ext.* table.
 *
 * <p>Supports two ingestion paths:
 * <ul>
 *   <li><b>Local</b> — a file is uploaded directly (original behaviour).</li>
 *   <li><b>Remote</b> — a {@code transport} block in the form selects an SFTP or S3 source;
 *       each matching remote file is buffered to a temp file and fed through the local pipeline.</li>
 * </ul>
 *
 * <p>The connector and endpoint are created with {@code enabled = false} / no schedule so the
 * caller can inspect, promote, or delete them before enabling scheduled sync.
 */
@ApplicationScoped
public class CsvIngestService {

    private static final Logger LOG = Logger.getLogger(CsvIngestService.class);

    private static final String LOCAL = "local";

    /**
     * The name belongs to a CONFIGURED connector — refused (409). A dormant one-shot twin
     * is not refused: re-ingesting it is one more breath (see the breath block below).
     */
    public static class DuplicateIngestName extends RuntimeException {
        DuplicateIngestName(String name) {
            super("the name '" + name + "' belongs to a configured connector — "
                + "pass a different --name, or delete that connector first (lm connector delete)");
        }
    }

    /**
     * A row this one-shot door cannot take as written: the file is the installer's to fix, so the
     * resource's {@code IllegalArgumentException} mapper answers a 400 naming its line (#1248). The
     * message never carries the file — on this door it is the hub's own upload temp path.
     */
    static final class MalformedRow extends IllegalArgumentException {
        MalformedRow(MalformedRecord m) {
            super("malformed CSV row at line " + m.line() + " — " + m.message());
        }
    }

    private static void refuse(MalformedRecord m) {
        throw new MalformedRow(m);
    }

    /** An existing dormant one-shot under this (tenant, name): the body a breath reuses. */
    private record PriorOneShot(UUID connectorId, UUID endpointId, List<String> oldTags,
                                String uniqueKey, List<PromotedColumn> storedPromote) {}

    /** One post-commit move: index under {@code tags}, rediscover {@code tags ∪ oldTags}. */
    private record AskableMove(UUID connectorId, String tableName, List<String> tags,
                               List<String> oldTags, List<PromotedColumn> promoted, int rows) {
        List<String> rediscoverTags() {
            List<String> all = new ArrayList<>(tags);
            for (String t : oldTags) if (!all.contains(t)) all.add(t);
            return all;
        }
    }

    private static final int DEFAULT_SAMPLE_ROWS = 100;
    private static final int BATCH_SIZE = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject EntityManager em;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject DiscoveryService discovery;
    @Inject Instance<ExtractionSink> sinks;
    @Inject @Analytic Instance<ChatPort> analyticChat;
    @Inject DynamicTableRegistry dynamicTables;
    @Inject TransactionSynchronizationRegistry txSync;
    @Inject MultiTargetWriter writer;
    @Inject FileSourceRegistry fileSourceRegistry;
    @Inject FileSourceHookRunner hooks;
    @Inject FileSourceMetrics metrics;

    /**
     * The ingest is transactional, but the boundary is opened <i>inside</i> — not by an
     * {@code @Transactional} interceptor on this method. The remote path must archive
     * ({@code after_process}: a real MOVE/DELETE on the source) only once the writes have
     * <b>committed</b>, which is impossible while the caller's frame still holds the transaction.
     * See {@link #executeViaFileSource}.
     *
     * <p>The local paths keep the old {@code REQUIRED} semantics via {@code joiningExisting()}
     * ({@code JOIN_EXISTING}: joins an active transaction, and <b>starts one</b> when none is —
     * it is {@code disallowingExisting()} that refuses to run without one): they drive no
     * {@code FileSource} at all, so nothing forces them out of a caller's transaction.
     */
    public CsvIngestResult execute(CsvIngestForm form, long tenantId) {
        // Ensure the JDBC interceptor sees the correct tenant for native queries.
        CurrentTenantHolder.set(tenantId);

        // A blank name is an absent name — normalized once here rather than re-checked downstream.
        // Four sites disagreed on blank-vs-null; the sharpest passed the blank through buildSubForm
        // until executeLocalFile fell back to the name of the ephemeral TEMP DOWNLOAD, landing rows
        // in ext.csv_ingest_remote_<random> — a different table every run.
        if (form.name != null && form.name.isBlank()) form.name = null;

        Map<String, Object> transportCfg = form.resolveTransport();
        String kind = (String) transportCfg.getOrDefault("kind", LOCAL);

        // Local upload path: a file was uploaded directly — use the fast local pipeline.
        // If an explicit path is provided in the transport map (or any non-upload config such as
        // max_file_size), route through executeViaFileSource so all transport options are honoured.
        // "Implicit" is judged on the FORM, not on the resolved map: resolveTransport() derives a
        // {kind, path} map from an upload, and judging by map size sent every HTTP upload down the
        // FileSource pipeline — which then named the table after RESTEasy's multipart temp file.
        boolean implicitUpload = form.transport == null
            && (form.transportJson == null || form.transportJson.isBlank());
        if (LOCAL.equals(kind) && implicitUpload) {
            // No explicit transport — the request IS the uploaded file.
            File file = form.resolveFile();
            if (file == null) {
                throw new IllegalArgumentException("local transport requires a file upload");
            }
            return QuarkusTransaction.joiningExisting().call(() -> executeLocalFile(form, tenantId, file));
        }
        if (LOCAL.equals(kind) && !transportCfg.containsKey("path")) {
            // Explicit local transport but no path — fall back to uploaded file.
            File file = form.resolveFile();
            if (file == null) {
                throw new IllegalArgumentException("local transport requires either 'path' or a file upload");
            }
            return QuarkusTransaction.joiningExisting().call(() -> executeLocalFile(form, tenantId, file));
        }

        // Remote path (SFTP, S3) or local transport with explicit path/options:
        // iterate files via FileSource, buffer each to a temp file, reuse local pipeline.
        return executeViaFileSource(form, tenantId, transportCfg);
    }

    // ── Local file pipeline ───────────────────────────────────────────────────

    private CsvIngestResult executeLocalFile(CsvIngestForm form, long tenantId, File file) {
        // Unnamed upload: the table carries the CALLER's filename (the multipart part name),
        // never the server-side buffer's — RESTEasy names that one resteasy-reactive…upload,
        // and the remote pipeline's twin test already pinned the same rule for temp downloads.
        String callerFile = (form.fileUpload != null
                && form.fileUpload.fileName() != null && !form.fileUpload.fileName().isBlank())
            ? form.fileUpload.fileName() : file.getName();
        String name = slugify(
            (form.name != null && !form.name.isBlank()) ? form.name : stripExt(callerFile)
        );
        String tableName = "ext." + name;

        // A taken name forks three ways at the threshold (never as the unique constraint's
        // anonymous 500): free → first breath; a dormant one-shot twin → ONE MORE BREATH
        // (reuse the connector, dedup by key, refresh card and descriptor); a configured
        // connector → the honest 409. One entity query answers all three; the race window
        // between this read and the write is closed by the guarded upsert below.
        PriorOneShot prior = findPrior(tenantId, name);
        boolean breath = prior != null;

        // Honour caller's format hints, auto-detect the rest, then sample to infer the schema.
        Config cfg = buildConfig(file.toPath(), form);
        int sampleLimit = (form.sampleRows != null && form.sampleRows > 0) ? form.sampleRows : DEFAULT_SAMPLE_ROWS;
        List<Map<String, Object>> sample = new ArrayList<>(sampleLimit);
        readWithLimit(file.toPath(), cfg, sampleLimit, sample);
        List<PromotedColumn> inferred = inferSchema(sample);
        // Stored columns keep their names, order and TYPES (the table's are fixed);
        // genuinely new columns append — the writer's schema evolution adds them.
        List<PromotedColumn> promoted = breath ? mergePromote(prior.storedPromote(), inferred) : inferred;

        UUID connectorId = breath ? prior.connectorId() : UUID.randomUUID();
        if (breath) {
            // A managed row has ONE writer: findPrior's entity lives in the persistence
            // context, and setTags below dirties it — a native upsert here was silently
            // reverted by the stale entity's full-column flush at commit (review catch,
            // pinned by the scope-migration breath test). Config rides the same flush.
            connectors.findById(connectorId).setConfig(connectorConfigMap(callerFile));
        } else {
            upsertDormantConnector(connectorId, tenantId, name, callerFile);
        }
        // The scope is a tag, and tags are what SchemaCardRefresher and rediscover walk —
        // without them the one-shot has no scope to be askable under. Entity gesture, the
        // ConnectorTagsResource pattern. On a breath the command's scope WINS (re-ingesting
        // is a re-publication act) — and the old scopes get a rediscover so their cards let go.
        List<String> connectorTags = connectorTags(form);
        connectors.findById(connectorId).setTags(connectorTags);

        UUID endpointId = breath ? prior.endpointId() : UUID.randomUUID();
        String uniqueKey = breath ? prior.uniqueKey()
            : (promoted.isEmpty() ? "$.id" : JsonPaths.member(promoted.get(0).name()));
        upsertDormantEndpoint(endpointId, connectorId, tenantId, name, callerFile, tableName, uniqueKey, promoted);

        var target = new MappingTarget(null, null, null,
            new HybridJsonb(tableName, uniqueKey, promoted, endpointId));
        int rowsRead = readAndWrite(file.toPath(), cfg, target, promoted, form, tenantId, name, connectorId);
        int rowsWritten = rowsRead;  // all rows read are written (no skip logic in v1)

        List<PromoteColumn> resultCols = promoted.stream()
            .map(pc -> new PromoteColumn(pc.name(), pc.type().name().toLowerCase(), sampleLimit))
            .toList();

        // The askable moves ride AFTER the commit (the registry-retry posture): the card
        // builder and the sink read committed state, and a failure of either must never
        // fail an ingest whose rows are already durable.
        AskableMove move = new AskableMove(connectorId, tableName, connectorTags,
            breath ? prior.oldTags() : List.of(), promoted, rowsWritten);
        if (txSync.getTransactionKey() != null) {
            txSync.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() { /* nothing to flush */ }
                @Override public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) makeAskableAfterCommit(tenantId, move);
                }
            });
        }

        return new CsvIngestResult(
            connectorId, endpointId, tableName,
            rowsRead, rowsWritten, 0,
            resultCols, List.of(),
            connectorTags, chatAvailable(), breath
        );
    }

    /**
     * The dormant one-shot twin under (tenant, name) as a breath body, {@code null} when the
     * name is free — a configured connector's name throws the 409 here. One entity query;
     * the ORM's own mappings replace hand-parsing (tags are a mapped List, config a mapped
     * Map, and the target block goes through THE parser, transform preserved).
     */
    private PriorOneShot findPrior(long tenantId, String name) {
        // Locked until this ingest commits: the scope decision below is taken on this row, and the
        // ingest that follows is long. Unlocked, a retag committed in between was judged on its old
        // tags, then overwritten, while the rows landed in the table of the connector's new métier
        // (#1216 review). The apply door holds the same row the same way (byAddressForUpdate).
        Connector c = connectors.byAddressForUpdate(tenantId, name)
            .filter(found -> !found.isDeleted())
            .orElse(null);
        if (c == null) return null;
        // A breath rewrites the one-shot's config, writes its table and replaces its tags: the
        // caller must hold the scopes it lives in, or they take over another métier's (#1212).
        // Before the 409 below, so a name of another métier answers with the uniform refusal.
        ConnectorScope.refuseUnlessHolds(c.getTags());
        boolean dormantOneShot = "csv-file".equals(c.getTypeId()) && !c.isEnabled()
            && Boolean.TRUE.equals(c.getConfig().get("dormant"));
        if (!dormantOneShot) throw new DuplicateIngestName(name);
        var endpoint = endpoints.findByName(c.getId(), name).orElseThrow(() ->
            new IllegalStateException("dormant one-shot '" + name + "' has no endpoint row"));
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) endpoint.getConfig().getOrDefault("target", Map.of());
        HybridJsonbSpec spec = HybridJsonbSpec.fromTargetConfig(target);
        return new PriorOneShot(c.getId(), endpoint.getId(), List.copyOf(c.getTags()),
            spec.uniqueKeyPath() != null ? spec.uniqueKeyPath() : "$.id", spec.promote());
    }

    /** Stored columns first (names, order and types are the table's); new ones append. */
    private static List<PromotedColumn> mergePromote(List<PromotedColumn> stored, List<PromotedColumn> inferred) {
        Map<String, PromotedColumn> byName = new LinkedHashMap<>();
        for (PromotedColumn pc : stored) byName.put(pc.name(), pc);
        for (PromotedColumn pc : inferred) byName.putIfAbsent(pc.name(), pc);
        return new ArrayList<>(byName.values());
    }

    /** The scope is first (it keys the card), then the caller's extra tags. */
    public static List<String> connectorTags(CsvIngestForm form) {
        List<String> tags = new ArrayList<>();
        tags.add(form.scope != null && !form.scope.isBlank() ? form.scope.trim() : "system");
        if (form.tags != null) {
            for (String t : form.tags.split(",")) {
                if (!t.isBlank() && !tags.contains(t.trim())) tags.add(t.trim());
            }
        }
        return tags;
    }

    /**
     * One command, an askable table (spec 2026-09-02): after the ingest commits, register
     * the descriptor chunk (one per table — per-row indexing stays the configured
     * connector's territory) and rediscover each tag so the schema card exists. Both
     * fail soft with the SchemaCardRefresher contract: the rows are durable, and
     * {@code lm source rediscover} remains the manual recovery.
     */
    private void makeAskableAfterCommit(long tenantId, AskableMove move) {
        UUID connectorId = move.connectorId();
        String tableName = move.tableName();
        List<String> tags = move.tags();
        List<PromotedColumn> promoted = move.promoted();
        int rows = move.rows();
        // Ensure the registry row exists before rediscovering — the writer schedules its own
        // after-commit retry, but interposed-synchronization ORDER is not ours to rely on.
        // register() is an upsert, so meeting the retry is harmless either way. Business
        // columns only: the registerIfNeeded contract (what the card may advertise).
        try {
            String[] parts = tableName.split("\\.", 2);
            Map<String, String> cols = new LinkedHashMap<>();
            cols.put("external_id", "TEXT");
            for (PromotedColumn pc : promoted) cols.put(pc.name(), pc.type().ddl);
            cols.put("data", "JSONB");
            dynamicTables.register(tenantId, "hybrid:" + tableName, parts[0], parts[1], cols, connectorId);
        } catch (RuntimeException e) {
            LOG.warnf("one-shot registry upsert failed for %s: %s", tableName, e.getMessage());
        }
        try {
            StringBuilder text = new StringBuilder("Table ").append(tableName)
                .append(" (one-shot CSV ingest, ").append(rows).append(" rows). Columns: external_id");
            for (PromotedColumn pc : promoted) {
                text.append(", ").append(pc.name()).append(" (").append(pc.type().name().toLowerCase()).append(")");
            }
            text.append(", data (jsonb). Tags: ").append(String.join(", ", tags)).append(".");
            ExtractedChunk chunk = new ExtractedChunk("table", "csv-oneshot", tableName,
                text.toString(), Map.of("rows", String.valueOf(rows)));
            for (ExtractionSink sink : sinks) {
                sink.index(tenantId, "oneshot:" + tableName, tags, List.of(chunk));
            }
        } catch (RuntimeException e) {
            LOG.warnf("one-shot descriptor indexing failed for %s (rows are durable): %s",
                tableName, e.getMessage());
        }
        for (String tag : move.rediscoverTags()) {
            try {
                // Runs from afterCompletion: the thread's transaction has committed but is still
                // associated with it, and the credential resolver cannot join that. Suspend it, so
                // the resolver opens its own and the reads of the source's AD run with nothing open (#938).
                TenantContext.callAsSystem(() ->
                    QuarkusTransaction.suspendingExisting().call(() -> discovery.rediscover(tenantId, tag)));
            } catch (Exception e) {
                LOG.warnf("one-shot rediscover failed for scope '%s' — manual `lm source rediscover` remains available: %s",
                    tag, e.getMessage());
            }
        }
    }

    /**
     * Upsert the dormant (enabled=false) connector row — one statement serves the first
     * breath and every later one, and the {@code dormant} guard closes the threshold's
     * read-write race fail-closed: losing the race to a configured connector updates
     * nothing, and zero rows IS the 409 (never the constraint's anonymous 500).
     */
    private void upsertDormantConnector(UUID connectorId, long tenantId, String name, String sourceFileName) {
        int rows = em.createNativeQuery(
            "INSERT INTO connector.connector" +
            " (id, tenant_id, org_id, name, type_id, config, enabled)" +
            " VALUES (:id, :tid, 0, :name, 'csv-file', CAST(:cfg AS jsonb), false)" +
            " ON CONFLICT (tenant_id, name) DO UPDATE SET config = EXCLUDED.config" +
            " WHERE connector.config->>'dormant' = 'true'")
            .setParameter("id", connectorId)
            .setParameter("tid", tenantId)
            .setParameter("name", name)
            .setParameter("cfg", buildConnectorConfig(sourceFileName))
            .executeUpdate();
        if (rows == 0) throw new DuplicateIngestName(name);
    }

    /** Upsert the dormant endpoint row carrying the (possibly merged) target config. */
    // S107: the parameters are the endpoint row's columns, passed straight to one statement.
    @SuppressWarnings("java:S107")
    private void upsertDormantEndpoint(UUID endpointId, UUID connectorId, long tenantId, String name,
                                       String sourceFileName, String tableName, String uniqueKey,
                                       List<PromotedColumn> promoted) {
        em.createNativeQuery(
            "INSERT INTO connector.connector_endpoint" +
            " (id, connector_id, tenant_id, org_id, name, path, config, enabled)" +
            " VALUES (:id, :cid, :tid, 0, :name, :path, CAST(:cfg AS jsonb), false)" +
            // The WHERE is index INFERENCE, not a filter: since V45 the endpoint's name rule is a
            // partial unique index (a deleted endpoint stops holding its name), and PostgreSQL only
            // infers a partial index when the statement repeats its predicate. Without it every
            // one-shot ingest — not merely a conflicting one — dies at plan time with "no unique or
            // exclusion constraint matching the ON CONFLICT specification". Same form as
            // InboxResource's ON CONFLICT (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL.
            " ON CONFLICT (connector_id, name) WHERE deleted_at IS NULL DO UPDATE" +
            " SET path = EXCLUDED.path, config = EXCLUDED.config")
            .setParameter("id", endpointId)
            .setParameter("cid", connectorId)
            .setParameter("tid", tenantId)
            .setParameter("name", name)
            .setParameter("path", sourceFileName)
            .setParameter("cfg", buildEndpointConfig(tableName, uniqueKey, promoted))
            .executeUpdate();
    }

    /** Read the full file in batches into the target, honouring {@code form.limit}. Returns rows read. */
    // S107: file + parser config + target + per-run identity travel together through the batch loop.
    @SuppressWarnings("java:S107")
    private int readAndWrite(Path file, Config cfg, MappingTarget target, List<PromotedColumn> promoted,
                             CsvIngestForm form, long tenantId, String name, UUID connectorId) {
        int rowLimit = (form.limit != null && form.limit > 0) ? form.limit : Integer.MAX_VALUE;
        List<String> scope = List.of(form.scope != null ? form.scope : "system");
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        final int[] counter = {0};
        final boolean[] limitReached = {false};

        try {
            new CsvFileReader().readAll(file, cfg, row -> {
                if (limitReached[0]) return;
                if (counter[0] >= rowLimit) {
                    limitReached[0] = true;
                    return;
                }
                batch.add(coerce(row, promoted));
                counter[0]++;
                if (batch.size() >= BATCH_SIZE) {
                    flushBatch(target, batch, tenantId, name, connectorId, scope);
                }
            }, CsvIngestService::refuse);
        } catch (MalformedRow e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Failed to read CSV file: " + file, e);
        }
        if (!batch.isEmpty()) {
            flushBatch(target, batch, tenantId, name, connectorId, scope);
        }
        return counter[0];
    }

    /** Write the buffered batch to the target and clear it. */
    private void flushBatch(MappingTarget target, List<Map<String, Object>> batch, long tenantId,
                            String name, UUID connectorId, List<String> scope) {
        writer.write(target, new ArrayList<>(batch), tenantId, UUID.randomUUID(),
            "csv:" + name, 1, "csv-ingest", connectorId, scope);
        batch.clear();
    }

    // ── Remote file pipeline (SFTP / S3) ─────────────────────────────────────

    /** Everything parsed once from the transport config, shared across the listed files. */
    private record RemotePlan(HookSpec fileFilter, HookSpec preProcess, HookSpec postProcess,
                              AfterProcessAction action, String kind, String connectorLabel, long maxFileSize) {}

    private RemotePlan parsePlan(CsvIngestForm form, Map<String, Object> transportCfg) {
        return new RemotePlan(
            parseHook(transportCfg.get("file_filter")),
            parseHook(transportCfg.get("pre_process")),
            parseHook(transportCfg.get("post_process")),
            parseAction(transportCfg.getOrDefault("after_process", "leave")),
            (String) transportCfg.getOrDefault("kind", LOCAL),
            form.name != null ? form.name : "unknown",
            ((Number) transportCfg.getOrDefault("max_file_size", 500L * 1024 * 1024)).longValue());
    }

    /** One file whose rows are written and which is awaiting its post-commit {@code after_process}. */
    private record Ingested(FileDescriptor descriptor, CsvIngestResult result) {}

    /**
     * Lists the source, writes every file in ONE transaction, and only once that transaction has
     * committed applies {@code after_process} + {@code post_process} to each ingested file.
     *
     * <p><b>The commit is the proof (A8's twin, tribunal 2026-07-26).</b> {@code markProcessed} is a
     * real MOVE/DELETE on the source. Running it inside the transaction — as this loop used to —
     * meant a failure on file <i>k</i> rolled back every row while files 1..<i>k</i>-1 were already
     * gone: unreplayable loss, and the promise that "nothing is half-written" held for the database
     * while the source was half-destroyed. Deferring past the commit makes a crash at worst
     * re-ingestible: the rows are absent and every file is still there.
     */
    private CsvIngestResult executeViaFileSource(CsvIngestForm form, long tenantId,
                                                  Map<String, Object> transportCfg) {
        RemotePlan plan = parsePlan(form, transportCfg);

        try (FileSource source = buildSource(transportCfg, plan.kind())) {
            long listStart = System.currentTimeMillis();
            List<FileDescriptor> files = source.listSince(Instant.EPOCH)
                .filter(f -> hooks.shouldProcess(plan.fileFilter(), f, source))
                .toList();
            metrics.recordListed(plan.kind(), plan.connectorLabel(), files.size(),
                System.currentTimeMillis() - listStart);

            refuseNamedBatch(form, files);

            // Reads and writes only — the source is READ here (each file is downloaded), but never
            // MUTATED: no markProcessed inside the transaction. requiringNew, not joiningExisting:
            // this path OWNS its commit, because archiving may only follow it.
            List<Ingested> ingested = new ArrayList<>();
            CsvIngestResult result = QuarkusTransaction.requiringNew()
                .call(() -> ingestAll(files, source, form, tenantId, plan, ingested));

            // ── committed: the rows are durable, the source may now be archived ──
            applyAfterProcess(source, ingested, transportCfg, plan);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("FileSource " + plan.kind() + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Refuses {@code name} on a multi-file source, before a single byte is downloaded (A11,
     * tribunal 2026-07-26).
     *
     * <p>Every listed file registers its own dormant connector under the form's {@code name}, so
     * the second one violates {@code connector_tenant_id_name_key} and the whole run dies on a raw
     * constraint error — and the default remote gesture is multi-file
     * ({@code lm csv ingest sftp://host/dir/} globs {@code *.csv}).
     *
     * <p>Refused rather than redesigned, per the A4/A5/A9 jurisprudence. Fusing a folder into one
     * dataset means one dormant connector for the whole batch — which a connector manifest with a
     * glob already declares on the scheduled path. The one-shot path is an <i>inspection</i>
     * gesture: it hands back something to inspect, promote, or delete. Nothing is taken away here
     * (this combination never once succeeded); an incomprehensible failure becomes a refusal that
     * teaches. Lifting it = the per-batch registration redesign, demand-triggered.
     */
    private void refuseNamedBatch(CsvIngestForm form, List<FileDescriptor> files) {
        if (form.name == null || files.size() <= 1) return;   // blank was normalized to null in execute
        throw new IllegalArgumentException(String.format(
            "name '%s' would ingest %d files into one dataset, which the one-shot path cannot "
            + "register: each file creates its own dormant connector under that name. "
            + "Point at a single file, or drop the name to get one dataset per file. "
            + "To fuse a folder into one dataset, declare a connector manifest with a glob path.",
            form.name, files.size()));
    }

    /** Writes every listed file through the local pipeline, collecting each one for post-commit archiving. */
    private CsvIngestResult ingestAll(List<FileDescriptor> files, FileSource source, CsvIngestForm form,
                                      long tenantId, RemotePlan plan, List<Ingested> ingested) {
        int totalRowsRead = 0;
        int totalRowsWritten = 0;
        UUID lastConnectorId = UUID.randomUUID();
        UUID lastEndpointId  = UUID.randomUUID();
        String tableName = "ext." + slugify(form.name != null ? form.name : "remote_ingest");
        List<PromoteColumn> promoteCols = new ArrayList<>();
        List<String> allScopes = new ArrayList<>();
        boolean anyRefreshed = false;

        for (FileDescriptor descriptor : files) {
            CsvIngestResult perFile;
            try {
                perFile = ingestRemoteFile(descriptor, source, form, tenantId, plan);
            } catch (IOException e) {
                throw new ConnectorException(
                    "CSV ingest failed for " + descriptor.path() + ": " + e.getMessage(), e);
            }
            ingested.add(new Ingested(descriptor, perFile));
            totalRowsRead    += perFile.rowsRead();
            totalRowsWritten += perFile.rowsWritten();
            if (promoteCols.isEmpty() && perFile.promoteColumns() != null) {
                promoteCols.addAll(perFile.promoteColumns());
            }
            if (perFile.connectorId() != null) lastConnectorId = perFile.connectorId();
            if (perFile.endpointId()  != null) lastEndpointId  = perFile.endpointId();
            if (perFile.tableName()   != null) tableName        = perFile.tableName();
            for (String t : perFile.askableScopes()) if (!allScopes.contains(t)) allScopes.add(t);
            anyRefreshed |= perFile.refreshed();
        }

        return new CsvIngestResult(lastConnectorId, lastEndpointId, tableName,
            totalRowsRead, totalRowsWritten, 0, promoteCols, List.of(),
            allScopes, chatAvailable(), anyRefreshed);
    }

    /**
     * Archives every file whose rows are now committed. A per-file failure WARNs and continues:
     * throwing here could not undo the commit, it would only report failure over durable data.
     * A file left behind is the operator's signal — the same contract the connector path's
     * {@code FileSourceCursorReader.archiveCommitted} settled under A8.
     */
    private void applyAfterProcess(FileSource source, List<Ingested> ingested,
                                   Map<String, Object> transportCfg, RemotePlan plan) {
        for (Ingested i : ingested) {
            // Two independent post-commit side effects, each with its own guard: a failed archive
            // must not silence the notification hook, and neither may be reported as the other.
            try {
                markProcessed(source, i.descriptor(), transportCfg, plan);
            } catch (RuntimeException e) {
                LOG.warnf(e, "after_process %s failed for %s — the rows are committed; the file stays on the source",
                    plan.action(), i.descriptor().path());
            }
            try {
                hooks.postProcess(plan.postProcess(), i.descriptor(), i.result());
            } catch (RuntimeException e) {
                LOG.warnf(e, "post_process hook failed for %s — the rows are committed and unaffected",
                    i.descriptor().path());
            }
        }
    }

    /**
     * Buffer one remote file to a temp path and run it through the local pipeline. Deliberately does
     * NOT mark the file processed: this runs inside the write transaction, and archiving before the
     * commit is exactly the loss A8's twin closed — see {@link #applyAfterProcess}.
     */
    @SuppressWarnings("java:S5443") // temp file in system dir is safe: short-lived, immediately processed, backend-only
    private CsvIngestResult ingestRemoteFile(FileDescriptor descriptor, FileSource source, CsvIngestForm form,
                                             long tenantId, RemotePlan plan)
            throws IOException {
        if (descriptor.size() > plan.maxFileSize()) {
            throw new RuntimeException(String.format(
                "File %s exceeds max_file_size (%d > %d). Configure max_file_size or split the file producer-side.",
                descriptor.path(), descriptor.size(), plan.maxFileSize()));
        }

        Path temp = Files.createTempFile("csv_ingest_remote_", ".csv");
        try {
            bufferToTemp(source, descriptor, plan, temp);

            CsvIngestForm sub = buildSubForm(form, descriptor, temp);
            return executeLocalFile(sub, tenantId, temp.toFile());
        } catch (HookScriptRefusedException | WebApplicationException | MalformedRow e) {
            // A refused pre_process script_ref, a re-ingest of another métier's one-shot (#1212), or a
            // malformed row (#1248) — preserve as-is so it keeps mapping to a clean 4xx at the HTTP
            // boundary, instead of being wrapped into an unmapped 500 below.
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeException(
                "CSV ingest failed for " + descriptor.path() + ": " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best-effort temp cleanup */ }
        }
    }

    /** Download the remote file (applying the pre-process transform) into {@code temp}, recording open metrics. */
    private void bufferToTemp(FileSource source, FileDescriptor descriptor, RemotePlan plan, Path temp)
            throws IOException {
        try (InputStream raw = source.open(descriptor);
             InputStream stream = hooks.transform(plan.preProcess(), descriptor, raw)) {
            Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException e) {
            metrics.recordOpen(plan.kind(), plan.connectorLabel(), "error");
            throw e;
        }
        metrics.recordOpen(plan.kind(), plan.connectorLabel(), "ok");
        metrics.recordBytesRead(plan.kind(), plan.connectorLabel(), Files.size(temp));
    }

    /** A sub-form for the local pipeline that inherits all of the parent form's format hints. */
    private CsvIngestForm buildSubForm(CsvIngestForm form, FileDescriptor descriptor, Path temp) {
        CsvIngestForm sub = new CsvIngestForm();
        sub.file       = temp.toFile();
        sub.name       = (form.name != null) ? form.name : slugify(descriptor.name());
        sub.scope      = form.scope;
        sub.tags       = form.tags;
        sub.sampleRows = form.sampleRows;
        sub.delimiter  = form.delimiter;
        sub.encoding   = form.encoding;
        sub.limit      = form.limit;
        return sub;
    }

    private void markProcessed(FileSource source, FileDescriptor descriptor,
                               Map<String, Object> transportCfg, RemotePlan plan) {
        try {
            source.markProcessed(descriptor, plan.action(), transportCfg);
            metrics.recordAfterProcess(plan.kind(), plan.action().name(), "ok");
        } catch (RuntimeException e) {
            metrics.recordAfterProcess(plan.kind(), plan.action().name(), "error");
            throw e;
        }
    }

    // ── FileSource factory helper ─────────────────────────────────────────────

    /**
     * Builds a {@link FileSource} and wraps any exception in a clear, transport-prefixed error
     * so failures are immediately identifiable (Task 13: failure granularity hardening).
     */
    private FileSource buildSource(Map<String, Object> transportCfg, String kind) {
        try {
            return fileSourceRegistry.build(transportCfg);
        } catch (RuntimeException e) {
            throw new RuntimeException("FileSource " + kind + " failed: " + e.getMessage(), e);
        }
    }

    // ── Hook / action helpers ─────────────────────────────────────────────────

    private HookSpec parseHook(Object node) {
        if (!(node instanceof Map<?, ?> m)) return null;
        return new HookSpec((String) m.get("kind"), (String) m.get("script_ref"));
    }

    private AfterProcessAction parseAction(Object node) {
        return AfterProcessAction.parse(node);
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private Config buildConfig(Path file, CsvIngestForm form) {
        // Auto-detect encoding and delimiter, then override with caller hints if provided.
        Config auto = CsvFileReader.autoDetect(file);
        Charset enc = (form.encoding != null && !form.encoding.isBlank())
            ? Charset.forName(form.encoding) : auto.encoding();
        char delim = (form.delimiter != null && !form.delimiter.isEmpty())
            ? form.delimiter.charAt(0) : auto.delimiter();
        return new Config(enc, delim, auto.hasHeader(), auto.bomStrip(), auto.nullValues(), null);
    }

    private void readWithLimit(Path file, Config cfg, int limit, List<Map<String, Object>> out) {
        var reader = new CsvFileReader();
        int[] n = {0};
        try {
            reader.readAll(file, cfg, row -> {
                if (n[0] < limit) {
                    out.add(row);
                    n[0]++;
                }
            }, CsvIngestService::refuse);
        } catch (MalformedRow e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CSV sample: " + file, e);
        }
    }

    // ── Schema inference ──────────────────────────────────────────────────────

    /**
     * Reserved column names already present in every ext.* table created by HybridJsonbWriter.
     * Promoting a column with one of these names would cause a DDL "column specified more than once" error.
     */
    private static final Set<String> RESERVED_COLUMNS = Set.of(
        "id", "tenant_id", "external_id", "data", "indexed_at", "connector_id", "endpoint_id"
    );

    List<PromotedColumn> inferSchema(List<Map<String, Object>> sample) {   // package-private: pinned by CsvTypeInferenceTest
        if (sample.isEmpty()) return List.of();
        // Collect the widest type per column, preserving header order. Absence is not
        // evidence: an empty cell says nothing about a column's type (146k real orders
        // once landed with NUMERIC amounts as TEXT because of blanks in the sample); a
        // column with no typed value at all stays TEXT.
        Map<String, SqlType> types = new LinkedHashMap<>();
        for (Map<String, Object> row : sample) {
            row.forEach((k, v) -> {
                if (!RESERVED_COLUMNS.contains(k.toLowerCase())) {
                    types.putIfAbsent(k, null);
                    SqlType t = inferType(v);
                    if (t != null) {
                        SqlType prev = types.get(k);
                        types.put(k, prev == null ? t : widerType(prev, t));
                    }
                }
            });
        }
        return types.entrySet().stream()
            .map(e -> new PromotedColumn(e.getKey(), JsonPaths.member(e.getKey()),
                    e.getValue() != null ? e.getValue() : SqlType.TEXT, Optional.empty()))
            .toList();
    }

    /** @return the value's type, or {@code null} when the cell is empty — absence is not evidence. */
    private static SqlType inferType(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.matches("-?\\d+")) return SqlType.BIGINT;
        if (s.matches("-?\\d+\\.\\d+")) return SqlType.DECIMAL;
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return SqlType.BOOLEAN;
        // DATE and TIMESTAMPTZ: the HybridJsonbWriter serializes rows via ObjectMapper (bare instance)
        // which cannot handle java.time types without JavaTimeModule. Keep temporal values as TEXT to
        // avoid serialization failures in RowHasher and HybridJsonbWriter's JSON round-trip.
        // Callers needing typed date columns can promote via the REST connector mapping config.
        return SqlType.TEXT;
    }

    /** Returns the wider of two SqlTypes — TEXT wins over everything. */
    static SqlType widerType(SqlType a, SqlType b) {   // package-private: pinned by CsvTypeInferenceTest
        if (a == b) return a;
        if ((a == SqlType.BIGINT && b == SqlType.DECIMAL)
                || (a == SqlType.DECIMAL && b == SqlType.BIGINT)) {
            return SqlType.DECIMAL;   // a bigint IS a decimal — 0 and 59.4 share a column type
        }
        return SqlType.TEXT;
    }

    // ── JSON config builders ──────────────────────────────────────────────────

    private static Map<String, Object> connectorConfigMap(String fileName) {
        return Map.of(
            "kind", "csv-file",
            "path", fileName,
            "dormant", true
        );
    }

    private String buildConnectorConfig(String fileName) {
        try {
            return JSON.writeValueAsString(connectorConfigMap(fileName));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String buildEndpointConfig(String tableName, String uniqueKey,
                                       List<PromotedColumn> promoted) {
        try {
            return JSON.writeValueAsString(Map.of(
                "target", Map.of(
                    "kind", "hybrid",
                    "table", tableName,
                    "unique_key", uniqueKey,
                    "promote", promoted.stream().map(pc -> Map.of(
                        "name", pc.name(),
                        "path", pc.jsonPath(),
                        "type", pc.type().name().toLowerCase()
                    )).toList()
                )
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Type coercion ─────────────────────────────────────────────────────────

    /**
     * CSV rows arrive as {@code Map<String, String>} (all values are strings).
     * HybridJsonbWriter.bindTyped expects Java types that match the SqlType
     * (e.g. Long for BIGINT, Boolean for BOOLEAN). This method coerces string
     * values to the correct Java type before the row is written.
     */
    private static Map<String, Object> coerce(Map<String, Object> row,
                                              List<PromotedColumn> promoted) {
        if (promoted.isEmpty()) return row;
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (PromotedColumn col : promoted) {
            Object raw = row.get(col.name());
            if (raw == null) continue;
            String s = raw.toString().trim();
            if (s.isEmpty()) {
                out.put(col.name(), null);
                continue;
            }
            try {
                Object coerced = switch (col.type()) {
                    case BIGINT      -> Long.parseLong(s);
                    case INTEGER     -> Integer.parseInt(s);
                    case DECIMAL     -> new BigDecimal(s);
                    case BOOLEAN     -> Boolean.parseBoolean(s);
                    default          -> raw;  // TEXT, DATE, TIMESTAMPTZ, JSONB stay as string
                };
                out.put(col.name(), coerced);
            } catch (Exception ignored) {
                // If coercion fails, leave as string — writer will handle gracefully.
            }
        }
        return out;
    }

    // ── String helpers ────────────────────────────────────────────────────────

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return (i > 0) ? s.substring(0, i) : s;
    }

    private static String slugify(String s) {
        return Identifiers.slug(s, "table");
    }

    /**
     * What {@code lm csv ingest} reads before it promises "askable … try: lm ask": a chat model is
     * resolvable. Not "a sink is": with {@code lumnik.ai.enabled=false} the sink stays (it keeps the
     * corpus consistent, D17) while the chat does not.
     */
    boolean chatAvailable() {
        return analyticChat.isResolvable();
    }
}
