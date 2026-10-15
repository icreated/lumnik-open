package io.lumnik.hub.entity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityViewCompilerPreflightTest {

  @Test
  void the_preflight_reads_no_data_and_names_every_physical_column_once() {
    EntityManifest.Join j = new EntityManifestParser().parse(EntityManifestJoinsParserTest.BASE).joins().get(0);
    assertThat(EntityViewCompiler.preflightSql(j)).isEqualTo(
        "SELECT \"m_product_category_id\", \"name\", \"description\" "
            + "FROM connector.\"t_product_categories\" LIMIT 0");
  }

  @Test
  void a_map_key_equal_to_to_is_not_selected_twice() {
    var j = new EntityManifest.Join("t_c", "f", "k", new LinkedHashMap<>(Map.of("k", "kk")));
    assertThat(EntityViewCompiler.preflightSql(j)).isEqualTo("SELECT \"k\" FROM connector.\"t_c\" LIMIT 0");
  }

  @Test
  void a_source_preflight_names_every_mapped_column_once() {
    var src = new EntityManifest.Source("t_cust", new LinkedHashMap<>(Map.of("ROW_ID", "id")));
    assertThat(EntityViewCompiler.preflightSql(src))
        .isEqualTo("SELECT \"ROW_ID\" FROM connector.\"t_cust\" LIMIT 0");
  }

  @Test
  void the_source_exception_names_the_table_and_the_database_words() {
    var e = new MissingSourceColumnException("t_cust", "column \"NMAE\" does not exist");
    assertThat(e.getMessage()).contains("t_cust").contains("\"NMAE\"").contains("sources");
  }

  @Test
  void the_exception_names_the_lookup_table_and_the_database_words() {
    var e = new MissingLookupColumnException("t_product_categories", "column \"nme\" does not exist");
    assertThat(e.getMessage()).contains("t_product_categories").contains("\"nme\"").contains("joins");
  }
}
