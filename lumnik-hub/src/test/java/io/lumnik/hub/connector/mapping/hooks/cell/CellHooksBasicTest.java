package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CellHooksBasicTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void clean_strips_whitespace_and_normalizes() throws Exception {
    var hook = catalog.cell("clean");
    var params = yaml("columns: ['*']\nstrip: true\nremove-control-chars: true");
    var row = new HashMap<String, Object>(Map.of("name", "  Alice  ", "code", "ABC"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("name", "Alice").containsEntry("code", "ABC");
  }

  @Test
  void clean_strips_bom_prefix() throws Exception {
    var hook = catalog.cell("clean");
    var params = yaml("columns: ['header']\nstrip: true");
    var row = new HashMap<String, Object>(Map.of("header", "﻿email"));
    hook.apply(row, params, new MappingState());
    assertThat((String) row.get("header")).isEqualTo("email");
  }

  @Test
  void lower_lowercases_specified_columns() throws Exception {
    var hook = catalog.cell("lower");
    var params = yaml("columns: [email]");
    var row = new HashMap<String, Object>(Map.of("email", "ALICE@X.COM", "name", "Alice"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("email", "alice@x.com").containsEntry("name", "Alice");
  }

  @Test
  void upper_uppercases_specified_columns() throws Exception {
    var hook = catalog.cell("upper");
    var params = yaml("columns: [code]");
    var row = new HashMap<String, Object>(Map.of("code", "abc"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("code", "ABC");
  }

  @Test
  void titlecase_capitalizes_each_word() throws Exception {
    var hook = catalog.cell("titlecase");
    var params = yaml("columns: [name]");
    var row = new HashMap<String, Object>(Map.of("name", "alice martin"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("name", "Alice Martin");
  }

  @Test
  void split_extracts_named_fields() throws Exception {
    var hook = catalog.cell("split");
    var params = yaml("column: composite\nby: ' - '\ninto: [group, sku]");
    var row = new HashMap<String, Object>(Map.of("composite", "WIDGET - SKU123"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("group", "WIDGET").containsEntry("sku", "SKU123");
  }

  @Test
  void truncate_cuts_at_length() throws Exception {
    var hook = catalog.cell("truncate");
    var params = yaml("column: desc\nlength: 5");
    var row = new HashMap<String, Object>(Map.of("desc", "abcdefghij"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("desc", "abcde");
  }

  @Test
  void default_replaces_null() throws Exception {
    var hook = catalog.cell("default");
    var params = yaml("column: country\nvalue: FR");
    var row = new HashMap<String, Object>();
    row.put("country", null);
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("country", "FR");
  }

  @Test
  void default_does_not_overwrite() throws Exception {
    var hook = catalog.cell("default");
    var params = yaml("column: country\nvalue: FR");
    var row = new HashMap<String, Object>(Map.of("country", "US"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("country", "US");
  }

  @Test
  void mask_keeps_last_4_chars() throws Exception {
    var hook = catalog.cell("mask");
    var params = yaml("column: ssn\nkeep-last: 4");
    var row = new HashMap<String, Object>(Map.of("ssn", "123-45-6789"));
    hook.apply(row, params, new MappingState());
    assertThat((String) row.get("ssn")).endsWith("6789").doesNotContain("123");
  }

  @Test
  void to_uuid_validates_valid_uuid() throws Exception {
    var hook = catalog.cell("to-uuid");
    var params = yaml("column: id");
    var validUuid = "11111111-2222-3333-4444-555555555555";
    var row = new HashMap<String, Object>(Map.of("id", validUuid));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("id", validUuid);
  }

  @Test
  void to_uuid_generates_when_invalid_and_generate_true() throws Exception {
    var hook = catalog.cell("to-uuid");
    var params = yaml("column: id\ngenerate: true");
    var row = new HashMap<String, Object>(Map.of("id", "not-a-uuid"));
    hook.apply(row, params, new MappingState());
    String result = (String) row.get("id");
    assertThat(result).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
}
