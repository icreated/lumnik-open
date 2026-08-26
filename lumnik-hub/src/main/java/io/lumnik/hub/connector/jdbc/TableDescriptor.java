package io.lumnik.hub.connector.jdbc;

import java.util.List;
import java.util.Optional;

/** Describes a discovered table for mapping generation and ingest. */
public record TableDescriptor(
    String schema,
    String name,
    List<String> primaryKey,
    List<ColumnDescriptor> columns,
    List<FkDescriptor> foreignKeys,
    Optional<String> suggestedWatermark,
    long rowEstimate
) {}
