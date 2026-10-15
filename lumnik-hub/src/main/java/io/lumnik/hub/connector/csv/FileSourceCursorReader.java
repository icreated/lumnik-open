package io.lumnik.hub.connector.csv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunScope;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.SeekableSource;
import io.lumnik.hub.connector.transport.cursor.FileProcessingCursor;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remote (sftp/s3) CSV reader. Per read(): first archive (after_process) the files the
 * PERSISTED cursor already carries — the committed cursor is the proof their rows landed —
 * then continue the in-progress file (if the cursor names one) or take the next unprocessed
 * file from the run's listing (one listing per {@link #REFRESH_EVERY} files; a file taken from an earlier listing is checked with one {@code stat}). Each read() delivers at most
 * {@code chunkSize} rows from ONE file (lot-5b B2 — the old one-file-one-chunk read
 * materialised the whole file in heap); a file spanning several chunks is carried in the
 * cursor's {@code inProgress} block until exhausted, and only then enters the
 * watermark/lastBatch bookkeeping. The local path keeps its own row-level reader.
 *
 * <p><b>The fork (lot-5b design (A), ratified doctrine), remote edition:</b> the fast path is
 * opt-in behind verified preconditions — {@link SeekableSource} transport AND
 * checkpoint-allowlisted charset AND (on a resume) a complete checkpoint whose version token
 * still matches the listed file. It reads checkpointed windows via {@code openAt} (S3 ranged
 * GET / SFTP offset): the file is parsed once across the run, heap = O(chunk). ANY
 * precondition false, or ANY pre-parse failure while seeking/resuming, drops to the fallback —
 * which for remote files means <em>streamed skip-parse over the transport stream</em>: parse
 * from byte 0, drop the consumed prefix, stop at the chunk bound. The fallback re-reads bytes
 * (O(file) per window) but NEVER holds more than a chunk of rows in heap — non-seekable
 * transports live on it permanently, so its memory bound is load-bearing, not a corner.
 * Correctness lives in the fallback; the fast path only ever buys speed.
 *
 * <p><b>One exception to fallback-not-fail:</b> a remote file whose bytes CHANGE mid-ingestion
 * ({@code sameVersion} false between chunks, or the file no longer listed). Skip-parse over
 * mutated bytes would silently misalign with the rows already committed, and restarting the
 * file silently would double-ingest it — so this fails LOUDLY, naming the file, until the
 * operator resets the cursor or restores the file.
 *
 * <p><b>Archiving is deferred by one read() on purpose (A8, tribunal 2026-07-25):</b> the
 * read that returns a file's rows runs inside the chunk transaction — moving/deleting the
 * source there would make a crash-after-archive unreplayable (rows never committed, file
 * gone). Deferring until the cursor proves the commit makes the whole path at-least-once:
 * a crash between commit and the next read leaves the file in place, the dedup batch stops
 * re-ingestion, and the next run archives it. Replay stays idempotent (DynamicTableWriter
 * dedups by _row_hash). Mid-file reads skip the archive sweep: the read that STARTED the
 * in-progress file already swept this exact lastBatch, and re-attempting per chunk would
 * only spam already-archived warnings — the cadence stays one attempt per file-start read,
 * exactly as before.
 */
final class FileSourceCursorReader implements ItemReader<Map<String, Object>> {

    private static final Logger LOG =
        Logger.getLogger(FileSourceCursorReader.class);

    private static final String AFTER_PROCESS = "after_process";
    private static final String IN_PROGRESS = "inProgress";

    private static final ObjectMapper M = new ObjectMapper();
    // Tolerant reads for the inProgress block: a build must never explode on fields added
    // after it shipped — unknown properties are the compatibility seam (same posture as CsvCursor).
    private static final ObjectMapper TOLERANT = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Map<String, Object> transport;
    private final CsvFileConnectorType.ParserOverrides overrides;
    private final FileSourceRegistry registry;
    private final AfterProcessAction afterProcess;
    private final Map<String, Object> markCfg;
    private final List<MalformedRecord> malformedBuffer = new ArrayList<>();
    /** One parser per reader lifetime, so its parse-work counter can span a test-driven run. */
    private final CsvFileReader reader = new CsvFileReader();

    /** Test-visible: records tokenized across every window this reader has run (O(R) proof). */
    long rowsTokenized() {
        return reader.rowsTokenized();
    }

