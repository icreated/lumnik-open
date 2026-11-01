package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** #1630: a resource named by its link (Spring Data REST hides the id) — its key is the link's last segment. */
class LastPathSegmentTest {

  @ParameterizedTest
  @CsvSource({
      "http://host.docker.internal:9230/products/SKU-00001, SKU-00001",
      "https://erp.example.com/api/products/SKU-00001, SKU-00001",
      "http://h/products/SKU%2F7?projection=full#top, SKU/7",
      "http://h/products/42/, 42",
      "/products/42, 42"})
  void reads_the_last_segment_of_a_link(String link, String key) {
    assertThat(HybridJsonbWriter.applyTransform(link, Optional.of(Transform.LAST_PATH_SEGMENT), "sku")).isEqualTo(key);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "http://h", "http://h/", "http://h//", "not a link"})
  void a_link_without_a_segment_reads_no_value_never_an_empty_one(String link) {
    assertThat(HybridJsonbWriter.applyTransform(link, Optional.of(Transform.LAST_PATH_SEGMENT), "sku")).isNull();
  }

  @Test
  void a_value_that_is_not_text_reads_no_value() {
    assertThat(HybridJsonbWriter.applyTransform(7, Optional.of(Transform.LAST_PATH_SEGMENT), "sku")).isNull();
  }

  @Test
  void the_key_does_not_move_with_the_host_the_hub_reached() {
    HybridJsonbSpec spec = HybridJsonbSpec.fromTargetConfig(Map.of("table", "ext.products",
        "unique_key", Map.of("path", "$._links.self.href", "transform", "last_path_segment"), "promote", List.of()));

    assertThat(HybridJsonbWriter.keyOf("http://inventory:8080/products/SKU-1", spec)).isEqualTo("SKU-1")
        .isEqualTo(HybridJsonbWriter.keyOf("https://erp.example.com/products/SKU-1", spec));
  }

  @Test
  void the_string_form_keeps_the_value_as_read() {
    HybridJsonbSpec spec = HybridJsonbSpec.fromTargetConfig(Map.of("table", "ext.products", "unique_key", "$.id"));

    assertThat(spec.uniqueKeyTransform()).isEmpty();
    assertThat(HybridJsonbWriter.keyOf(42, spec)).isEqualTo("42");
  }
}
