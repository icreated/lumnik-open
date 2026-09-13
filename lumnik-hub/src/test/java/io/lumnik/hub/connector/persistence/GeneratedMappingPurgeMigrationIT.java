package io.lumnik.hub.connector.persistence;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V43 is the only part of the guided-add mapping cut that reaches a hub already in service — the
 * install where the defect was found. The code change stops NEW orphans; this migration removes the
 * ones already sitting there, and until they are gone re-adding a deleted table still dies at commit.
 *
 * <p>So the migration is exercised rather than assumed, and on the shipped artifact: the SQL is read
 * from the classpath, not restated here, because a restatement would pass while the file that runs
 * on startup was wrong. Its statements are idempotent, which is what makes replaying them against an
 * already-migrated schema a fair test.
 *
 * <p>Two rows must SURVIVE alongside the one that goes: a template override (what
 * {@code MappingResolverImpl} still reads) and a row with no template_id that the guided add did not
 * write. A DELETE is irreversible, so "removes the right rows" is half the guarantee.
 */
@QuarkusTest
class GeneratedMappingPurgeMigrationIT {

  private static final String SQL_PATH = "/db/migration/connector/V43__drop_generated_connector_mappings.sql";

  @Inject
  @DataSource("admin")
  AgroalDataSource ds;

  @Test
  void it_removes_what_the_guided_add_wrote_and_nothing_else() throws Exception {
    String tag = "purge" + System.nanoTime();
    UUID generated = UUID.randomUUID();
    UUID override = UUID.randomUUID();
    UUID foreign = UUID.randomUUID();
    UUID connectorId = UUID.randomUUID();

    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      // The shape persistTable used to write: no template_id, spec_format yaml, the '-mapping' suffix.
      insertMapping(st, generated, tag + "--orders-mapping", "yaml", null);
      // A per-tenant template override — the population that is still read, and must not be touched.
      insertMapping(st, override, tag + "--override", "yaml-override", "erp-order");
      // No template_id either, but not this door's row: the predicate must leave it alone.
      insertMapping(st, foreign, tag + "--handwritten", "yaml", null);

      st.executeUpdate("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id) "
          + "VALUES ('" + connectorId + "', 1, '" + tag + "--orders', 'jdbc-generic', '{}'::jsonb, '" + generated + "')");

      st.execute(shippedSql());

      assertThat(exists(st, generated))
          .as("the row the guided add wrote is what blocks the re-add — it must be gone").isFalse();
      assertThat(mappingIdOf(st, connectorId))
          .as("a connector left pointing at a deleted row is a dangling link this migration made").isNull();
      assertThat(exists(st, override))
          .as("template overrides are still read by MappingResolverImpl").isTrue();
      assertThat(exists(st, foreign))
          .as("a row this door did not write is not this migration's to delete").isTrue();

      st.executeUpdate("DELETE FROM connector.connector WHERE id = '" + connectorId + "'");
      st.executeUpdate("DELETE FROM connector.connector_mapping WHERE id IN ('" + override + "', '" + foreign + "')");
    }
  }

  private static void insertMapping(Statement st, UUID id, String name, String format, String templateId)
      throws Exception {
    st.executeUpdate("INSERT INTO connector.connector_mapping "
        + "(id, tenant_id, name, target_entity, spec_format, spec_content, template_id) VALUES ('"
        + id + "', 1, '" + name + "', 'public.orders', '" + format + "', 'x', "
        + (templateId == null ? "NULL" : "'" + templateId + "'") + ")");
  }

  private static boolean exists(Statement st, UUID id) throws Exception {
    try (ResultSet rs = st.executeQuery(
        "SELECT 1 FROM connector.connector_mapping WHERE id = '" + id + "'")) {
      return rs.next();
    }
  }

  private static String mappingIdOf(Statement st, UUID connectorId) throws Exception {
    try (ResultSet rs = st.executeQuery(
        "SELECT mapping_id FROM connector.connector WHERE id = '" + connectorId + "'")) {
      assertThat(rs.next()).as("the seeded connector must still be there").isTrue();
      return rs.getString(1);
    }
  }

  private String shippedSql() throws Exception {
    try (var in = getClass().getResourceAsStream(SQL_PATH)) {
      assertThat(in).as("migration %s is on the classpath", SQL_PATH).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
