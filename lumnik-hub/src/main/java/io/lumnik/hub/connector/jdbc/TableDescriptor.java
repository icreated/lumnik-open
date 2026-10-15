package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.spi.FkDescriptor;
import io.lumnik.jdbc.spi.ColumnDescriptor;
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
    long rowEstimate,
    /** Columns the source's dialect cannot read, as {@code NAME TYPE}; empty when the table is clean. */
    List<String> unsupportedColumns
) {}
