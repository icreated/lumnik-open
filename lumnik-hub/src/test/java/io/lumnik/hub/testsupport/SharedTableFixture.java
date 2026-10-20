package io.lumnik.hub.testsupport;

import java.util.UUID;

/**
 * A hub table two connectors of one tenant write, as it stood before #1199 refused the second
 * writer: the second connector registered for the table the way V72 found it. Run before that
 * connector's first run, which then writes into the table instead of being refused.
 */
public final class SharedTableFixture {
  private SharedTableFixture() {}

  /** The SQL registering {@code connector} as a writer of {@code connector.<table>}, beside its first writer. */
  public static String registerAsWriter(UUID connector, String table) {
    return "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
        + "SELECT DISTINCT ON (tenant_id) tenant_id, mapping_id, table_schema, table_name, schema_inferred, '" + connector + "' "
        + "FROM connector.dynamic_table WHERE table_schema = 'connector' AND table_name = '" + table + "' "
        + "AND tenant_id = (SELECT tenant_id FROM connector.connector WHERE id = '" + connector + "')";
  }
}
