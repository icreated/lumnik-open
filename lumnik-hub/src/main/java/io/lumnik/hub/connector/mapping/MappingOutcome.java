package io.lumnik.hub.connector.mapping;

import java.util.List;

/**
 * What one {@code MappingEngine.transformAndWrite} pass produced, beyond the written rows
 * themselves: the rows it dropped (source of {@code WriteOutcome.PartialWrite}), and how many
 * CELLS the pipeline's cell hooks nulled because they could not parse the value (parse-date,
 * parse-number, parse-email, parse-phone, lookup) — the row that cell belonged to was still
 * written. {@code cellErrors} is a diagnostic count, never a row count: it must not be added to,
 * subtracted from, or compared against rows-in/out/skipped.
 */
public record MappingOutcome(List<MappingState.DroppedRow> dropped, long cellErrors) {}
