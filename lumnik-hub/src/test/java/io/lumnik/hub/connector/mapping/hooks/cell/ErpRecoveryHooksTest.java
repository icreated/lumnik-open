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
class ErpRecoveryHooksTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  // ── fix-mojibake ────────────────────────────────────────────────────

  @Test
  void fix_mojibake_recovers_french_accents() throws Exception {
    var hook = catalog.cell("fix-mojibake");
    var params = yaml("columns: [name]");
    // "Bénédict" encoded UTF-8 then decoded as Latin-1 = "BÃ©nÃ©dict"
    var row = new HashMap<String, Object>(Map.of("name", "BÃ©nÃ©dict"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("name", "Bénédict");
  }

  @Test
  void fix_mojibake_leaves_clean_strings_unchanged() throws Exception {
    var hook = catalog.cell("fix-mojibake");
    var params = yaml("columns: [name]");
    var row = new HashMap<String, Object>(Map.of("name", "Already clean"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("name", "Already clean");
  }

  @Test
  void fix_mojibake_handles_null_safely() throws Exception {
    var hook = catalog.cell("fix-mojibake");
    var params = yaml("columns: [name]");
    var row = new HashMap<String, Object>();
    row.put("name", null);
    hook.apply(row, params, new MappingState());
    assertThat(row.get("name")).isNull();
  }

  // ── replace-smart-quotes ────────────────────────────────────────────

  @Test
  void replace_smart_quotes_converts_curly_to_ascii() throws Exception {
    var hook = catalog.cell("replace-smart-quotes");
    var params = yaml("columns: [text]");
    var row = new HashMap<String, Object>(Map.of("text", "“Hello” he said, ‘world’…"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("text", "\"Hello\" he said, 'world'...");
  }

  @Test
  void replace_smart_quotes_replaces_em_dash_and_en_dash() throws Exception {
    var hook = catalog.cell("replace-smart-quotes");
    var params = yaml("columns: [text]");
    var row = new HashMap<String, Object>(Map.of("text", "page 1–2 — next"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("text", "page 1-2 - next");
  }

  @Test
  void replace_smart_quotes_french_guillemets() throws Exception {
    var hook = catalog.cell("replace-smart-quotes");
    var params = yaml("columns: [text]");
    var row = new HashMap<String, Object>(Map.of("text", "«Bonjour»"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("text", "\"Bonjour\"");
  }

  // ── decode-html-entities ───────────────────────────────────────────

  @Test
  void decode_html_entities_basic() throws Exception {
    var hook = catalog.cell("decode-html-entities");
    var params = yaml("columns: [desc]");
    var row = new HashMap<String, Object>(Map.of("desc", "Coffee &amp; cream &lt;hot&gt;"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("desc", "Coffee & cream <hot>");
  }

  @Test
  void decode_html_entities_named_and_numeric() throws Exception {
    var hook = catalog.cell("decode-html-entities");
    var params = yaml("columns: [desc]");
    var row = new HashMap<String, Object>(Map.of("desc", "&copy; 2026 &mdash; price &euro;100 &#33;"));
    hook.apply(row, params, new MappingState());
    String result = (String) row.get("desc");
    assertThat(result).contains("©").contains("—").contains("€100").contains("!");
  }

  // ── remove-diacritics ───────────────────────────────────────────────

  @Test
  void remove_diacritics_strips_accents() throws Exception {
    var hook = catalog.cell("remove-diacritics");
    var params = yaml("columns: [searchKey]");
    var row = new HashMap<String, Object>(Map.of("searchKey", "Café Bénédict ñoño"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("searchKey", "Cafe Benedict nono");
  }

  @Test
  void remove_diacritics_preserves_ascii() throws Exception {
    var hook = catalog.cell("remove-diacritics");
    var params = yaml("columns: [searchKey]");
    var row = new HashMap<String, Object>(Map.of("searchKey", "Plain ASCII text"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("searchKey", "Plain ASCII text");
  }

  // ── strip-invisible ─────────────────────────────────────────────────

  @Test
  void strip_invisible_removes_zwsp_and_zwj() throws Exception {
    var hook = catalog.cell("strip-invisible");
    var params = yaml("columns: [name]");
    // "Alice" with U+200B ZWSP inserted between letters
    var row = new HashMap<String, Object>(Map.of("name", "A​li​ce"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("name", "Alice");
  }

  @Test
  void strip_invisible_removes_soft_hyphen_and_bom() throws Exception {
    var hook = catalog.cell("strip-invisible");
    var params = yaml("columns: [text]");
    // U+00AD soft hyphen + U+FEFF BOM in the middle
    var row = new HashMap<String, Object>(Map.of("text", "Hel­lo﻿world"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("text", "Helloworld");
  }

  // ── combined recovery ──────────────────────────────────────────────

  @Test
  void combined_recovery_for_mangled_excel_export() throws Exception {
    // Simulates a CSV exported from Excel with: smart quotes + HTML entities
    var hook1 = catalog.cell("replace-smart-quotes");
    var hook2 = catalog.cell("decode-html-entities");
    var params1 = yaml("columns: [desc]");
    var params2 = yaml("columns: [desc]");

    var row = new HashMap<String, Object>(Map.of(
        "desc", "Coffee “Deluxe” &amp; cream &mdash; €10"));

    hook1.apply(row, params1, new MappingState());
    hook2.apply(row, params2, new MappingState());

    assertThat(row).containsEntry("desc", "Coffee \"Deluxe\" & cream — €10");
  }
}
