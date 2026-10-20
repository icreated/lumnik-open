package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1196: a keyed table's guard on one chunk, and the upsert it writes. */
class DynamicTableKeyTest {

  private static final MappingTarget.Table KEYED = new MappingTarget.Table(
      "connector", "t_lines", true, MappingTarget.OnConflict.SKIP, List.of(), false, List.of("order_id", "line"));

  @Test
  void the_same_row_read_twice_is_one_row() {
    assertThatCode(() -> SourceKeyIndex.refuseRepeatedKeyInChunk(KEYED,
        List.of(row(1, 1, "a"), row(1, 1, "a")), List.of(hash("h1"), hash("h1"))))
        .doesNotThrowAnyException();
  }

  @Test
  void two_rows_that_differ_under_one_key_say_the_key_is_not_one_and_name_no_value() {
    assertThatThrownBy(() -> SourceKeyIndex.refuseRepeatedKeyInChunk(KEYED,
        List.of(row(1, 1, "a"), row(1, 1, "secret-b")), List.of(hash("h1"), hash("h2"))))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("pk_columns (order_id, line)")
        .hasMessageContaining("connector.t_lines")
        .hasMessageNotContaining("secret-b");
  }

  @Test
  void rows_that_share_part_of_the_key_are_two_keys() {
    assertThatCode(() -> SourceKeyIndex.refuseRepeatedKeyInChunk(KEYED,
        List.of(row(1, 1, "a"), row(1, 2, "b")), List.of(hash("h1"), hash("h2"))))
        .doesNotThrowAnyException();
  }

  @Test
  void the_upsert_replaces_a_changed_row_of_this_tenants_source_and_skips_an_unchanged_one() {
    String clause = DynamicTableWriter.upsertClause(KEYED, List.of("order_id", "line", "label"), 7L, "jdbc:lines");

    assertThat(clause)
        .contains("ON CONFLICT (\"order_id\", \"line\") WHERE tenant_id = 7 AND _source = 'jdbc:lines' DO UPDATE SET")
        .contains("\"label\" = EXCLUDED.\"label\"")
        .contains("_ingested_at = now()")
        .endsWith("WHERE \"t_lines\"._row_hash IS DISTINCT FROM EXCLUDED._row_hash")
        .as("the key columns are what the conflict matched on, never rewritten")
        .doesNotContain("\"order_id\" = EXCLUDED");
  }

  @Test
  void a_long_table_name_keeps_its_tenant_and_a_hash_within_63_bytes() {
    String longName = "t_" + "x".repeat(61);
    MappingTarget.Table a = new MappingTarget.Table("connector", longName, true, MappingTarget.OnConflict.SKIP,
        List.of(), false, List.of("id"));
    MappingTarget.Table b = new MappingTarget.Table("connector", longName.substring(0, 62) + "y", true,
        MappingTarget.OnConflict.SKIP, List.of(), false, List.of("id"));

    assertThat(SourceKeyIndex.indexName(a, 12L)).hasSizeLessThanOrEqualTo(63).endsWith("_key_12");
    assertThat(SourceKeyIndex.indexName(a, 12L)).isNotEqualTo(SourceKeyIndex.indexName(b, 12L));
    assertThat(SourceKeyIndex.indexName(a, 12L)).isNotEqualTo(SourceKeyIndex.indexName(a, 1L));
  }

  @Test
  void two_long_names_whose_java_hash_collides_still_name_two_indexes() {
    // "Aa" and "BB" share their String.hashCode(): a name told apart only by such a hash is not told apart.
    String start = "t_" + "x".repeat(60);
    MappingTarget.Table aa = keyed(start + "Aa");
    MappingTarget.Table bb = keyed(start + "BB");

    assertThat((start + "Aa").hashCode()).isEqualTo((start + "BB").hashCode());
    assertThat(SourceKeyIndex.indexName(aa, 1L)).isNotEqualTo(SourceKeyIndex.indexName(bb, 1L));
  }

  private static MappingTarget.Table keyed(String name) {
    return new MappingTarget.Table("connector", name, true, MappingTarget.OnConflict.SKIP, List.of(), false, List.of("id"));
  }

  private static Map<String, Object> row(int order, int line, String label) {
    return Map.of("order_id", order, "line", line, "label", label);
  }

  private static Map<String, Object> hash(String h) {
    return Map.of("_row_hash", h);
  }
}
