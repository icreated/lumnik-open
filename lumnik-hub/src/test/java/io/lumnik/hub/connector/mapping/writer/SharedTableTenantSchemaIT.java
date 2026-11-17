package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.ask.RegistrySchemaBuilder;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.entity.MissingSourceColumnException;
import io.lumnik.hub.entity.MissingSourceTableException;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #2023: a {@code connector.t_*} table is one physical table for every tenant ingesting under its name, and RLS keeps the
 * rows apart. Its shape must not cross over either: a tenant's schema (what its Ask card lists) holds the columns its own
 * data brought, and an Entity it applies cannot name a column only another tenant brought — refused as a column that
 * does not exist, never as one it may not see.
 */
@QuarkusTest
class SharedTableTenantSchemaIT {

  private static final String TABLE = "t_shared2023";
  private static final long OTHER = 1;
  private static final long TENANT = 2;
  private static final UUID OTHER_CONNECTOR = UUID.fromString("20230000-0000-0000-0000-0000000000b1");
  private static final UUID CONNECTOR = UUID.fromString("20230000-0000-0000-0000-0000000000b2");

  @Inject DynamicTableWriter writer;
  @Inject EntityViewCompiler compiler;
  @Inject RegistrySchemaBuilder cards;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @BeforeEach
  void anotherTenantMadeTheTableWithAColumnOfItsOwn() {
    clean();
    execSql(adminDs, connector(OTHER_CONNECTOR, OTHER, "sh-other"));
    execSql(adminDs, connector(CONNECTOR, TENANT, "sh-mine"));
    write(OTHER_CONNECTOR, OTHER, Map.of("code", "O-1", "label", "theirs", "margin", "secret"));
  }

  @AfterEach
  void clean() {
    execSql(adminDs, "DELETE FROM connector.entity_manifest WHERE name = 'shared2023'");
    execSql(adminDs, "DROP VIEW IF EXISTS connector.\"v_shared2023\"");
    execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name = '" + TABLE + "'");
    execSql(adminDs, "DROP TABLE IF EXISTS connector." + TABLE);
    execSql(adminDs, "DELETE FROM connector.connector WHERE id IN ('" + OTHER_CONNECTOR + "', '" + CONNECTOR + "')");
  }

  @Test
  void a_tenants_card_lists_only_the_columns_its_data_brought() {
    write(CONNECTOR, TENANT, Map.of("code", "M-1", "label", "mine"));

    assertThat(columns(TENANT)).containsOnlyKeys("code", "label");
    assertThat(columns(OTHER)).containsKeys("code", "label", "margin");
  }

  /** A column the other tenant made first is recorded with its physical type, whatever this chunk would infer. */
  @Test
  void a_column_another_tenant_made_is_recorded_with_its_physical_type() {
    write(CONNECTOR, TENANT, Map.of("code", "M-1", "label", "mine"));
    write(CONNECTOR, TENANT, Map.of("code", "M-2", "label", "mine", "margin", 12));   // a number over their text

    assertThat(columns(TENANT)).containsEntry("margin", columns(OTHER).get("margin"));
  }

  /**
   * A tenant's data may change a column every tenant reads, losslessly and one way only: a fraction widens a whole-number
   * column to numeric (every value still reads), and a value the column cannot hold is refused, the type unchanged.
   */
  @Test
  void a_type_changes_only_by_a_lossless_widening() {
    write(OTHER_CONNECTOR, OTHER, Map.of("code", "O-2", "label", "theirs", "margin", "secret", "qty", 3));
    assertThat(physicalType("qty")).isEqualTo("bigint");

    write(CONNECTOR, TENANT, Map.of("code", "M-1", "label", "mine", "qty", 1.5));
    assertThat(physicalType("qty")).isEqualTo("numeric");

    assertThatThrownBy(() -> write(CONNECTOR, TENANT, Map.of("code", "M-2", "label", "mine", "qty", "many")));
    assertThat(physicalType("qty")).as("never narrowed, never converted to text").isEqualTo("numeric");
  }

  /** The fraction comes in a later chunk, on a column new to this tenant but whole-number in the table: widened too. */
  @Test
  void a_fraction_on_a_column_another_tenant_made_whole_number_widens_it() {
    write(OTHER_CONNECTOR, OTHER, Map.of("code", "O-2", "label", "theirs", "margin", "secret", "qty", 3));
    write(CONNECTOR, TENANT, Map.of("code", "M-1", "label", "mine"));

    write(CONNECTOR, TENANT, Map.of("code", "M-2", "label", "mine", "qty", 748.5));

    assertThat(physicalType("qty")).isEqualTo("numeric");
    assertThat(columns(TENANT)).containsEntry("qty", "NUMERIC");
  }

