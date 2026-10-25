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
import io.lumnik.hub.connector.mapping.carry.StaleCarryException;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.writer.UnseenRowSweeper;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
  @Inject UnseenRowSweeper sweeper;
  @Inject ConnectorDeadLetterRepository deadLetters;

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
      boolean isGlob = CsvGlobResolver.isGlob(p);
      // This message echoes a SERVER-SIDE path, and ConfigValidationException is now dressed as a
      // 400 by ConfigValidationExceptionMapper. It is safe only because nothing in production calls
      // csv's validateConfig — it never reaches a caller. If a future change makes
      // CsvFileConnectorApplyHandler re-run it (the "validator == runtime" tightening kafka already
      // has), this path becomes integrator-visible and NO guard would fail. Say what is missing
      // without the resolved path first.
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
    Charset charset = enc != null ? Charset.forName(enc.toString()) : null;
    // #1415: one line = one row, one column (`line` unless renamed); the validator refuses the keys this replaces.
    if (Boolean.TRUE.equals(parser.get("lines"))) {
      return new ParserOverrides(CsvFileReader.Config.LINES, charset, false, bomStrip, nullValues,
          columns != null && !columns.isEmpty() ? columns : List.of("line"));
    }
    Character delimiter = (delim != null && !delim.toString().isEmpty()) ? delim.toString().charAt(0) : null;
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
      // #1452: a mapping whose cell hooks carry values row to row is reset at each file, so it reads one per chunk.
      JsonNode mapping = mappingOf(ctx.config());
      boolean oneFilePerChunk = mapping != null && mappingEngine.carriesState(HookInvocation.parseList(mapping, "cell"));
      return new CsvGlobCursorReader(pathObj.toString(), overrides, CsvFullRefresh.stateFile(ctx.config()).isPresent(),
          oneFilePerChunk);
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
   * The full refresh (#1195): a run that read the whole of one local file removes the rows the file
   * no longer holds — see {@link CsvFullRefresh} for when, and for the two guards.
   */
  @Override
  public StreamEnd endOfStream(ConnectorContext ctx) {
    // A file skipped as unchanged was not read: nothing to sweep, only something to say (#1371).
    long unchanged = ctx.scope().resource(new UnchangedKey(), Unchanged::new).files;
    if (unchanged > 0) return new StreamEnd.Unchanged(unchanged);
    // A remote file read and left where it was (#1382): a remote transport removes no rows, nothing else to say.
    // Exclusive with Unchanged by transport: only the local reader counts unchanged files, only a remote one
    // archives (a local connector's after_process is refused).
    long notArchived = ctx.scope().resource(new FileSourceCursorReader.NotArchivedKey(),
        FileSourceCursorReader.NotArchived::new).paths.size();
    if (notArchived > 0) return new StreamEnd.NotArchived(notArchived);
    Optional<String> file = CsvFullRefresh.stateFile(ctx.config());
    if (file.isEmpty()) return new StreamEnd.Removed(0);
    // Built here when no read of this run started the file (an unchanged file is not read): no sweep.
    CsvFullRefresh.Pass pass = ctx.scope().resource(new CsvFullRefresh.PassKey(file.get()),
        () -> new CsvFullRefresh.Pass(false, null));
    if (!pass.fromStart) return new StreamEnd.Removed(0);
    String table = ctx.configValue("name", String.class);
    MappingTarget.Table spec = tableOf(table, ctx.config(), true);
    UnseenRowSweeper.Count n = sweeper.count(spec, ctx.tenantId(), sourceOf(table), ctx.runId());
    // The guards speak only when rows would go: a first run, or one that met every row, has
    // nothing to remove and nothing to refuse.
    if (n.unseen() == 0) return new StreamEnd.Removed(0);
    if (pass.changed) return new StreamEnd.Refused(CsvFullRefresh.CHANGED_WHILE_READ);
    if (pass.events == 0) return new StreamEnd.Refused(CsvFullRefresh.EMPTY_FILE);
    long unread = deadLetters.countByRun(ctx.runId());
    if (unread > 0) return CsvFullRefresh.deadLettered(unread, ctx.runId());
    double max = CsvFullRefresh.maxRemoved(ctx.config());
    if (n.shrinksBeyond(max)) return CsvFullRefresh.refused(n, max);
    // Every unseen version goes; the run reports the rows the source lost, not the versions replaced (#1544).
    long deleted = sweeper.remove(spec, ctx.tenantId(), sourceOf(table), ctx.runId());
    return new StreamEnd.Removed(Math.min(deleted, n.lost()));
  }

  /** The {@code _source} a csv run stamps: its mapping id, which {@link #buildSpec} names. */
  static String sourceOf(String table) {
    return "csv:" + table;
  }

  /** The hub table a csv connector writes; {@code marksSeen} when its source is one file (#1195). */
  static MappingTarget.Table tableOf(String table, Map<String, Object> config, boolean marksSeen) {
    return new MappingTarget.Table("connector", "t_" + table, /* autoSchema */ true,
        MappingTarget.OnConflict.SKIP, parseIndexes(config), marksSeen);
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

  /** The connector's {@code mapping} block, or null without one — read the same way by the writer's spec and the reader. */
  private static JsonNode mappingOf(Map<String, Object> config) {
    return config.get("mapping") instanceof Map<?, ?> mapping ? M.valueToTree(mapping) : null;
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
    MappingTarget target = new MappingTarget(null,
        tableOf(table, config, CsvFullRefresh.stateFile(config).isPresent()), null, null);

    List<HookInvocation> datasetHooks = List.of();
    List<HookInvocation> cellHooks = List.of();
    List<HookInvocation> rowHooks  = List.of();
    ErrorPolicy onError = ErrorPolicy.soft();
    JsonNode mappingNode = mappingOf(config);
    if (mappingNode != null) {
      // Shared with MappingLoader so csv and manifest/jdbc mappings refuse a malformed hook the same
      // way (this used to silently DROP a bad entry, where MappingLoader threw).
      datasetHooks = HookInvocation.parseList(mappingNode, "dataset");
      cellHooks = HookInvocation.parseList(mappingNode, "cell");
      rowHooks  = HookInvocation.parseList(mappingNode, "row");
      onError = ErrorPolicy.parse(mappingNode.path("on-error"));
    }
    return new MappingSpec(
        sourceOf(table), 1, target, MappingSpec.StreamMode.STREAM,
        "dump of " + table,
        datasetHooks, rowHooks, cellHooks, onError);
  }

  // -----------------------------------------------------------------------

  /** What the run's scope keys the folder listing by: one pattern, one listing. */
  record LocalListingKey(String pathPattern) {}

  /** The run-scope key of {@link Unchanged}: one per run, the read counts and endOfStream reports. */
  record UnchangedKey() {}

  /** One file's bytes, as its identity names them: a write changes the ctime, whatever a copy restores (#1452). */
  record ContentKey(String path, long size, String lastModified, String changed) {}

  /** A file's SHA-256, kept for the run. */
  static final class Content implements AutoCloseable {
    final String sha256;

    Content(String sha256) {
      this.sha256 = sha256;
    }

    @Override
    public void close() {
      // holds no resource
    }
  }

  /** Files the run did not read because they had not changed since they were read to the end (#1371). */
  static final class Unchanged implements AutoCloseable {
    long files;

    @Override
    public void close() {
      // holds no resource; the run scope only keeps AutoCloseables
    }
  }

  /** The folder as the run's first chunk found it. Holds no resource; the scope only keeps {@code AutoCloseable}s. */
  record LocalListing(List<Path> files) implements AutoCloseable {
    @Override
    public void close() {
      // nothing to release
    }
  }

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
   *   <li>Take the sorted file list from the run's scope: the glob is listed once, at the first chunk
   *       of the run, and every later chunk reads that same list.</li>
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
    /** One plain local file: the run records whether it started that file at its first row (#1195). */
    private final boolean holdsTheSourceState;
    /** #1452: a mapping that carries values row to row gets one file per chunk — it cannot see a file end inside one. */
    private final boolean oneFilePerChunk;
    /** Whether the last {@link #read} began at a file's first record. */
    private boolean startsAFile;
    /** The run's scope, where a carrying mapping's content fingerprints are kept. */
    private RunScope scope;
    private List<Path> resolvedFiles;
    private final List<MalformedRecord> malformedBuffer = new ArrayList<>();

    CsvGlobCursorReader(String pathPattern, ParserOverrides overrides) {
      this(pathPattern, overrides, false);
    }

    CsvGlobCursorReader(String pathPattern, ParserOverrides overrides, boolean holdsTheSourceState) {
      this(pathPattern, overrides, holdsTheSourceState, false);
    }

    CsvGlobCursorReader(String pathPattern, ParserOverrides overrides, boolean holdsTheSourceState,
                        boolean oneFilePerChunk) {
      this.pathPattern = pathPattern;
      this.overrides = overrides;
      this.source = new LocalFileSource(pathPattern);
      this.holdsTheSourceState = holdsTheSourceState;
      this.oneFilePerChunk = oneFilePerChunk;
    }

    @Override
    public boolean startsAFile() {
      return startsAFile;
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
      startsAFile = false;
      scope = ctx.scope();
      resolveFiles(ctx);
      if (resolvedFiles.isEmpty()) return new ReadResult.EndOfStream<>(List.of());

      CsvCursor start = cursor.isEmpty()
          ? new CsvCursor(resolvedFiles.get(0).toString(), 0L)
          : decodeCursor(cursor);

      int fileIndex = indexOfFile(start.file());
      if (fileIndex < 0) {
        fileIndex = firstFileAfterGone(start);
        if (fileIndex < 0) return new ReadResult.EndOfStream<>(List.of());
        start = new CsvCursor(resolvedFiles.get(fileIndex).toString(), 0L);
      } else if (start.fullyRead()) {
        // The run that wrote this cursor read the file to its end. Unchanged bytes: nothing to
        // read, go on with the next file. Anything else (replaced in place, grown, unreadable):
        // read it again in full — the table deduplicates, and a replaced file must not be
        // resumed at a row count that belonged to its previous content.
        // Size and mtime survive a copy that keeps them (docker cp, cp -p, rsync -t): with no etag,
        // the content's fingerprint decides (#1371). A token from before it carries none.
        Path file = resolvedFiles.get(fileIndex);
        FileDescriptor now = describe(file);
        if (start.version() != null && start.version().matches(now, () -> sha256(file))) {
          ctx.scope().resource(new UnchangedKey(), Unchanged::new).files++;
          if (++fileIndex >= resolvedFiles.size()) return new ReadResult.EndOfStream<>(List.of());
          start = new CsvCursor(resolvedFiles.get(fileIndex).toString(), 0L);
        } else {
          start = new CsvCursor(start.file(), 0L);
        }
      }

      // #1452: a chunk that begins at a file's first record starts a carrying mapping empty; one that resumes mid-way
      // does so on the same bytes only.
      startsAFile = start.rowsProcessed() == 0;
      if (oneFilePerChunk && !startsAFile) refuseChangedBytes(start, resolvedFiles.get(fileIndex));

      // The run's first read decides it, once: a later chunk resumes mid-file by construction.
      CsvFullRefresh.Pass pass = null;
      FileDescriptor version = null;
      if (holdsTheSourceState) {
        boolean fromStart = startsAFile;
        FileDescriptor first = version = describe(resolvedFiles.get(fileIndex));
        pass = ctx.scope().resource(new CsvFullRefresh.PassKey(pathPattern), () -> new CsvFullRefresh.Pass(fromStart, first));
      }

      List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
      Walk walk = fillChunk(chunk, chunkSize, fileIndex, start);
      if (pass != null) pass.observe(version, describe(resolvedFiles.get(fileIndex)), chunk.size() + malformedBuffer.size());

      if (walk.fileIndex() >= resolvedFiles.size()) {
        // The list is read to its end. The run ends here, so it hands over the finished position
        // itself: without it the stored cursor stays on the last file mid-way, and archiving that
        // file would lose its place. A read that found nothing new leaves the stored one alone.
        if (chunk.isEmpty() && malformedBuffer.isEmpty()) return new ReadResult.EndOfStream<>(chunk);
        return new ReadResult.EndOfStream<>(chunk,
            wrap(CsvCursor.finished(walk.file(), walk.consumed(),
                walk.lastStat() == null ? null
                    : CsvCursor.Version.of(walk.lastStat(), sha256(Path.of(walk.file()))))));
      }
      return new ReadResult.HasMore<>(chunk, encodeCursor(walk.file(), walk.consumed(), walk.last(), walk.lastStat()));
    }

    /** Where the walk stopped: the file it is on, how much of it is consumed, and its last window. */
    record Walk(int fileIndex, String file, long consumed, WindowOutcome last, FileDescriptor lastStat) {}

    /**
     * Walks files from {@code fileIndex}, one window at a time, until the chunk is full or the files
     * run out. Good rows stream straight into {@code chunk} and malformed ones into the buffer, so
     * the unread tail of a large file is never parsed or materialized.
     */
    Walk fillChunk(List<Map<String, Object>> chunk, int chunkSize, int fileIndex, CsvCursor start) {
      String currentFile = start.file();
      long recordsConsumed = start.rowsProcessed();   // events consumed (good + malformed)
      WindowOutcome last = null;
      FileDescriptor lastStat = null;   // taken BEFORE the file is read: a change during the read shows next run

      while (chunk.size() < chunkSize && fileIndex < resolvedFiles.size()) {
        Path file = resolvedFiles.get(fileIndex);
        lastStat = describe(file);
        boolean isCursorFile = file.toString().equals(currentFile);
        long skip = isCursorFile ? recordsConsumed : 0L;

        // The cursor's checkpoint belongs to the cursor's own file: a later file of the walk starts
        // from byte 0 even when its size and modification time happen to equal the cursor file's.
        boolean startsFromCursor = file.toString().equals(start.file());
        last = readOneWindow(file, skip, chunkSize - chunk.size(), chunk,
            startsFromCursor ? start : null);

        if (last.exhausted()) {
          fileIndex++;
          if (fileIndex < resolvedFiles.size()) {
            currentFile = resolvedFiles.get(fileIndex).toString();
            recordsConsumed = 0;
            // #1452: the next file starts the next chunk — unless this one gave no row: an empty chunk reads as the
            // end of the run, so the chunk goes on into the next file, whose first row is then its first.
            if (oneFilePerChunk) {
              if (!chunk.isEmpty()) break;
              startsAFile = true;
            }
          } else {
            // the list is done: the walk reports the last file and its row count
            currentFile = file.toString();
            recordsConsumed = skip + last.consumed();
          }
        } else {
          currentFile = file.toString();
          recordsConsumed = skip + last.consumed();
          break;
        }
      }
      return new Walk(fileIndex, currentFile, recordsConsumed, last, lastStat);
    }

    /**
     * One window's outcome: the plain consumed/exhausted pair plus, when the window ran on the
     * checkpoint path, the cursor payload — ABSOLUTE anchor, headers, version token. All-null
     * checkpoint fields → the cursor stays legacy-shaped.
     */
    record WindowOutcome(long consumed, boolean exhausted,
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

    /**
     * The file's content fingerprint, hex; null when it cannot be read. A finished token then carries
     * none and is judged on its stat; at a skip check, an unreadable file counts as changed and is read.
     */
    private static String sha256(Path p) {
      try (InputStream in = Files.newInputStream(p)) {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        return HexFormat.of().formatHex(md.digest());
      } catch (IOException | NoSuchAlgorithmException unreadable) {
        return null;
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

    /**
     * The sorted file list of this run: listed on the first ask, then held by the run's scope. A file that
     * appears afterwards waits for the next run; one that disappears fails the read that reaches it.
     */
    private void resolveFiles(ConnectorContext ctx) {
      resolvedFiles = ctx.scope().resource(new LocalListingKey(pathPattern), () -> listing(pathPattern)).files();
    }

    /** A Supplier cannot throw a checked exception: the failure travels as it always has, unchecked. */
    private static LocalListing listing(String pathPattern) {
      try {
        return new LocalListing(List.copyOf(CsvGlobResolver.resolve(pathPattern)));
      } catch (IOException e) {
        throw new RuntimeException("Failed to resolve path pattern: " + pathPattern, e);
      }
    }

    /**
     * The cursor names a file this run no longer resolves: archived, moved or deleted between
     * runs. Returns the index of the first file sorting after it, or -1 when none does (it was the
     * last file, the run is done). A finished cursor knows its file was read in full, so the run
     * goes on there. Any other cursor cannot tell whether the file was fully read, so with files
     * waiting after it the reader refuses rather than guess: ending quietly would leave those
     * files unread for ever, resuming would skip the unread rest of the gone file.
     */
    private int firstFileAfterGone(CsvCursor cursor) {
      Path gone = Path.of(cursor.file());
      int first = -1;
      for (int i = 0; i < resolvedFiles.size() && first < 0; i++) {
        if (resolvedFiles.get(i).compareTo(gone) > 0) first = i;
      }
      if (first < 0 || cursor.fullyRead()) return first;
      throw new IllegalStateException("The cursor's file " + cursor.file() + " is gone, but files sorting after it"
          + " are waiting. Reset the endpoint's cursor to read again from the first file"
          + " (lm endpoint reset-cursor <connector>/<endpoint>).");
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
    private ConnectorCursor encodeCursor(String file, long rowsProcessed, WindowOutcome last, FileDescriptor stat) {
      CsvCursor out = (last != null && last.anchorByte() != null && last.charDelta() != null
              && last.lineBase() != null && last.headers() != null && last.version() != null)
          ? new CsvCursor(file, rowsProcessed, last.anchorByte(), last.charDelta(),
              last.lineBase(), last.headers(), last.version())
          : new CsvCursor(file, rowsProcessed);
      return wrap(witnessed(out, stat));
    }

    /**
     * #1452: a position mid-file resumes by its checkpoint or by row count — on the bytes its stat says, and a stat
     * (size, mtime) survives a same-length copy that keeps the mtime. For a carrying mapping the position records the
     * file's content fingerprint too, so a resume can refuse bytes its carried values were not taken from. Every other
     * cursor keeps its shape.
     */
    private CsvCursor witnessed(CsvCursor c, FileDescriptor stat) {
      if (!oneFilePerChunk || c.rowsProcessed() == 0) return c;
      CsvCursor.Version v = c.version() != null ? c.version()
          : stat != null && stat.path().equals(c.file()) ? CsvCursor.Version.of(stat) : null;
      if (v == null) return c;   // nothing to witness: the resume is refused
      CsvCursor.Version fingerprinted =
          new CsvCursor.Version(v.size(), v.lastModified(), v.etag(), contentHash(Path.of(c.file())));
      return new CsvCursor(c.file(), c.rowsProcessed(), c.anchorByte(), c.charDelta(), c.lineBase(), c.headers(),
          fingerprinted);
    }

    /**
     * #1452: a carrying mapping resumes a file mid-way only on the bytes its stored position and carried values were
     * taken from — a file replaced between two chunks would hand the old file's context to the new file's rows.
     */
    private void refuseChangedBytes(CsvCursor start, Path file) {
      CsvCursor.Version v = start.version();
      if (v == null || v.sha256() == null || !v.matches(describe(file), () -> contentHash(file))) {
        throw new StaleCarryException("csv-file: " + file + " is resumed mid-way, but it is no longer the file its "
            + "stored position and carried values were taken from; " + StaleCarryException.RESET);
      }
    }

    /**
     * The file's SHA-256 (#1452), kept for the run under the file's identity: size, mtime and ctime. A copy can restore
     * a size and an mtime (`cp -p`), never a ctime — every write moves it — so bytes replaced between two chunks miss
     * the kept fingerprint and are hashed again. A carrying mapping thus reads an unchanged file a second time per run,
     * not once per chunk. Where the system names no ctime, nothing proves the bytes kept: hashed at every resume.
     */
    private String contentHash(Path file) {
      FileDescriptor now = describe(file);
      String changed = changeTime(file);
      if (now == null || changed == null) return sha256(file);
      return scope.resource(new ContentKey(file.toString(), now.size(), now.lastModified().toString(), changed),
          () -> new Content(sha256(file))).sha256;
    }

    /** The file's ctime, or null where the file system does not expose one. */
    private static String changeTime(Path file) {
      try {
        return String.valueOf(Files.getAttribute(file, "unix:ctime"));
      } catch (UnsupportedOperationException | IllegalArgumentException | IOException noCtime) {
        return null;
      }
    }

    private ConnectorCursor wrap(CsvCursor out) {
      ObjectNode n = (ObjectNode) M.valueToTree(out);
      n.put("type", "CsvFile");
      return new ConnectorCursor(n, Instant.now());
    }
  }
}
