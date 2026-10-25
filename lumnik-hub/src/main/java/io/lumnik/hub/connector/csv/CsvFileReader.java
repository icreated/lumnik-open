package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import io.lumnik.hub.connector.api.types.MalformedRecord;

import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Reads CSV rows from a file as {@code Map<String, Object>} (column name → cell value).
 *
 * <p>Auto-detection (encoding, delimiter) is Task 18.
 * For Task 17, defaults are UTF-8 + comma + has-header + BOM strip.
 */
public class CsvFileReader {

  /**
   * Parse-work counter: CSV records tokenized by this reader instance, across all calls —
   * including the skipped prefix of a resumed window and the one-record peek at a window
   * boundary. Test-visible instrumentation for the lot-5b O(R) proof (every row tokenized
   * at most twice per run: once delivered + at most one peeked re-read); no production logic
   * reads it.
   */
  private long rowsTokenized;

  /** Records tokenized by this instance so far (delivered + skipped + peeked). */
  public long rowsTokenized() {
    return rowsTokenized;
  }

  public record Config(
      Charset encoding,
      char delimiter,
      boolean hasHeader,
      boolean bomStrip,
      List<String> nullValues,
      List<String> columns          // required when hasHeader == false; ignored otherwise
  ) {
    /**
     * The delimiter of {@code parser.lines: true} (#1415): ASCII's own unit separator — a marker, never a split: a
     * line that holds it is joined back whole ({@code lineRow}). Line mode rides on the delimiter
     * because every resume path already carries the delimiter — the remote cursor persists it — so the mode
     * reaches them with no new field and no new cursor format. The validator reserves this character.
     */
    public static final char LINES = '\u001F';

    /** One line = one row: no field splitting, and no quote character a line could open and never close. */
    public boolean lines() {
      return delimiter == LINES;
    }

    public static Config defaults() {
      return new Config(StandardCharsets.UTF_8, ',', true, true,
          List.of("", "NULL", "null", "#N/A"), null);
    }
  }

  public static Config autoDetect(Path file) {
    Charset enc = EncodingDetector.detect(file);
    char delim = DelimiterDetector.detect(file, enc);
    return new Config(enc, delim, true, true,
        List.of("", "NULL", "null", "#N/A"), null);
  }

  /** Outcome of a bounded read: events consumed past the skip point, and whether the file ended. */
  public record Window(long consumed, boolean fileExhausted) {}

  /**
   * Reads the whole file, isolating malformed rows instead of aborting. Thin wrapper over
   * {@link #readWindow} with no skip and no row limit.
   *
   * <p>Recoverable per-row failures (e.g. wrong column count → {@code IllegalArgumentException}
   * at {@code rec.get(...)}) are sent to {@code malformed} with the partial cell values, and
   * parsing continues. A tokenizer desync (unterminated quote → {@code UncheckedIOException} at
   * iterator advance) is sent to {@code malformed} with {@code rawValues == null} and stops this
   * file — the rest of the stream is suspect.
   */
  public void readAll(Path file, Config cfg,
                      Consumer<Map<String, Object>> rows,
                      Consumer<MalformedRecord> malformed) throws Exception {
    readWindow(file, cfg, 0L, Integer.MAX_VALUE, rows, malformed);
  }

  /**
   * Streams events from {@code file}, dropping the first {@code skip} events (already consumed on a
   * resume) and stopping once {@code maxGood} good rows have been delivered — so only {@code maxGood}
   * rows are ever held downstream and the unread tail is never parsed or materialized. Good rows go
   * to {@code rows}, isolated malformed rows to {@code malformed}; a tokenizer desync is file-fatal
   * (one entry, then stop). Returns the events consumed past {@code skip} and whether the file was
   * exhausted (EOF or desync). The skip prefix is still parsed each call — the connector re-opens the
   * file per chunk — but it is never retained.
   */
  public Window readWindow(Path file, Config cfg, long skip, int maxGood,
                           Consumer<Map<String, Object>> rows,
                           Consumer<MalformedRecord> malformed) throws Exception {
    Config effective = cfg == null ? autoDetect(file) : cfg;
    return readWindow(Files.newBufferedReader(file, effective.encoding()), file.toString(),
        effective, skip, maxGood, rows, malformed);
  }

