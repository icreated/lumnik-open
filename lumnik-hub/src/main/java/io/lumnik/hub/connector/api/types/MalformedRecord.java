package io.lumnik.hub.connector.api.types;

import java.util.List;

/**
 * A source row that could not be ingested as-is.
 *
 * <p>{@code rawValues != null} → a recoverable per-row failure (e.g. wrong column count); the
 * partial cell values are preserved for inspection/replay. {@code rawValues == null} → a
 * tokenizer-desync / file-fatal failure (no parsable record; the rest of the file is suspect).
 * {@code declaredSkip} → not a failure at all: a row the config declared skippable
 * ({@code parser.short_rows: skip-leading}, #1863), counted as read and as skipped and never dead-lettered.
 */
public record MalformedRecord(String file, long line, List<String> rawValues, String message, boolean declaredSkip) {

  /** A row isolated by failure: it goes to the dead letters. */
  public MalformedRecord(String file, long line, List<String> rawValues, String message) {
    this(file, line, rawValues, message, false);
  }
}
