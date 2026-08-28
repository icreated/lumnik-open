package io.lumnik.hub.connector.csv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.MappingEngine;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.LocalFileSource;
import io.lumnik.hub.connector.transport.SeekableSource;
import io.lumnik.hub.sql.SqlIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * CSV file connector. Reads rows from a local file (or glob of files) as
 * {@code Map<String, Object>}.
 *
 * <p>Config keys (YAML):
 * <pre>{@code
 * config:
 *   path: /imports/*.csv      # plain path or glob
 *   delimiter: ","            # optional — auto-detected if absent
 *   encoding: UTF-8           # optional — auto-detected if absent
 *   has-header: true
 *   bom-strip: true
 *   null-values: ['', 'NULL', 'null', '#N/A']
 *   chunk-size: 1000
 * }</pre>
 *
 * <p>Cursor format: {@code {"type":"CsvFile","file":"<path>","rowsProcessed":<N>}}
 * On resume, the connector re-opens the current file and skips already-processed rows,
 * then continues with subsequent files in lexical order.
 */
@ConnectorTypeId(CsvFileConnectorType.TYPE_ID)
@ApplicationScoped
public class CsvFileConnectorType implements ConnectorType {

  private static final String LOCAL = "local";

  public static final String TYPE_ID = "csv-file";

  private static final ObjectMapper M = new ObjectMapper();

  @Inject MappingEngine mappingEngine;
  @Inject FileSourceRegistry fileSourceRegistry;

  @Override
  public String id() { return TYPE_ID; }

  @Override
  public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }

  @Override
  public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL, TriggerMode.POLL); }

  // -----------------------------------------------------------------------
  // Static helper — backwards-compatible transport resolution
  // -----------------------------------------------------------------------

  /**
   * Resolves the transport block from connector config, with backwards compatibility:
   * <ul>
   *   <li><b>Modern</b>: {@code { transport: { kind: local|sftp|s3, ... }, ... }}
   *   <li><b>Legacy</b>: {@code { path: "/imports/*.csv", ... }} → wrapped as
   *       {@code { kind: local, path: ... }}
   * </ul>
   *
   * @throws IllegalArgumentException if neither a {@code transport} block nor a legacy
   *     {@code path} field is present in the config.
   */
  public static Map<String, Object> resolveTransport(Map<String, Object> connectorConfig) {
    Object t = connectorConfig.get("transport");
    if (t instanceof Map<?, ?> m) {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) m;
      return result;
    }
    String legacyPath = (String) connectorConfig.get("path");
    if (legacyPath != null && !legacyPath.isBlank()) {
      Map<String, Object> defaultTransport = new HashMap<>();
      defaultTransport.put("kind", LOCAL);
      defaultTransport.put("path", legacyPath);
      return defaultTransport;
    }
    throw new IllegalArgumentException(
        "csv-file connector config requires either a `transport: {...}` block or a legacy `path` field");
  }

  // -----------------------------------------------------------------------

  @Override
  public void validateConfig(Map<String, Object> config) {
    // Resolve the transport block (modern `transport: {...}` or legacy top-level `path`).
    Map<String, Object> transport;
    try {
      transport = resolveTransport(config);
    } catch (IllegalArgumentException e) {
      throw new ConfigValidationException(e.getMessage());
    }
    Object pathObj = transport.get("path");
    if (!(pathObj instanceof String p) || p.isBlank())
      throw new ConfigValidationException("Missing or blank 'path' in transport config");

    // Local file-existence pre-check only: remote transports (sftp/s3) resolve at run time, and
    // glob patterns can't be pre-checked.
    String kind = String.valueOf(transport.getOrDefault("kind", LOCAL));
    if (LOCAL.equals(kind)) {
      boolean isGlob = p.contains("*") || p.contains("?");
      if (!isGlob && !Files.exists(Path.of(p)))
        throw new ConfigValidationException("CSV file not found: " + p);
    }
  }

  /**
   * Parser overrides from the connector's {@code parser} block. A {@code null} {@code delimiter} or
   * {@code encoding} means "auto-detect that aspect per file"; {@code hasHeader}/{@code bomStrip}/
   * {@code nullValues}/{@code columns} are always honored (with their defaults).
   */
  record ParserOverrides(Character delimiter, Charset encoding, boolean hasHeader,
                         boolean bomStrip, List<String> nullValues, List<String> columns) {}

  /**
   * Reads the {@code parser} sub-map into {@link ParserOverrides}. Never returns null: an unset
   * delimiter/encoding stays {@code null} (→ auto-detected per file by {@link #effectiveConfig}),
   * while the remaining parser fields always carry their configured value — so options like
   * {@code bom_strip}/{@code null_values}/{@code columns} are honored even when delimiter+encoding
   * are left to auto-detection.
   */
  static ParserOverrides buildParserOverrides(Map<String, Object> parser) {
    Object delim = parser.get("delimiter");
    Object enc = parser.get("encoding");
    boolean hasHeader = !Boolean.FALSE.equals(parser.get("has_header"));   // default true
    boolean bomStrip = !Boolean.FALSE.equals(parser.get("bom_strip"));     // default true
    @SuppressWarnings("unchecked")
    List<String> columns = parser.get("columns") instanceof List<?> l ? (List<String>) l : null;
    @SuppressWarnings("unchecked")
    List<String> nullValues = parser.get("null_values") instanceof List<?> n
        ? (List<String>) n : List.of("", "NULL", "null", "#N/A");
    Character delimiter = (delim != null && !delim.toString().isEmpty()) ? delim.toString().charAt(0) : null;
    Charset charset = enc != null ? Charset.forName(enc.toString()) : null;
    return new ParserOverrides(delimiter, charset, hasHeader, bomStrip, nullValues, columns);
  }

  /**
   * Per-file effective {@link CsvFileReader.Config}: auto-detect encoding + delimiter for the file,
   * then apply the explicit overrides on top. This preserves auto-detection for the aspects the user
   * left unset while still honoring every configured parser field.
   */
  static CsvFileReader.Config effectiveConfig(Path file, ParserOverrides o) {
    CsvFileReader.Config base = CsvFileReader.autoDetect(file);
    return new CsvFileReader.Config(
        o.encoding()  != null ? o.encoding()  : base.encoding(),
        o.delimiter() != null ? o.delimiter() : base.delimiter(),
        o.hasHeader(), o.bomStrip(), o.nullValues(), o.columns());
  }

  @Override
  public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
    Map<String, Object> transport = resolveTransport(ctx.config());
    String kind = String.valueOf(transport.getOrDefault("kind", LOCAL));
    Object parser = ctx.config().get("parser");
    @SuppressWarnings("unchecked")
    Map<String, Object> parserCfg = parser instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    ParserOverrides overrides = buildParserOverrides(parserCfg);

    if (LOCAL.equals(kind)) {
      Object pathObj = transport.get("path");
      if (pathObj == null || pathObj.toString().isBlank()) {
        throw new ConfigValidationException("csv-file connector: missing transport.path");
      }
      return new CsvGlobCursorReader(pathObj.toString(), overrides);
    }
    // Pass the FULL config — the remote reader needs top-level after_process / archive_path
    // (the apply handler stores them as siblings of transport, not inside it).
    return new FileSourceCursorReader(ctx.config(), overrides, fileSourceRegistry);
  }

  /**
   * Persists the read rows into the hub table {@code connector.t_<name>} (auto-schema:
   * columns inferred from the source rows). Without this the engine would only count reads.
   * Writes go through {@link MappingEngine} → {@code MultiTargetWriter} → {@code DynamicTableWriter}
   * (RLS + tenant_id + lineage columns), the same machinery jdbc uses.
   *
   * <p>If the connector config carries a {@code mapping} block, its {@code dataset}, {@code row}
   * and {@code cell} hooks are applied before writing — enabling declarative transformations
   * (e.g. Kotlin scripts). Dataset hooks are chunk-scoped by contract (they may rewrite the
   * whole chunk, never more).
   */
  @Override
  public ItemWriter<?> writer(ConnectorContext ctx) {
    String table = ctx.configValue("name", String.class);
    MappingSpec spec = buildSpec(table, ctx.config());
    // Built through MappingEngine.itemWriter — the seam that reaches the ItemWriter.prepare hook
    // (a bare write-only lambda cannot override it). buildSpec carries no rag target today, but a
    // mapping block that declares one hoists its embedding out of the write transaction for free
    // through this same seam, exactly like jdbc-generic.
    return mappingEngine.itemWriter(spec);
  }

  /**
   * Builds a {@link MappingSpec} for writing to {@code connector.t_<table>}.
   * When the connector config contains a {@code mapping} block, its {@code dataset}, {@code row}
   * and {@code cell} hook lists are parsed and included; otherwise the spec has no hooks (1:1 dump).
   */
  @SuppressWarnings("unchecked")
  private static List<MappingTarget.Index> parseIndexes(Map<String, Object> config) {
    Object raw = config.get("indexes");
    if (!(raw instanceof List<?> list)) return List.of();
    var out = new ArrayList<MappingTarget.Index>(list.size());
    for (Object o : list) {
      if (o instanceof Map<?, ?> m && m.get("columns") instanceof List<?> cols && !cols.isEmpty()) {
        out.add(new MappingTarget.Index(
            cols.stream().map(String::valueOf).toList(), Boolean.TRUE.equals(m.get("unique"))));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  static MappingSpec buildSpec(String table, Map<String, Object> config) {
    // DDL-injection boundary (layer 2, alongside the escaping fix in RlsDdl): `table` is prefixed
    // with `t_` and handed straight to DDL that DynamicTableWriter executes on the BYPASSRLS admin
    // datasource, so refuse anything that is not a plain identifier before that happens.
    // CsvFileConnectorApplyHandler already slugs the manifest name before it reaches this method,
    // but buildSpec is also directly callable, so it must not rely on that upstream sanitization.
    if (!SqlIdentifiers.isPlainIdentifier(table)) {
      throw new ConfigValidationException(
          "config key 'name' must be a plain identifier (letters, digits, underscore; not starting with a digit): " + table);
    }
    MappingTarget.Table tbl = new MappingTarget.Table(
        "connector", "t_" + table, /* autoSchema */ true,
        MappingTarget.OnConflict.SKIP, parseIndexes(config));
    MappingTarget target = new MappingTarget(null, tbl, null, null);

    List<HookInvocation> datasetHooks = List.of();
    List<HookInvocation> cellHooks = List.of();
    List<HookInvocation> rowHooks  = List.of();
    Object mappingObj = config.get("mapping");
    if (mappingObj instanceof Map<?, ?> mappingMap) {
      JsonNode mappingNode = M.valueToTree(mappingMap);
      datasetHooks = parseHooks(mappingNode.path("dataset"));
      cellHooks = parseHooks(mappingNode.path("cell"));
      rowHooks  = parseHooks(mappingNode.path("row"));
    }
    return new MappingSpec(
        "csv:" + table, 1, target, MappingSpec.StreamMode.STREAM,
        "dump of " + table,
        datasetHooks, rowHooks, cellHooks, ErrorPolicy.soft());
  }

  /** Parses a JSON array of single-key hook objects into {@link HookInvocation} list. */
  private static List<HookInvocation> parseHooks(JsonNode arr) {
    if (arr == null || !arr.isArray()) return List.of();
    List<HookInvocation> out = new ArrayList<>(arr.size());
    for (JsonNode entry : arr) {
      if (entry.isObject() && entry.size() == 1) {
        var f = entry.fields().next();
        out.add(new HookInvocation(f.getKey(), f.getValue()));
      }
    }
    return out;
  }

  // -----------------------------------------------------------------------

  /**
   * Glob + row-based cursor reader.
   *
   * <p>Cursor JSON: {@code {"type":"CsvFile","file":"<path>","rowsProcessed":<N>}} where {@code N}
   * counts <em>events consumed</em> — good rows plus isolated malformed rows — so resume never
   * re-reads or re-dead-letters an event already processed.
   *
   * <p><b>Contract (A9, stated 2026-07-25):</b> resume positions by the cursor file's index in
   * the LEXICALLY sorted glob result — the drop directory must grow with monotonically
   * increasing names (date-prefixed files). A file arriving later with a lexically earlier
   * name sorts before the cursor and is treated as already ingested, silently. Lifting this
   * means per-file completion tracking (the FileSource cursor model) — a redesign, deliberate
   * and demand-triggered, same posture as local after_process (refused at validate).
   *
   * <p>Algorithm per {@code read()} call:
   * <ol>
   *   <li>Resolve glob → sorted file list (cached after first call).</li>
   *   <li>Decode cursor → current file path + events already consumed.</li>
   *   <li>Locate current file index in the list (start at 0 if cursor empty).</li>
   *   <li>Stream the file from the already-consumed position, filling the chunk with good rows and
   *       buffering malformed rows for the DLQ, stopping once the chunk is full.</li>
   *   <li>If the file is exhausted before {@code chunkSize}, advance to next file (events=0).</li>
   *   <li>Return HasMore with updated cursor, or EndOfStream when all files consumed.</li>
   * </ol>
   */
  static class CsvGlobCursorReader implements ItemReader<Map<String, Object>> {

    private static final Logger LOG = Logger.getLogger(CsvGlobCursorReader.class);

    private final String pathPattern;
    private final ParserOverrides overrides;
    /** One reader per CsvGlobCursorReader lifetime, so its parse-work counter spans the run. */
    private final CsvFileReader reader = new CsvFileReader();
    /**
     * The transport behind this reader's files. Local is always seekable, but the fast-path
     * guard still checks the {@link SeekableSource} capability structurally — absence of the
     * interface IS the fallback configuration, and the remote wiring reuses the same fork.
     */
    private final FileSource source;
    private List<Path> resolvedFiles;
    private final List<MalformedRecord> malformedBuffer = new ArrayList<>();

    CsvGlobCursorReader(String pathPattern, ParserOverrides overrides) {
      this.pathPattern = pathPattern;
      this.overrides = overrides;
      this.source = new LocalFileSource(pathPattern);
    }

    /** Test-visible: records tokenized across every window this reader has run (O(R) proof). */
    long rowsTokenized() {
      return reader.rowsTokenized();
    }

    @Override
    public List<MalformedRecord> drainMalformed() {
      var out = List.copyOf(malformedBuffer);
      malformedBuffer.clear();
      return out;
    }

    /**
     * Three movements: resolve where the cursor left off, walk the files filling one chunk, encode
     * what remains. The loop is the only part that carries state, so it is the only part extracted.
     */
    @Override
    public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor, ConnectorContext ctx) {
      ensureResolved();
      if (resolvedFiles.isEmpty()) return new ReadResult.EndOfStream<>(List.of());

      CsvCursor start = cursor.isEmpty()
          ? new CsvCursor(resolvedFiles.get(0).toString(), 0L)
          : decodeCursor(cursor);

      int fileIndex = indexOfFile(start.file());
      // The cursor names a file this run no longer resolves — it was archived, moved or deleted
      // between runs. Nothing left to read from here.
      if (fileIndex < 0) return new ReadResult.EndOfStream<>(List.of());

      List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
      Walk walk = fillChunk(chunk, chunkSize, fileIndex, start);

      if (walk.fileIndex() >= resolvedFiles.size()) {
        return new ReadResult.EndOfStream<>(chunk);
      }
      return new ReadResult.HasMore<>(chunk, encodeCursor(walk.file(), walk.consumed(), walk.last()));
    }

    /** Where the walk stopped: the file it is on, how much of it is consumed, and its last window. */
    private record Walk(int fileIndex, String file, long consumed, WindowOutcome last) {}

    /**
     * Walks files from {@code fileIndex}, one window at a time, until the chunk is full or the files
     * run out. Good rows stream straight into {@code chunk} and malformed ones into the buffer, so
     * the unread tail of a large file is never parsed or materialized.
     */
    private Walk fillChunk(List<Map<String, Object>> chunk, int chunkSize, int fileIndex, CsvCursor start) {
      String currentFile = start.file();
      long recordsConsumed = start.rowsProcessed();   // events consumed (good + malformed)
      WindowOutcome last = null;

      while (chunk.size() < chunkSize && fileIndex < resolvedFiles.size()) {
        Path file = resolvedFiles.get(fileIndex);
        boolean isCursorFile = file.toString().equals(currentFile);
        long skip = isCursorFile ? recordsConsumed : 0L;

        last = readOneWindow(file, skip, chunkSize - chunk.size(), chunk,
            isCursorFile ? start : null);

        if (last.exhausted()) {
          fileIndex++;
          currentFile = fileIndex < resolvedFiles.size() ? resolvedFiles.get(fileIndex).toString() : "";
          recordsConsumed = 0;
        } else {
          currentFile = file.toString();
          recordsConsumed = skip + last.consumed();
          break;
        }
      }
      return new Walk(fileIndex, currentFile, recordsConsumed, last);
    }

    /**
     * One window's outcome: the plain consumed/exhausted pair plus, when the window ran on the
     * checkpoint path, the cursor payload — ABSOLUTE anchor, headers, version token. All-null
     * checkpoint fields → the cursor stays legacy-shaped.
     */
    private record WindowOutcome(long consumed, boolean exhausted,
                                 Long anchorByte, Long charDelta, Long lineBase,
                                 List<String> headers, CsvCursor.Version version) {}

    /**
     * THE FORK — guarded fast path vs. the present (lot-5b design (A), ratified doctrine):
     * the fast path is opt-in behind verified preconditions — seekable transport AND
     * checkpoint-allowlisted charset AND (for a resume) a complete checkpoint whose version
     * token still matches the file's bytes. ANY precondition false, or ANY failure while
     * seeking/resuming, drops to today's skip-parse path, untouched — the worst case is the
     * present, never worse. Correctness lives in the fallback; the fast path only ever buys
     * speed.
     */
    private WindowOutcome readOneWindow(Path file, long skip, int limit,
                                        List<Map<String, Object>> chunk, CsvCursor incoming) {
      CsvFileReader.Config cfg = effectiveConfig(file, overrides);
      if (source instanceof SeekableSource seekable && CheckpointingReader.canCheckpoint(cfg.encoding())) {
        FileDescriptor now = describe(file);
        if (now != null) {
          WindowOutcome fast = null;
          if (incoming != null && incoming.hasCheckpoint() && incoming.version().matches(now)) {
            fast = tryResumedWindow(seekable, now, incoming, cfg, limit, chunk);
          } else if (skip == 0) {
            // Start of a file (fresh run, or the next file of the glob): read it through the
            // checkpointing reader from byte 0 — same parse, same order — so the cursor can
            // carry a checkpoint for the NEXT window. A mid-file legacy cursor (skip > 0)
            // deliberately does NOT take this route: old cursors ride the fallback, per design.
            fast = tryFirstWindow(seekable, now, cfg, limit, chunk);
          }
          if (fast != null) return fast;
        }
      }
      // FALLBACK — today's skip-parse path, byte for byte.
      CsvFileReader.Window w = readWindowPlain(file, cfg, skip, limit, chunk);
      return new WindowOutcome(w.consumed(), w.fileExhausted(), null, null, null, null, null);
    }

    /** Resume at the cursor's checkpoint. Returns null (→ fallback) if seek/resume fails. */
    private WindowOutcome tryResumedWindow(SeekableSource seekable, FileDescriptor now,
                                           CsvCursor incoming, CsvFileReader.Config cfg,
                                           int limit, List<Map<String, Object>> chunk) {
      CheckpointingReader rdr = openResumed(seekable, now, cfg, incoming);
      if (rdr == null) return null;
      // The header line is behind the anchor: parse with the cursor's captured column names,
      // the same headerless-mode machinery has-header=false files always used.
      CsvFileReader.Config headerless = new CsvFileReader.Config(
          cfg.encoding(), cfg.delimiter(), false, cfg.bomStrip(), cfg.nullValues(), incoming.headers());
      CsvFileReader.CheckpointedWindow w =
          readWindowCheckpointed(rdr, now, headerless, limit, incoming.lineBase(), chunk);
      // Rows are flowing again, so parse failures past this point propagate exactly as on the
      // plain path — falling back NOW would deliver the same rows twice.
      return outcome(w, incoming.anchorByte(), now);
    }

    /** First window of a file through the checkpointing reader. Null (→ fallback) if open fails. */
    private WindowOutcome tryFirstWindow(SeekableSource seekable, FileDescriptor now,
                                         CsvFileReader.Config cfg, int limit,
                                         List<Map<String, Object>> chunk) {
      CheckpointingReader rdr;
      InputStream in = null;
      try {
        in = seekable.openAt(now, 0L);
        rdr = new CheckpointingReader(in, cfg.encoding());
      } catch (Exception openFailed) {
        closeQuietly(in);
        LOG.debugf(openFailed, "csv fast path unavailable for %s — using the skip-parse fallback", now.path());
        return null;
      }
      CsvFileReader.CheckpointedWindow w = readWindowCheckpointed(rdr, now, cfg, limit, 0L, chunk);
      return outcome(w, 0L, now);
    }

    /** openAt + resume; any failure here is pre-parse (no rows delivered) → safe to fall back. */
    private CheckpointingReader openResumed(SeekableSource seekable, FileDescriptor now,
                                            CsvFileReader.Config cfg, CsvCursor incoming) {
      InputStream in = null;
      try {
        in = seekable.openAt(now, incoming.anchorByte());
        return CheckpointingReader.resume(in, cfg.encoding(), incoming.charDelta());
      } catch (Exception seekOrResumeFailed) {
        closeQuietly(in);
        LOG.warnf(seekOrResumeFailed,
            "csv checkpoint resume failed for %s at byte %d — using the skip-parse fallback",
            now.path(), incoming.anchorByte());
        return null;
      }
    }

    private CsvFileReader.CheckpointedWindow readWindowCheckpointed(
        CheckpointingReader rdr, FileDescriptor now, CsvFileReader.Config cfg,
        int limit, long lineBase, List<Map<String, Object>> chunk) {
      try {
        return reader.readWindowCheckpointed(rdr, now.path(), cfg,
            0L, limit, lineBase, chunk::add, malformedBuffer::add);
      } catch (Exception e) {
        throw new RuntimeException("Failed to read CSV: " + now.path(), e);
      }
    }

    private WindowOutcome outcome(CsvFileReader.CheckpointedWindow w, long baseAnchor, FileDescriptor now) {
      // The window's checkpoint is relative to the stream it ran on; the cursor stores the
      // absolute byte position, so the next resume seeks the file directly.
      boolean checkpointed = w.nextCheckpoint() != null && !w.headers().isEmpty();
      return new WindowOutcome(w.consumed(), w.fileExhausted(),
          checkpointed ? baseAnchor + w.nextCheckpoint().anchorByte() : null,
          checkpointed ? w.nextCheckpoint().charDelta() : null,
          checkpointed ? w.nextLineBase() : null,
          checkpointed ? w.headers() : null,
          checkpointed ? CsvCursor.Version.of(now) : null);
    }

    /** Read up to {@code limit} rows from {@code file} starting at {@code skip}, translating the
     *  reader's checked failure into a runtime one. */
    private CsvFileReader.Window readWindowPlain(Path file, CsvFileReader.Config cfg, long skip,
                                                 int limit, List<Map<String, Object>> chunk) {
      try {
        return reader.readWindow(file, cfg, skip, limit, chunk::add, malformedBuffer::add);
      } catch (Exception e) {
        throw new RuntimeException("Failed to read CSV: " + file, e);
      }
    }

    /** The version-token source: stat the file. Null (→ fallback) when the stat fails. */
    private static FileDescriptor describe(Path p) {
      try {
        return new FileDescriptor(p.toString(), Files.size(p),
            Files.getLastModifiedTime(p).toInstant(), null);
      } catch (IOException statFailed) {
        return null;
      }
    }

    private static void closeQuietly(InputStream in) {
      if (in != null) {
        try { in.close(); } catch (IOException ignored) { /* best-effort */ }
      }
    }

    /** Resolve the glob to a sorted file list on first use; cached for the reader's lifetime. */
    private void ensureResolved() {
      if (resolvedFiles != null) return;
      try {
        resolvedFiles = CsvGlobResolver.resolve(pathPattern);
      } catch (IOException e) {
        throw new RuntimeException("Failed to resolve path pattern: " + pathPattern, e);
      }
    }

    private int indexOfFile(String filePath) {
      for (int i = 0; i < resolvedFiles.size(); i++) {
        if (resolvedFiles.get(i).toString().equals(filePath)) return i;
      }
      return -1;
    }

    private CsvCursor decodeCursor(ConnectorCursor cursor) {
      var node = cursor.value();
      try {
        return CsvCursor.fromJson(node.toString());
      } catch (Exception badCheckpointFields) {
        // Malformed NEW fields must not cost the row position: keep the legacy pair (→ the
        // skip-parse fallback), and only a cursor with neither field restarts from scratch.
        try {
          return new CsvCursor(node.get("file").asText(), node.get("rowsProcessed").asLong());
        } catch (Exception e) {
          return CsvCursor.empty();
        }
      }
    }

    /**
     * Writes the extended (checkpoint) shape only when the window that produced {@code last}
     * proved the fast path can honour it — it ran on a seekable, allowlist-charset stream and
     * named a resume point. Every other window keeps writing the legacy two-field shape.
     */
    private ConnectorCursor encodeCursor(String file, long rowsProcessed, WindowOutcome last) {
      CsvCursor out = (last != null && last.anchorByte() != null && last.charDelta() != null
              && last.lineBase() != null && last.headers() != null && last.version() != null)
          ? new CsvCursor(file, rowsProcessed, last.anchorByte(), last.charDelta(),
              last.lineBase(), last.headers(), last.version())
          : new CsvCursor(file, rowsProcessed);
      ObjectNode n = (ObjectNode) M.valueToTree(out);
      n.put("type", "CsvFile");
      return new ConnectorCursor(n, Instant.now());
    }
  }
}
