package io.lumnik.hub.source.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.api.sources.CsvIngestResult.PromoteColumn;
import io.lumnik.hub.connector.csv.CsvFileReader;
import io.lumnik.hub.connector.csv.CsvFileReader.Config;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.spec.MappingTarget.HybridJsonb;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;
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
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final int DEFAULT_SAMPLE_ROWS = 100;
    private static final int BATCH_SIZE = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject EntityManager em;
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
        if (LOCAL.equals(kind) && transportCfg.size() == 1) {
            // Only "kind" present — implicit local transport derived from an uploaded file.
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
        String name = slugify(
            (form.name != null && !form.name.isBlank()) ? form.name : stripExt(file.getName())
        );
        String tableName = "ext." + name;

        // Honour caller's format hints, auto-detect the rest, then sample to infer the schema.
        Config cfg = buildConfig(file.toPath(), form);
        int sampleLimit = (form.sampleRows != null && form.sampleRows > 0) ? form.sampleRows : DEFAULT_SAMPLE_ROWS;
        List<Map<String, Object>> sample = new ArrayList<>(sampleLimit);
        readWithLimit(file.toPath(), cfg, sampleLimit, sample);
        List<PromotedColumn> promoted = inferSchema(sample);

        UUID connectorId = UUID.randomUUID();
        insertDormantConnector(connectorId, tenantId, name, file);

        UUID endpointId = UUID.randomUUID();
        String uniqueKey = promoted.isEmpty() ? "$.id" : JsonPaths.member(promoted.get(0).name());
        insertDormantEndpoint(endpointId, connectorId, tenantId, name, file, tableName, uniqueKey, promoted);

        var target = new MappingTarget(null, null, null,
            new HybridJsonb(tableName, uniqueKey, promoted, endpointId));
        int rowsRead = readAndWrite(file.toPath(), cfg, target, promoted, form, tenantId, name, connectorId);
        int rowsWritten = rowsRead;  // all rows read are written (no skip logic in v1)

        List<PromoteColumn> resultCols = promoted.stream()
            .map(pc -> new PromoteColumn(pc.name(), pc.type().name().toLowerCase(), sampleLimit))
            .toList();

        return new CsvIngestResult(
            connectorId, endpointId, tableName,
            rowsRead, rowsWritten, 0,
            resultCols, List.of()
        );
    }

    /** Insert the dormant (enabled=false) connector row for an inspected CSV ingest. */
    private void insertDormantConnector(UUID connectorId, long tenantId, String name, File file) {
        em.createNativeQuery(
            "INSERT INTO connector.connector" +
            " (id, tenant_id, org_id, name, type_id, config, enabled)" +
            " VALUES (:id, :tid, 0, :name, 'csv-file', CAST(:cfg AS jsonb), false)")
            .setParameter("id", connectorId)
            .setParameter("tid", tenantId)
            .setParameter("name", name)
            .setParameter("cfg", buildConnectorConfig(file.getName()))
            .executeUpdate();
    }

    /** Insert the dormant endpoint row carrying the inferred target config. */
    // S107: the parameters are the endpoint row's columns, passed straight to one INSERT.
    @SuppressWarnings("java:S107")
    private void insertDormantEndpoint(UUID endpointId, UUID connectorId, long tenantId, String name,
                                       File file, String tableName, String uniqueKey,
                                       List<PromotedColumn> promoted) {
        em.createNativeQuery(
            "INSERT INTO connector.connector_endpoint" +
            " (id, connector_id, tenant_id, org_id, name, path, config, enabled)" +
            " VALUES (:id, :cid, :tid, 0, :name, :path, CAST(:cfg AS jsonb), false)")
            .setParameter("id", endpointId)
            .setParameter("cid", connectorId)
            .setParameter("tid", tenantId)
            .setParameter("name", name)
            .setParameter("path", file.getName())
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
            });
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
            List<FileDescriptor> files = source.listSince(java.time.Instant.EPOCH)
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
        }

        return new CsvIngestResult(lastConnectorId, lastEndpointId, tableName,
            totalRowsRead, totalRowsWritten, 0, promoteCols, List.of());
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
        } catch (HookScriptRefusedException e) {
            // A refused pre_process script_ref — preserve as-is so it keeps mapping to a clean
            // 4xx at the HTTP boundary, instead of being wrapped into an unmapped 500 below.
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
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CSV sample: " + file, e);
        }
    }

    // ── Schema inference ──────────────────────────────────────────────────────

    /**
     * Reserved column names already present in every ext.* table created by HybridJsonbWriter.
     * Promoting a column with one of these names would cause a DDL "column specified more than once" error.
     */
    private static final java.util.Set<String> RESERVED_COLUMNS = java.util.Set.of(
        "id", "tenant_id", "external_id", "data", "indexed_at", "connector_id", "endpoint_id"
    );

    private List<PromotedColumn> inferSchema(List<Map<String, Object>> sample) {
        if (sample.isEmpty()) return List.of();
        // Collect widest type per column, preserving header order.
        Map<String, SqlType> types = new LinkedHashMap<>();
        for (Map<String, Object> row : sample) {
            row.forEach((k, v) -> {
                if (!RESERVED_COLUMNS.contains(k.toLowerCase())) {
                    types.merge(k, inferType(v), CsvIngestService::widerType);
                }
            });
        }
        return types.entrySet().stream()
            .map(e -> new PromotedColumn(e.getKey(), JsonPaths.member(e.getKey()), e.getValue(), Optional.empty()))
            .toList();
    }

    private static SqlType inferType(Object v) {
        if (v == null) return SqlType.TEXT;
        String s = v.toString().trim();
        if (s.isEmpty()) return SqlType.TEXT;
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
    private static SqlType widerType(SqlType a, SqlType b) {
        if (a == b) return a;
        return SqlType.TEXT;
    }

    // ── JSON config builders ──────────────────────────────────────────────────

    private String buildConnectorConfig(String fileName) {
        try {
            return JSON.writeValueAsString(Map.of(
                "kind", "csv-file",
                "path", fileName,
                "dormant", true
            ));
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
}