    /**
     * @param config the FULL connector config. The apply handler stores {@code after_process} and
     *   {@code archive_path} at the TOP LEVEL (siblings of {@code transport}), not inside the transport
     *   block — so we read them from {@code config}, with a transport-block fallback for the one-shot
     *   style. {@code transport} (for {@code FileSource} build) is resolved from the same config.
     */
    FileSourceCursorReader(Map<String, Object> config,
                           CsvFileConnectorType.ParserOverrides overrides,
                           FileSourceRegistry registry) {
        this.transport = CsvFileConnectorType.resolveTransport(config);
        this.overrides = overrides;
        this.registry = registry;
        Object ap = config.containsKey(AFTER_PROCESS) ? config.get(AFTER_PROCESS)
                                                         : transport.get(AFTER_PROCESS);
        this.afterProcess = AfterProcessAction.parse(ap);
        // markProcessed (e.g. MOVE) reads archive_* from the cfg it's given; surface the top-level
        // archive keys alongside the transport block so the relocation target is honoured regardless
        // of where the manifest put it.
        Map<String, Object> mc = new HashMap<>(transport);
        for (String k : List.of("archive_path", "archive_bucket", "archive_prefix")) {
            if (config.containsKey(k)) mc.putIfAbsent(k, config.get(k));
        }
        this.markCfg = mc;
    }