  /** One tenant, two mappings into one table, each bringing its own columns: the card holds both. */
  @Test
  void a_tenants_card_holds_every_mapping_of_the_table() {
    write(CONNECTOR, TENANT, "first", Map.of("code", "M-1", "label", "mine"));
    write(CONNECTOR, TENANT, "second", Map.of("code", "M-2", "colour", "red"));

    assertThat(columns(TENANT)).containsOnlyKeys("code", "label", "colour");
  }

  /** Both tenants own qty; one widens it: the other's card follows the table, and still lists only its own columns. */
  @Test
  void a_widening_reaches_the_card_of_every_tenant_that_owns_the_column() {
    write(OTHER_CONNECTOR, OTHER, Map.of("code", "O-2", "label", "theirs", "margin", "secret", "qty", 3));
    write(CONNECTOR, TENANT, Map.of("code", "M-1", "label", "mine", "qty", 4));
    assertThat(columns(TENANT)).containsEntry("qty", "BIGINT");

    write(OTHER_CONNECTOR, OTHER, Map.of("code", "O-3", "label", "theirs", "margin", "secret", "qty", 2.5));

    assertThat(columns(TENANT)).containsEntry("qty", "NUMERIC").doesNotContainKey("margin");
  }

  private String physicalType(String column) {
    return Jdbc.queryOne(adminDs, "test.type", "SELECT data_type FROM information_schema.columns "
        + "WHERE table_schema = 'connector' AND table_name = ? AND column_name = ?", ps -> {
          ps.setString(1, TABLE);
          ps.setString(2, column);
        }, rs -> rs.getString(1)).orElseThrow();
  }

  @Test
  void an_entity_cannot_name_a_column_only_another_tenant_brought() {
    write(CONNECTOR, TENANT, Map.of("code", "M-1", "label", "mine"));

    assertThatThrownBy(() -> apply("margin")).isInstanceOf(MissingSourceColumnException.class)
        .hasMessageContaining("column \"margin\" does not exist");
    assertThatThrownBy(() -> apply("nosuch")).isInstanceOf(MissingSourceColumnException.class)
        .hasMessageContaining("column \"nosuch\" does not exist");
    apply("label");
  }

  /** A table only another tenant ingests reads as one not made yet: the honest refusal, and the same as a fresh one. */
  @Test
  void an_entity_over_a_table_this_tenant_never_ingested_reads_as_not_made_yet() {
    assertThatThrownBy(() -> apply("label")).isInstanceOf(MissingSourceTableException.class)
        .hasMessageContaining("relation \"connector." + TABLE + "\" does not exist");
  }

  private Map<String, String> columns(long tenant) {
    return cards.resolve(tenant, "sh2023").stream().filter(t -> t.name().equals(TABLE)).findFirst()
        .orElseThrow().columns();
  }

  private void apply(String column) {
    String yaml = "kind: Entity\nname: Shared2023\nscopes: [sh2023]\nmatch: [code]\nsources:\n  - table: " + TABLE
        + "\n    map: { code: code, " + column + ": " + column + " }\n";
    compiler.apply(new EntityManifestParser().parse(yaml), yaml, null, TENANT);
  }

  private void write(UUID connector, long tenant, Map<String, Object> row) {
    write(connector, tenant, TABLE, row);
  }

  private void write(UUID connector, long tenant, String mapping, Map<String, Object> row) {
    MappingTarget.Table spec = new MappingTarget.Table("connector", TABLE, true, MappingTarget.OnConflict.SKIP, List.of());
    Map<String, Object> sys = new HashMap<>(Map.of("_row_hash", UUID.randomUUID().toString(), "_tenant", tenant,
        "_mapping", mapping, "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test"));
    runInTxAsSystem(utx, () -> writer.write(spec, List.of(row), List.of(sys), tenant, connector));
  }

  private static String connector(UUID id, long tenant, String name) {
    return "INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, sync_mode, skip_policy) "
        + "VALUES ('" + id + "', " + tenant + ", '" + name + "', 'jdbc-generic', '{}'::jsonb, true, ARRAY['sh2023'], "
        + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)";
  }
}
