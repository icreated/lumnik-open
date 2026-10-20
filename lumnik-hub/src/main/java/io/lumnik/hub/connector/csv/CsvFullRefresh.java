package io.lumnik.hub.connector.csv;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.api.types.StreamEnd;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.writer.UnseenRowSweeper;
import io.lumnik.hub.connector.transport.FileDescriptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A csv-file connector reading ONE local file holds the source's state (#1195): the file is the
 * table, so a run that read all of it removes the rows it no longer has. Every other csv source is a
 * drop folder and only ever adds — a glob grows file by file, and a remote transport keeps a
 * watermark over the files it took.
 *
 * <p>A run sweeps only when it read the file from its first row to its end and the run succeeded:
 * an unchanged file is not read again, and a run that resumed a file half-read by a failed one did
 * not meet the first half. Neither removes anything.
 *
 * <p>Two guards, both refusing the run with nothing removed: an empty file, and a removal larger than
 * {@code refresh.max_removed} of the source's rows (30% unless declared; {@code off} or
 * {@code 100%} lifts it). A refused purge is recovered by configuration and a re-run; an accidental
 * one — a truncated export — is not.
 */
public final class CsvFullRefresh {

  private CsvFullRefresh() {}

  static final String KEY = "refresh";
  static final String MAX_REMOVED = "max_removed";
  static final double DEFAULT_MAX_REMOVED = 0.30;

  /** The one local file this connector's source is, when it is one: the local transport, a path without a glob. */
  static Optional<String> stateFile(Map<String, Object> config) {
    Map<String, Object> transport;
    try {
      transport = CsvFileConnectorType.resolveTransport(config);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String kind = String.valueOf(transport.getOrDefault("kind", "local"));
    return transport.get("path") instanceof String p && isOneLocalFile(kind, p) ? Optional.of(p) : Optional.empty();
  }

  /** The rule itself, for a caller holding the manifest rather than the stored config (the validator). */
  public static boolean isOneLocalFile(String transportKind, String path) {
    return "local".equals(transportKind) && !path.isBlank() && !CsvGlobResolver.isGlob(path);
  }

  /**
   * {@code refresh.max_removed} as a fraction: a percentage such as {@code "30%"}, or {@code "off"}; 1
   * lifts the limit. Absent → {@link #DEFAULT_MAX_REMOVED}. A manifest declares it as a string (the
   * apply door types it so); a bare number stored through the flat connector door reads the same.
   *
   * @throws ConfigValidationException naming the accepted forms
   */
  static double maxRemoved(Map<String, Object> config) {
    if (!(config.get(KEY) instanceof Map<?, ?> refresh) || refresh.get(MAX_REMOVED) == null) {
      return DEFAULT_MAX_REMOVED;
    }
    String text = String.valueOf(refresh.get(MAX_REMOVED)).trim();
    return parse(text).orElseThrow(() -> new ConfigValidationException(problem(text)));
  }

  /** The apply door's check of {@code spec.refresh.max_removed}: the same parse the run uses. */
  public static Optional<String> maxRemovedProblem(JsonNode value) {
    String text = value.asText().trim();
    return parse(text).isPresent() ? Optional.empty() : Optional.of(problem(text));
  }

  private static Optional<Double> parse(String text) {
    if (text.equalsIgnoreCase("off")) return Optional.of(1.0);
    try {
      double percent = Double.parseDouble(text.endsWith("%") ? text.substring(0, text.length() - 1).trim() : text);
      if (percent >= 0 && percent <= 100) return Optional.of(percent / 100);
    } catch (NumberFormatException ignored) {
      // not a number: the caller names the accepted forms
    }
    return Optional.empty();
  }

  private static String problem(String text) {
    return "refresh.max_removed must be a percentage from 0% to 100% (30% by default), or off — got " + text;
  }

  /** {@code 0.3} as {@code 30%}, the way the manifest says it. */
  static String percent(double fraction) {
    double p = fraction * 100;
    return (p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p)) + "%";
  }

  static final String EMPTY_FILE = "the file is empty: a full refresh never empties a table from an "
      + "empty file — nothing was removed. If the source really is empty, delete the connector's data "
      + "instead (lm connector delete).";

  /** The refusal a sweep over the ratio becomes: the counts, the limit, and the gesture. */
  static StreamEnd refused(UnseenRowSweeper.Count r, double max) {
    return new StreamEnd.Refused(r.unseen() + " of " + r.before() + " rows would be removed: the file holds "
        + r.met() + " where the table held " + r.before() + ", more than refresh.max_removed ("
        + percent(max) + ") allows — nothing was removed. If the file really lost them, raise "
        + "refresh.max_removed (or set it to off) and run again.");
  }

  /**
   * A row of the file in this run's dead letters — unreadable, dropped by {@code validate-row}, any
   * reason (#1253) — is still the file's: its last good version stays.
   */
  static StreamEnd deadLettered(long rows, UUID runId) {
    return new StreamEnd.Refused(rows + " row(s) of the file went to the dead letters this run (lm dlq list "
        + "--run " + runId + "): a full refresh removes nothing while a row of the file is in the dead "
        + "letters, whatever its reason (unreadable, dropped by validate-row…) — nothing was removed. Fix "
        + "the cause each dead letter reports (lm dlq get <id>) and run again.");
  }

  static final String CHANGED_WHILE_READ = "the file changed while this run was reading it, so the run met "
      + "parts of two versions — nothing was removed. The next run reads it again in full.";

  /** The run-scope key of a run's {@link Pass} over one file. */
  record PassKey(String path) {}

  /**
   * How the run's read of the file went: whether it started at the first row, how many rows it met,
   * and whether the file stayed the same version from the first read to the last. Holds no resource;
   * the run scope only keeps {@code AutoCloseable}s.
   */
  static final class Pass implements AutoCloseable {
    final boolean fromStart;
    private final FileDescriptor first;
    boolean changed;
    long events;

    Pass(boolean fromStart, FileDescriptor first) {
      this.fromStart = fromStart;
      this.first = first;
    }

    /**
     * One more read of the file, with its version taken {@code before} and {@code after} it: a version
     * that moved during the read, or since the run's first read, taints the pass — so does one that
     * could not be taken. Both ends, because a write landing during the only (or last) read is seen
     * by nothing else.
     */
    void observe(FileDescriptor before, FileDescriptor after, long read) {
      if (first == null || before == null || !first.sameVersion(before) || !before.sameVersion(after)) {
        changed = true;
      }
      events += read;
    }

    @Override
    public void close() {
      // nothing to release
    }
  }
}
