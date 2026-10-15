package io.lumnik.jdbc.spi;

/** Describes one foreign key column of a source table. */
public record FkDescriptor(
    String column,
    String targetSchema,
    String targetTable,
    String targetColumn
) {}