  /**
   * Stream twin of {@link #readWindow(Path, Config, long, int, Consumer, Consumer)} for sources
   * that are not local paths (the remote transports, lot-5b B2): identical skip/maxGood/malformed
   * semantics — literally the same {@link #scan} engine — over a caller-opened {@link Reader},
   * which is consumed and closed by this call. {@code cfg} is required (no auto-detect: there is
   * no Path, only a stream) and {@code fileLabel} names the SOURCE file in malformed records, so
   * DLQ entries point at the remote path, never at an ephemeral local copy.
   */
  public Window readWindow(Reader rdr, String fileLabel, Config cfg, long skip, int maxGood,
                           Consumer<Map<String, Object>> rows,
                           Consumer<MalformedRecord> malformed) throws Exception {
    Objects.requireNonNull(cfg, "cfg (no auto-detect here: there is no Path, only a stream)");
    try (rdr) {
      Scan s = scan(rdr, fileLabel, cfg, skip, maxGood, rows, malformed, null, 0L, 0L);
      return new Window(s.consumed(), s.exhausted());
    }
  }

  /**
   * Outcome of a checkpoint-capable window (lot-5b fast path): the plain {@link Window} fields,
   * plus — when the window ended on a full chunk with more records ahead — the
   * {@linkplain CheckpointingReader.Checkpoint checkpoint} of the PEEKED next record (in the
   * coordinates of the reader this window ran on: an anchor-relative position for a resumed
   * reader; the caller adds its base anchor for an absolute one), and the stripped header names
   * (from the stream on a first window, echoing the configured columns on a resumed one).
   * {@code nextCheckpoint} is {@code null} when the file is exhausted, when the peek hit a
   * tokenizer desync (the next — fallback — window isolates it, exactly as today), or when the
   * charset turned out uncheckpointable: the caller then writes a legacy cursor.
   */
  public record CheckpointedWindow(long consumed, boolean fileExhausted,
                                   CheckpointingReader.Checkpoint nextCheckpoint,
                                   long nextLineBase, List<String> headers) {}

  /**
   * The checkpoint-capable twin of {@link #readWindow}: identical skip/maxGood/malformed
   * semantics (same loop — literally, see {@link #scan}), but reading through a
   * {@link CheckpointingReader} so the window can name a resumable position for its end.
   *
   * <p>The reader is consumed and closed by this call. It may be freshly opened at byte 0
   * (first window: {@code cfg} does header inference as configured) or produced by
   * {@link CheckpointingReader#resume} (resumed window: {@code cfg} must carry
   * {@code hasHeader=false} + the cursor's captured {@code columns}, since the header line is
   * behind the anchor). Char positions self-adjust: a resumed reader already counts the skipped
   * delta as delivered, so the parser's record positions are rebased on
   * {@link CheckpointingReader#charsDelivered()} at entry.
   */
  public CheckpointedWindow readWindowCheckpointed(CheckpointingReader rdr, String fileLabel,
                                                   Config cfg, long skip, int maxGood, long lineBase,
                                                   Consumer<Map<String, Object>> rows,
                                                   Consumer<MalformedRecord> malformed) throws Exception {
    Objects.requireNonNull(cfg, "cfg (no auto-detect here: there is no Path, only a stream)");
    try (rdr) {
      Scan s = scan(rdr, fileLabel, cfg, skip, maxGood, rows, malformed, rdr,
          rdr.charsDelivered(), lineBase);
      return new CheckpointedWindow(s.consumed(), s.exhausted(), s.next(), s.nextLineBase(), s.headers());
    }
  }

  private record Scan(long consumed, boolean exhausted,
                      CheckpointingReader.Checkpoint next, long nextLineBase,
                      List<String> headers) {}

