package io.lumnik.hub.connector.csv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.transport.FileDescriptor;

import java.time.Instant;
import java.util.List;

/**
 * Resume cursor for the CSV file connector.
 *
 * <p>Legacy JSON shape (always valid, always written by non-checkpointable windows):
 * {@code {"file":"/imports/x.csv","rowsProcessed":42}} — on resume the connector re-opens the
 * file and skips {@code rowsProcessed} events (the skip-parse path).
 *
 * <p>Extended shape (lot-5b, the byte-offset variant this record's original javadoc promised):
 * the same two fields plus an optional checkpoint —
 * {@code anchorByte}/{@code charDelta} (a {@link CheckpointingReader.Checkpoint}: byte-seek to
 * the anchor, skip the char delta), {@code lineBase} (physical lines before the anchor record,
 * so DLQ line numbers stay file-absolute in resumed windows), {@code headers} (the stripped
 * column names, so a resumed window can parse mid-file without the header line) and
 * {@code version} (the bytes the checkpoint was taken against). Compatibility is bidirectional by construction: a legacy
 * cursor deserializes with the checkpoint fields {@code null} and {@link #hasCheckpoint()}
 * false → fallback path; an extended cursor still carries valid legacy fields, so an old build
 * (or a fallback window) reads {@code file}/{@code rowsProcessed} and skip-parses correctly.
 * No migration in either direction.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CsvCursor(String file, long rowsProcessed,
                        Long anchorByte, Long charDelta, Long lineBase,
                        List<String> headers, Version version) {

  /**
   * The version token: identifies the bytes the checkpoint describes. {@code lastModified} is
   * ISO-8601 ({@link Instant#toString()}) — a string, not epoch millis, because file mtimes can
   * carry sub-millisecond precision and a truncating round-trip would fail {@link #matches}
   * forever (a permanent, silent fallback).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Version(long size, String lastModified, String etag) {

    public static Version of(FileDescriptor d) {
      return new Version(d.size(), d.lastModified().toString(), d.etag());
    }

    /** True when {@code current} still has the bytes this token was taken against. */
    public boolean matches(FileDescriptor current) {
      if (current == null || lastModified == null) return false;
      Instant lm;
      try {
        lm = Instant.parse(lastModified);
      } catch (Exception corruptToken) {
        return false;
      }
      // Delegate to the single truth for "same bytes" (size + mtime + etag-when-both-present).
      return new FileDescriptor(current.path(), size, lm, etag).sameVersion(current);
    }
  }

  /** Legacy two-field cursor — the shape every fallback window keeps writing. */
  public CsvCursor(String file, long rowsProcessed) {
    this(file, rowsProcessed, null, null, null, null, null);
  }

  /**
   * True when this cursor carries a complete, sane checkpoint: all fields present, coordinates
   * non-negative, and the anchor strictly inside the recorded file size (an anchor at or past
   * EOF cannot name a record start — a corrupt cursor must fall back, never "resume" onto an
   * empty stream and report the file done).
   */
  public boolean hasCheckpoint() {
    return anchorByte != null && anchorByte >= 0
        && charDelta != null && charDelta >= 0
        && lineBase != null && lineBase >= 0
        && headers != null && !headers.isEmpty()
        && version != null
        && anchorByte < version.size();
  }

  // Tolerant reads: the persisted node also carries "type":"CsvFile", and a build must never
  // explode on fields added after it shipped — unknown properties are the compatibility seam.
  private static final ObjectMapper M = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public String toJson() {
    try {
      return M.writeValueAsString(this);
    } catch (Exception e) {
      throw new RuntimeException("CsvCursor serialize: " + e.getMessage(), e);
    }
  }

  public static CsvCursor fromJson(String json) {
    if (json == null || json.isBlank()) return empty();
    try {
      return M.readValue(json, CsvCursor.class);
    } catch (Exception e) {
      throw new RuntimeException("CsvCursor deserialize: " + e.getMessage(), e);
    }
  }

  public static CsvCursor empty() {
    return new CsvCursor("", 0);
  }
}