    @Override
    public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor, ConnectorContext ctx) {
        // The source (SFTP session, S3 client) belongs to the run, not to this chunk: the engine builds a fresh
        // reader per chunk but closes ctx.scope() once, when the run ends. Closing it here would rebuild a
        // session every 500 rows.
        try {
            FileSource source = source(ctx);
            FileProcessingCursor fpc = cursor.isEmpty()
                ? FileProcessingCursor.defaultCursor()
                : FileProcessingCursor.fromJson(cursor.value().toString());
            InProgress ip = cursor.isEmpty() ? null : InProgress.fromCursor(cursor.value());

            if (ip != null) {
                return continueFile(source, fpc, ip, chunkSize);
            }

            archiveCommitted(source, fpc);

            FileDescriptor next = nextFile(source, fpc, ctx.scope());
            if (next == null) return new ReadResult.EndOfStream<>(List.of());

            return startFile(source, fpc, next, chunkSize);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("remote read failed: " + e.getMessage(), e);
        }
    }

    /** The key under which the run scope holds this endpoint's source (the scope is per endpoint run). */
    private static final String SOURCE_KEY = "file-source";
    private static final String LISTING_KEY = "file-listing";
    /**
     * How many files a listing is trusted for. A file that arrives while the run reads, with an mtime behind the
     * watermark the run has reached, is passed by the watermark rule; the longer a list lives, the more such
     * arrivals it hides from the listing that would have shown them in time. Bounding it keeps that window at this
     * many files while a run over D files lists D / REFRESH_EVERY times instead of D (5 000 files: 50 listings, not
     * 5 000).
     */
    static final int REFRESH_EVERY = 100;

    /** The files a listing found that this run has not started yet, oldest first. Held by the run scope. */
    private static final class Listing implements AutoCloseable {
        final Deque<FileDescriptor> pending = new ArrayDeque<>();
        /** Files handed out since the list was made. */
        int served;

        @Override public void close() { pending.clear(); }
    }

    /**
     * The next file to start, or {@code null} when the source has none left. The source is LISTED once to find the
     * files, not once per file (#1017: the listing is O(D), so a run over D small files was O(D²)); each file then
     * taken from an EARLIER listing is re-checked with one {@code stat}, because that list was true when it was made
     * and archiving or the outside world has acted since (the first file of a listing just made is not: the
     * listing is its check):
     * <ul>
     *   <li>gone from the source → skipped, exactly as a fresh listing would not return it;</li>
     *   <li>changed (size, mtime, etag) → the list is dropped and rebuilt, because the file now belongs somewhere
     *       else in the mtime order and the watermark rule must see it there;</li>
     *   <li>list used up, or {@link #REFRESH_EVERY} files served → listed again, which finds what arrived while
     *       the run was reading (when its mtime is not behind the watermark yet) and is the answer to "nothing
     *       left", as before.</li>
     * </ul>
     * The run's scope is per endpoint run and a failing chunk fails the run, so a file taken from the list and
     * lost with its chunk is listed afresh by the next run; a chunk retried inside one run would not find it again.
     */
    private FileDescriptor nextFile(FileSource source, FileProcessingCursor fpc, RunScope scope) {
        Listing listing = scope.resource(LISTING_KEY, Listing::new);
        while (listing.served < REFRESH_EVERY) {
            FileDescriptor queued = pollStartable(listing, fpc);
            if (queued == null) break;
            FileDescriptor now = source.stat(queued.path()).orElse(null);
            if (now == null) continue;
            if (queued.sameVersion(now)) {
                listing.served++;
                return now;
            }
            break;
        }
        listing.pending.clear();
        try (var stream = source.listSince(fpc.watermark())) {
            stream.forEach(listing.pending::add);   // already ascending by lastModified: the FileSource contract
        }
        listing.served = 0;
        FileDescriptor first = pollStartable(listing, fpc);
        if (first != null) listing.served++;
        return first;
    }

    /** The first queued file the cursor has not already seen; the ones it has are dropped on the way. */
    private static FileDescriptor pollStartable(Listing listing, FileProcessingCursor fpc) {
        FileDescriptor f;
        while ((f = listing.pending.poll()) != null) {
            if (!f.lastModified().isBefore(fpc.watermark()) && !fpc.alreadyProcessed(f)) return f;
        }
        return null;
    }

    /**
     * The run's source. If its session died while the previous chunk was being written, it is replaced HERE,
     * at the start of the chunk, before anything was read or moved, and only once: a chunk that fails afterwards
     * fails as it always did, because it may already have downloaded half a file or archived a processed one.
     */
    private FileSource source(ConnectorContext ctx) {
        RunScope scope = ctx.scope();
        Supplier<FileSource> build = () -> registry.build(transport);
        if (!scope.resource(SOURCE_KEY, build).usable()) scope.invalidate(SOURCE_KEY);
        return scope.resource(SOURCE_KEY, build);
    }

    // -----------------------------------------------------------------------
    // First window of a file
    // -----------------------------------------------------------------------

    /**
     * Starts a new file: download it to a temp file (disk, not heap — same transfer today's
     * whole-file read already did, and the download is what encoding/delimiter auto-detection
     * needs), then parse only the FIRST window from it. On a seekable transport with an
     * allowlisted charset the window runs through the checkpointing reader, so the cursor can
     * carry a byte-anchor for the next window — the temp copy has the same bytes the transport
     * serves, so its anchors are valid against {@code openAt} on later reads.
     */
    // S5443: temp file in system dir is safe — short-lived, immediately processed, backend-only.
    @SuppressWarnings("java:S5443")
    private ReadResult<Map<String, Object>> startFile(FileSource source, FileProcessingCursor fpc,
                                                      FileDescriptor next, int chunkSize) {
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(chunkSize, 1024));
        Path temp;
        try {
            temp = Files.createTempFile("csv_conn_remote_", ".csv");
        } catch (IOException e) {
            throw new ConnectorException("temp create failed: " + e.getMessage(), e);
        }
        try {
            try (InputStream in = source.open(next)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            CsvFileReader.Config cfg = CsvFileConnectorType.effectiveConfig(temp, overrides);
            Windowed w = firstWindow(source, next, temp, cfg, chunkSize, rows);
            return advance(fpc, next, cfg, w, rows, 0L);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("remote read failed for " + next.path() + ": " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best-effort temp cleanup */ }
        }
    }

    /** THE FORK for a file's first window — see the class javadoc for the doctrine. */
    private Windowed firstWindow(FileSource source, FileDescriptor next, Path temp,
                                 CsvFileReader.Config cfg, int chunkSize,
                                 List<Map<String, Object>> rows) throws Exception {
        if (source instanceof SeekableSource && CheckpointingReader.canCheckpoint(cfg.encoding())) {
            CheckpointingReader rdr = null;
            InputStream in = null;
            try {
                in = Files.newInputStream(temp);
                rdr = new CheckpointingReader(in, cfg.encoding());
            } catch (Exception openFailed) {
                // The stream is named so the fallback can close it. Constructed inline it was
                // unreachable, and openResumed below — and tryFirstWindow in
                // CsvFileConnectorType, the same method's other copy — already close theirs.
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) { /* best-effort */ }
                }
                LOG.debugf(openFailed, "csv fast path unavailable for %s — using the skip-parse fallback",
                    next.path());
            }
            if (rdr != null) {
                // Rows start flowing here: a parse failure past this point propagates exactly as
                // the plain path would — falling back mid-window would deliver rows twice.
                CsvFileReader.CheckpointedWindow w = reader.readWindowCheckpointed(
                    rdr, next.path(), cfg, 0L, chunkSize, 0L, rows::add, malformedBuffer::add);
                return windowed(w, 0L);
            }
        }
        // FALLBACK — bounded skip-parse over the temp copy: heap holds at most one chunk.
        CsvFileReader.Window w = reader.readWindow(
            Files.newBufferedReader(temp, cfg.encoding()), next.path(), cfg,
            0L, chunkSize, rows::add, malformedBuffer::add);
        return new Windowed(w.consumed(), w.fileExhausted(), null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Resumed windows of the in-progress file
    // -----------------------------------------------------------------------

    private ReadResult<Map<String, Object>> continueFile(FileSource source, FileProcessingCursor fpc,
                                                         InProgress ip, int chunkSize) {
        // One stat of this file, not a listing of the whole source (#898). The watermark filter is
        // listSince's inclusive boundary, so the answer is still the one the listing would give.
        FileDescriptor now = source.stat(ip.path())
            .filter(f -> !f.lastModified().isBefore(fpc.watermark()))
            .orElse(null);
        // A partially-ingested file that mutated or vanished is NOT resumable: skip-parse over
        // different bytes would silently misalign with the committed rows, and a silent restart
        // would double-ingest. Fail loudly, naming the file — the one exception to fallback-not-fail.
        if (now == null) {
            throw new ConnectorException("remote CSV " + ip.path() + " disappeared mid-ingestion after "
                + ip.rowsProcessed() + " rows were committed — the transport no longer lists it; refusing to"
                + " continue or restart it silently. Restore the file, or reset the endpoint cursor to move on.");
        }
        if (!ip.startedVersion().sameVersion(now)) {
            throw new ConnectorException("remote CSV " + ip.path() + " changed mid-ingestion after "
                + ip.rowsProcessed() + " rows were committed (was size=" + ip.size()
                + ", lastModified=" + ip.lastModified() + (ip.etag() != null ? ", etag=" + ip.etag() : "")
                + "; now size=" + now.size() + ", lastModified=" + now.lastModified()
                + (now.etag() != null ? ", etag=" + now.etag() : "")
                + ") — a file mutated mid-run is not resumable; refusing to continue or restart it silently."
                + " Reset the endpoint cursor to re-ingest it from scratch.");
        }

        Charset enc = Charset.forName(ip.encoding());
        char delim = ip.delimiter().charAt(0);
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(chunkSize, 1024));

        // THE FORK, resumed edition: seekable + allowlisted charset + a complete checkpoint →
        // seek to the anchor (ranged GET / SFTP offset) and parse only this window.
        if (source instanceof SeekableSource seekable && ip.hasCheckpoint()
                && CheckpointingReader.canCheckpoint(enc)) {
            CheckpointingReader rdr = openResumed(seekable, now, enc, ip);
            if (rdr != null) {
                // The header line is behind the anchor: parse with the cursor's captured column
                // names, the same headerless-mode machinery has-header=false files always used.
                CsvFileReader.Config headerless = new CsvFileReader.Config(
                    enc, delim, false, overrides.bomStrip(), overrides.nullValues(), ip.headers());
                CsvFileReader.CheckpointedWindow w;
                try {
                    w = reader.readWindowCheckpointed(rdr, ip.path(), headerless,
                        0L, chunkSize, ip.lineBase(), rows::add, malformedBuffer::add);
                } catch (Exception e) {
                    throw new ConnectorException("remote read failed for " + ip.path() + ": " + e.getMessage(), e);
                }
                return advance(fpc, now, headerless, windowed(w, ip.anchorByte()), rows, ip.rowsProcessed());
            }
        }

        // FALLBACK — streamed skip-parse over the transport stream from byte 0: the consumed
        // prefix is parsed and dropped, the window stops at the chunk bound, and the heap never
        // holds more than a chunk of rows. This is the permanent home of non-seekable transports
        // (and of any resume whose seek failed pre-parse); today's whole-file materialisation is gone.
        CsvFileReader.Config cfg = new CsvFileReader.Config(
            enc, delim, overrides.hasHeader(), overrides.bomStrip(), overrides.nullValues(), overrides.columns());
        try (InputStream in = source.open(now)) {
            // REPORT decoder semantics, matching Files.newBufferedReader on every other path.
            Reader r = new BufferedReader(new InputStreamReader(in,
                enc.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                   .onUnmappableCharacter(CodingErrorAction.REPORT)));
            CsvFileReader.Window w = reader.readWindow(
                r, ip.path(), cfg, ip.rowsProcessed(), chunkSize, rows::add, malformedBuffer::add);
            return advance(fpc, now, cfg,
                new Windowed(w.consumed(), w.fileExhausted(), null, null, null, null),
                rows, ip.rowsProcessed());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("remote read failed for " + ip.path() + ": " + e.getMessage(), e);
        }
    }

    /** openAt + resume; any failure here is pre-parse (no rows delivered) → safe to fall back. */
    private CheckpointingReader openResumed(SeekableSource seekable, FileDescriptor now,
                                            Charset enc, InProgress ip) {
        InputStream in = null;
        try {
            in = seekable.openAt(now, ip.anchorByte());
            return CheckpointingReader.resume(in, enc, ip.charDelta());
        } catch (Exception seekOrResumeFailed) {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { /* best-effort */ }
            }
            LOG.warnf(seekOrResumeFailed,
                "csv checkpoint resume failed for %s at byte %d — using the streamed skip-parse fallback",
                now.path(), ip.anchorByte());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Window outcome → cursor
    // -----------------------------------------------------------------------

    /**
     * One window's outcome: the plain consumed/exhausted pair plus, when the window ran on the
     * checkpoint path and named a resume point, the ABSOLUTE anchor, char delta, line base and
     * captured headers. All-null checkpoint fields → the in-progress cursor stays skip-parse-shaped.
     */
    private record Windowed(long consumed, boolean exhausted,
                            Long anchorByte, Long charDelta, Long lineBase, List<String> headers) {}

    private static Windowed windowed(CsvFileReader.CheckpointedWindow w, long baseAnchor) {
        // The window's checkpoint is relative to the stream it ran on; the cursor stores the
        // absolute byte position, so the next resume seeks the file directly.
        boolean checkpointed = w.nextCheckpoint() != null && !w.headers().isEmpty();
        return new Windowed(w.consumed(), w.fileExhausted(),
            checkpointed ? baseAnchor + w.nextCheckpoint().anchorByte() : null,
            checkpointed ? w.nextCheckpoint().charDelta() : null,
            checkpointed ? w.nextLineBase() : null,
            checkpointed ? w.headers() : null);
    }

    /**
     * Advances the cursor after a window on {@code fd}. A file is only promoted into the
     * watermark/lastBatch bookkeeping when EXHAUSTED — mid-file windows keep the incoming
     * watermark untouched and carry the position in {@code inProgress}, so a crashed run resumes
     * the file instead of re-listing past it. lastBatch de-dups files sharing the watermark
     * second — listSince is inclusive at the boundary, so it re-returns them, and files strictly
     * older than the new watermark are excluded, leaving only same-second entries.
     */
    private ReadResult<Map<String, Object>> advance(FileProcessingCursor fpc, FileDescriptor fd,
                                                    CsvFileReader.Config cfg, Windowed w,
                                                    List<Map<String, Object>> rows, long priorRows) {
        if (w.exhausted()) {
            List<FileProcessingCursor.Entry> batch = fd.lastModified().equals(fpc.watermark())
                ? new ArrayList<>(fpc.lastBatch())
                : new ArrayList<>();
            batch.add(new FileProcessingCursor.Entry(fd.path(), fd.size(), fd.etag()));
            FileProcessingCursor advanced = new FileProcessingCursor(fd.lastModified(), batch);
            // Deliberately NO markProcessed here: this file's rows are not committed yet.
            // The next read() (or the next run) archives it once the cursor proves the commit.
            return new ReadResult.HasMore<>(rows, encodeCursor(advanced, null));
        }
        InProgress next = new InProgress(fd.path(), fd.size(), fd.lastModified().toString(), fd.etag(),
            priorRows + w.consumed(), cfg.encoding().name(), String.valueOf(cfg.delimiter()),
            w.anchorByte(), w.charDelta(), w.lineBase(), w.headers());
        return new ReadResult.HasMore<>(rows, encodeCursor(fpc, next));
    }

    private ConnectorCursor encodeCursor(FileProcessingCursor fpc, InProgress ip) {
        try {
            ObjectNode n = (ObjectNode) M.readTree(FileProcessingCursor.toJson(fpc));
            if (ip != null) n.set(IN_PROGRESS, M.valueToTree(ip));
            return new ConnectorCursor(n, Instant.now());
        } catch (Exception e) {
            throw new ConnectorException("cursor serialize failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // The in-progress block
    // -----------------------------------------------------------------------

    /**
     * The cursor's {@code inProgress} block: the file currently spanning chunks. Stored as a
     * sibling of {@code FileProcessingCursor}'s fields (which ignores unknown properties, so an
     * old build simply re-reads the file whole and the {@code _row_hash} dedup absorbs the
     * replay — backward-compatible by construction; a cursor without the block is
     * forward-compatible the same way). Carries:
     * <ul>
     *   <li>the version token ({@code size}/{@code lastModified}/{@code etag}) of the bytes the
     *       committed rows came from — verified against a fresh {@code stat} every window;</li>
     *   <li>{@code rowsProcessed} (events consumed: good + isolated malformed) for the
     *       skip-parse fallback;</li>
     *   <li>the {@code encoding}/{@code delimiter} detected at the file's first window — resumed
     *       windows have no local copy to re-detect from, and re-detection on a different window
     *       could drift mid-file;</li>
     *   <li>optionally a checkpoint ({@code anchorByte}/{@code charDelta}/{@code lineBase}/
     *       {@code headers}) for the seekable fast path, mirroring {@link CsvCursor}.</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InProgress(String path, long size, String lastModified, String etag,
                      long rowsProcessed, String encoding, String delimiter,
                      Long anchorByte, Long charDelta, Long lineBase, List<String> headers) {

        /** The descriptor of the bytes ingestion started on — the version token to verify. */
        FileDescriptor startedVersion() {
            return new FileDescriptor(path, size, Instant.parse(lastModified), etag);
        }

        /** Complete, sane checkpoint — same sanity bar as {@link CsvCursor#hasCheckpoint()}. */
        boolean hasCheckpoint() {
            return anchorByte != null && anchorByte >= 0
                && charDelta != null && charDelta >= 0
                && lineBase != null && lineBase >= 0
                && headers != null && !headers.isEmpty()
                && anchorByte < size;
        }

        /**
         * Reads the block from the persisted cursor node; {@code null} when absent. A block that
         * is present but unusable fails LOUDLY: silently dropping it would restart a
         * partially-ingested file — the one forbidden move on this path. (Only the essentials
         * are load-bearing; a merely-corrupt checkpoint still skip-parses via
         * {@link #hasCheckpoint()} returning false.)
         */
        static InProgress fromCursor(JsonNode cursorNode) {
            JsonNode n = cursorNode.get(IN_PROGRESS);
            if (n == null || n.isNull()) return null;
            try {
                InProgress ip = TOLERANT.treeToValue(n, InProgress.class);
                Charset.forName(ip.encoding());
                Instant.parse(ip.lastModified());
                if (ip.path() == null || ip.path().isBlank() || ip.size() < 0 || ip.rowsProcessed() < 0
                        || ip.delimiter() == null || ip.delimiter().length() != 1) {
                    throw new IllegalArgumentException("incomplete in-progress fields");
                }
                return ip;
            } catch (Exception corrupt) {
                throw new ConnectorException("remote CSV in-progress cursor is unreadable ("
                    + corrupt.getMessage() + ") — refusing to restart the partially-ingested file"
                    + " silently; reset the endpoint cursor to re-ingest. Cursor block: " + n);
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Archives every file the persisted cursor carries — their chunks committed, or the
     * cursor would not hold them. Per-entry failures warn and continue: a MOVE on an
     * already-archived file throws (idempotent retry across reads of the same watermark
     * second), and a real source error must not block ingestion — the file lingers and
     * the warning is the operator's signal.
     */
    private void archiveCommitted(FileSource source, FileProcessingCursor fpc) {
        if (afterProcess == AfterProcessAction.LEAVE || fpc.lastBatch() == null) return;
        for (FileProcessingCursor.Entry e : fpc.lastBatch()) {
            try {
                source.markProcessed(
                    new FileDescriptor(e.path(), e.size(), fpc.watermark(), e.etag()),
                    afterProcess, markCfg);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warnf(ex, "after_process %s skipped for %s (already archived, or source error)",
                    afterProcess, e.path());
            }
        }
    }

    @Override
    public List<MalformedRecord> drainMalformed() {
        var out = List.copyOf(malformedBuffer);
        malformedBuffer.clear();
        return out;
    }
}