  /**
   * The single window engine both paths share, so their skip/maxGood/malformed semantics cannot
   * drift. {@code ckpt} is {@code null} on the plain path; when present it is the SAME object as
   * {@code rdr}, and {@code charBase} is its chars-delivered count at entry (0 for a fresh
   * stream, the skipped delta for a resumed one) — parser record positions are relative to where
   * parsing started, reader checkpoints to the reader's own stream.
   */
  private Scan scan(Reader rdr, String fileLabel, Config effective, long skip, int maxGood,
                    Consumer<Map<String, Object>> rows, Consumer<MalformedRecord> malformed,
                    CheckpointingReader ckpt, long charBase, long lineBase) throws Exception {
    long seen = 0;       // events read from the stream start (good + malformed)
    long consumed = 0;   // events delivered or isolated past `skip` (this window)
    int good = 0;        // good rows delivered this window
    try (CSVParser parser = buildFormat(effective).parse(rdr)) {
      Set<String> nullSet = new HashSet<>(effective.nullValues());
      Map<String, String> originalToStripped = strippedHeaders(parser, effective.bomStrip());
      int declaredColumns = parser.getHeaderNames().size();
      List<String> headers = List.copyOf(originalToStripped.values());
      Iterator<CSVRecord> it = parser.iterator();
      while (good < maxGood) {
        CSVRecord rec;
        try {
          if (!it.hasNext()) return new Scan(consumed, true, null, 0L, headers);   // EOF
          rec = it.next();
          rowsTokenized++;
        } catch (UncheckedIOException tokenizerDesync) {
          // Commons CSV wraps a tokenizer/IO failure (e.g. unterminated quote) as an
          // UncheckedIOException — the stream is desynced, so this file is fatal. Narrowed to the
          // I/O wrapper on purpose: any OTHER RuntimeException (e.g. an NPE) is a real defect and
          // must propagate to fail the run loudly, not masquerade as a "malformed source" entry.
          if (seen >= skip) {
            malformed.accept(new MalformedRecord(
                fileLabel, lineBase + parser.getCurrentLineNumber(), null, desyncSentence(tokenizerDesync)));
            consumed++;
          }
          return new Scan(consumed, true, null, 0L, headers);
        }
        if (seen++ < skip) {
          continue;   // already-consumed event on resume: counted, delivered to no one
        }
        Map<String, Object> row;
        try {
          row = effective.lines()
              ? lineRow(rec, headers.get(0), nullSet, effective.bomStrip())
              : toRow(rec, originalToStripped, declaredColumns, nullSet, effective.bomStrip());
        } catch (IllegalArgumentException badColumns) {
          // Column-count mismatch: isolate this row, keep going.
          malformed.accept(new MalformedRecord(
              fileLabel, lineBase + parser.getCurrentLineNumber(), rec.toList(), badColumns.getMessage()));
          consumed++;
          continue;
        }
        rows.accept(row);
        consumed++;
        good++;
      }
      // The window is full; what the boundary means is its own question.
      return boundary(it, parser, ckpt, charBase, lineBase, consumed, headers);
    }
  }

