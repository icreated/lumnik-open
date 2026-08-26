package io.lumnik.hub.connector.jdbc;

/** Describes one column of a source table. */
public record ColumnDescriptor(
    String name,
    String jdbcType,     // VARCHAR, INTEGER, TIMESTAMP, NUMERIC, BOOLEAN, JSONB, BYTEA, ...
    int size,            // column size / precision; 0 if not applicable
    boolean nullable
) {}
