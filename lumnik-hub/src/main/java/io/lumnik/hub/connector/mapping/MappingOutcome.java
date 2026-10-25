package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.types.ScriptVersion;

import java.util.List;
import java.util.SequencedSet;

/**
 * What one {@code MappingEngine.transformAndWrite} pass produced, beyond the written rows
 * themselves: the rows it dropped (source of {@code WriteOutcome.PartialWrite}), and how many
 * CELLS the pipeline's cell hooks nulled because they could not parse the value (parse-date,
 * parse-number, parse-email, parse-phone, lookup) — the row that cell belonged to was still
 * written. {@code cellErrors} is a diagnostic count, never a row count: it must not be added to,
 * subtracted from, or compared against rows-in/out/skipped. {@code scripts}: the versions of the Kotlin scripts
 * the pass ran (#1430).
 */
public record MappingOutcome(List<MappingState.DroppedRow> dropped, long cellErrors, SequencedSet<ScriptVersion> scripts) {}