  /**
   * What the end of a full window MEANS. Peeking one record ahead is what lets a file that ends
   * exactly on the boundary be reported as exhausted now, sparing the next chunk a full re-open and
   * skip just to hit EOF. Four outcomes live here and none of them belongs in the row loop: clean
   * EOF, a tokenizer desync just past the boundary, a resumable checkpoint, and a charset that
   * cannot be checkpointed (rows already delivered are fine; only the cursor stays legacy-shaped).
   */
  private Scan boundary(Iterator<CSVRecord> it, CSVParser parser, CheckpointingReader ckpt,
                        long charBase, long lineBase, long consumed, List<String> headers) {
    // Chunk is full. Peek one record ahead so a file that ends exactly on this boundary is
    // reported as exhausted now — sparing the next chunk a full re-open + skip just to hit EOF.
    if (ckpt == null) {
      boolean more;
      try {
        more = it.hasNext();
        if (more) rowsTokenized++;   // the peek parses one record ahead
      } catch (UncheckedIOException desyncAhead) {
        more = true;   // a tokenizer desync sits just past the boundary; the next window isolates it
      }
      return new Scan(consumed, !more, null, 0L, headers);
    }
    // Checkpoint path: the peeked record's start IS the resumable position (design finding 4 —
    // no extra parse work: hasNext() already tokenized it; next() surfaces the cached record).
    // Line accounting for file-absolute DLQ lines: capture the physical lines consumed BEFORE
    // the peek — the resumed window's parser restarts its line counter at the anchor, and adds
    // this base back. (An ignored empty line sitting exactly on the window boundary shifts the
    // base by its count — an accepted corner: the affected value is a diagnostic line number,
    // never a row.)
    long boundaryLine = lineBase + parser.getCurrentLineNumber();
    CSVRecord peeked;
    try {
      if (!it.hasNext()) return new Scan(consumed, true, null, 0L, headers);
      peeked = it.next();
      rowsTokenized++;
    } catch (UncheckedIOException desyncAhead) {
      // Desync just past the boundary: report "more" with no checkpoint — the caller writes a
      // legacy cursor and the next (fallback) window isolates it, exactly as the plain path.
      return new Scan(consumed, false, null, 0L, headers);
    }
    CheckpointingReader.Checkpoint cp;
    try {
      cp = ckpt.checkpoint(charBase + peeked.getCharacterPosition());
    } catch (CheckpointingReader.UncheckpointableCharsetException offAllowlist) {
      cp = null;   // rows already delivered are fine; only the cursor stays legacy-shaped
    }
    return new Scan(consumed, false, cp, boundaryLine, headers);
  }

  /**
   * Legacy 3-arg overload — preserves the <em>abort on the first malformed row</em> contract for the
   * direct ingest path ({@code CsvIngestService}) and other callers that must fail rather than
   * isolate. It delegates to the isolating overload with a malformed sink that re-throws, so the
   * first malformed row stops the read.
   *
   * <p>The thrown exception is a uniform {@link IllegalStateException} (carrying {@code file:line} and
   * the underlying message), not the original Apache Commons CSV exception type. Callers here treat
   * any throwable from the read as a hard failure (they catch {@code Exception} and re-wrap), so the
   * exact type is immaterial — only the abort contract matters.
   */
  public void readAll(Path file, Config cfg, Consumer<Map<String, Object>> consumer) throws Exception {
    readAll(file, cfg, consumer, m -> {
      throw new IllegalStateException("malformed CSV row at " + m.file() + ":" + m.line()
          + " — " + m.message());
    });
  }

  private CSVFormat buildFormat(Config effective) {
    if (effective.hasHeader() && !effective.lines()) {
      return CSVFormat.Builder.create(CSVFormat.DEFAULT)
          .setDelimiter(effective.delimiter())
          .setHeader()
          .setSkipHeaderRecord(true)
          .setIgnoreEmptyLines(true)
          .build();
    }
    if (effective.columns() == null || effective.columns().isEmpty()) {
      throw new ConfigValidationException(
          "csv: has-header=false requires an explicit 'columns' list "
          + "(headerless files have no column names to infer)");
    }
    return CSVFormat.Builder.create(CSVFormat.DEFAULT)
        .setDelimiter(effective.delimiter())
        .setQuote(effective.lines() ? null : CSVFormat.DEFAULT.getQuoteCharacter())
        .setHeader(effective.columns().toArray(new String[0]))
        .setSkipHeaderRecord(false)
        .setIgnoreEmptyLines(true)
        .build();
  }

  /** Map each header key to its BOM-stripped form, parsed once and reused per row. */
  private Map<String, String> strippedHeaders(CSVParser parser, boolean bomStrip) {
    Map<String, String> originalToStripped = new LinkedHashMap<>();
    for (String originalKey : parser.getHeaderMap().keySet()) {
      originalToStripped.put(originalKey, stripBom(originalKey, bomStrip));
    }
    return originalToStripped;
  }

