package io.lumnik.hub.connector.api.types;

import java.util.List;

/**
 * A source row that could not be ingested as-is.
 *
 * <p>{@code rawValues != null} → a recoverable per-row failure (e.g. wrong column count); the
 * partial cell values are preserved for inspection/replay. {@code rawValues == null} → a
 * tokenizer-desync / file-fatal failure (no parsable record; the rest of the file is suspect).
 */
public record MalformedRecord(String file, long line, List<String> rawValues, String message) {}