  /**
   * The row, against the schema DECLARED for it — a header line, or the {@code columns} a
   * headerless connector configures. Both paths land here, so this method says "declared" rather
   * than "header" throughout, exactly as its refusal does when the operator reads it in the DLQ.
   *
   * <p>Both column-count directions end as an {@link IllegalArgumentException} the caller isolates
   * into the DLQ — too FEW comes from Commons CSV itself (a declared index past the record's
   * values), too MANY is refused here.
   *
   * <p>The extra-values guard is not decoration: without it this loop walks the DECLARED keys and
   * never looks at what follows, so a third value on a two-column row was dropped without a trace
   * — a row the run counted as written, with nothing in the DLQ to say a field had gone missing.
   *
   * <p>{@code declaredColumns} is the count of declared NAMES, not of {@code originalToStripped}
   * entries: Commons CSV collapses a repeated name in its header MAP ({@code a,b,a} → 2 entries)
   * while the records still carry all three values, so counting the map would quarantine every row
   * of a legacy export that repeats a column name.
   *
   * <p><b>A repeated name is still lossy, and this guard does not pretend otherwise.</b>
   * Counting names makes the guard right about WIDTH; it cannot make the OUTPUT whole, because a
   * row is a {@code Map} and a map cannot hold the same key twice — {@code a,b,a} yields two
   * entries with the last duplicate winning, so the first {@code a} value is dropped exactly as
   * a surplus value used to be. The captured {@code headers} list inherits that collapse, which
   * is why a resumed window declares fewer columns than the first one and can quarantine rows the
   * first window accepted. Both predate this guard and need a decision it has no business making
   * alone — rename the repeats, or refuse the file at apply time.
   */
  private Map<String, Object> toRow(CSVRecord rec, Map<String, String> originalToStripped,
                                    int declaredColumns, Set<String> nullSet, boolean bomStrip) {
    if (rec.size() > declaredColumns) {
      // "are declared", not "the header declares": a headerless file declares its columns in the
      // config, and this sentence is what the operator reads in `lm dlq get`.
      throw new IllegalArgumentException("Record has " + rec.size()
          + " values but only " + columns(declaredColumns));
    }
    Map<String, Object> row = new LinkedHashMap<>(originalToStripped.size());
    for (var entry : originalToStripped.entrySet()) {
      String cell;
      try {
        cell = rec.get(entry.getKey());
      } catch (IllegalArgumentException tooFew) {
        // Commons CSV's own text names its record class; the operator reads the shape instead.
        throw new IllegalArgumentException("Record has " + (rec.size() == 1 ? "1 value" : rec.size() + " values")
            + " but " + columns(declaredColumns), tooFew);
      }
      String v = stripBom(cell, bomStrip);
      row.put(entry.getValue(), nullSet.contains(v) ? null : v);
    }
    return row;
  }

  private static String columns(int declared) {
    return declared == 1 ? "1 column is declared" : declared + " columns are declared";
  }

  /**
   * The reader's sentence for a stream it cannot read on: a quote that does not close where the
   * format expects is the usual cause; any other read failure gets the plain fact. Never the
   * library's exception text, which names its own classes.
   */
  private static String desyncSentence(UncheckedIOException desync) {
    return String.valueOf(desync.getMessage()).contains("encapsulated token")
        ? "A quoted value does not close where expected: the file cannot be read past this line"
        : "The file cannot be read past this line";
  }

  /**
   * Line mode's row: the whole line, whatever it holds. With no quote and no escape, Commons CSV's split on
   * {@link Config#LINES} is lossless, so a line that holds the unit separator is joined back exactly as written.
   */
  private static Map<String, Object> lineRow(CSVRecord rec, String column, Set<String> nullSet, boolean bomStrip) {
    String line = stripBom(String.join(String.valueOf(Config.LINES), rec.values()), bomStrip);
    Map<String, Object> row = new LinkedHashMap<>(1);
    row.put(column, nullSet.contains(line) ? null : line);
    return row;
  }

  /** Strip a leading UTF-8 BOM (U+FEFF) when {@code bomStrip} is enabled. */
  private static String stripBom(String s, boolean bomStrip) {
    if (bomStrip && s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
      return s.substring(1);
    }
    return s;
  }
}
