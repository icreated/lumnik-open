package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CellHooksParseTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void regex_extract_captures_named_groups() throws Exception {
    var hook = catalog.cell("regex-extract");
    var params = yaml("""
      column: rawSegment
      pattern: '^([A-Z]{2,5})\\s*-\\s*(.+)$'
      groups: { 1: segment, 2: segmentLabel }
      """);
    var row = new HashMap<String, Object>(Map.of("rawSegment", "PRO - Premium"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("segment", "PRO").containsEntry("segmentLabel", "Premium");
  }

  @Test
  void regex_replace_substitutes() throws Exception {
    var hook = catalog.cell("regex-replace");
    var params = yaml("column: phone\npattern: '\\s+'\nreplacement: ''");
    var row = new HashMap<String, Object>(Map.of("phone", "01 23 45 67 89"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("phone", "0123456789");
  }

  @Test
  void parse_date_iso_format() throws Exception {
    var hook = catalog.cell("parse-date");
    var params = yaml("column: createdAt\nformats: ['yyyy-MM-dd']");
    var row = new HashMap<String, Object>(Map.of("createdAt", "2026-05-23"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("createdAt")).isEqualTo(LocalDate.of(2026, 5, 23));
  }

  @Test
  void parse_date_multiple_formats_auto_detect() throws Exception {
    var hook = catalog.cell("parse-date");
    var params = yaml("column: d\nformats: ['yyyy-MM-dd', 'dd/MM/yyyy', 'MM/dd/yyyy']");
    var row = new HashMap<String, Object>(Map.of("d", "23/05/2026"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("d")).isEqualTo(LocalDate.of(2026, 5, 23));
  }

  @Test
  void parse_date_unparseable_becomes_null_soft() throws Exception {
    var hook = catalog.cell("parse-date");
    var params = yaml("column: d\nformats: ['yyyy-MM-dd']");
    var row = new HashMap<String, Object>(Map.of("d", "not-a-date"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("d")).isNull();
  }

  @Test
  void parse_number_handles_comma_decimal() throws Exception {
    var hook = catalog.cell("parse-number");
    var params = yaml("column: amount\ndecimal-separator: ','");
    var row = new HashMap<String, Object>(Map.of("amount", "1234,56"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("amount")).isInstanceOf(BigDecimal.class);
    assertThat(row.get("amount").toString()).isEqualTo("1234.56");
  }

  @Test
  void parse_number_handles_parens_negative() throws Exception {
    var hook = catalog.cell("parse-number");
    var params = yaml("column: amount\nnegatives-in-parens: true");
    var row = new HashMap<String, Object>(Map.of("amount", "(123.45)"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("amount").toString()).isEqualTo("-123.45");
  }

  @Test
  void parse_email_lowers_and_validates() throws Exception {
    var hook = catalog.cell("parse-email");
    var params = yaml("column: email\nlower: true");
    var row = new HashMap<String, Object>(Map.of("email", "ALICE@X.COM"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("email", "alice@x.com");
  }

  @Test
  void parse_email_invalid_becomes_null() throws Exception {
    var hook = catalog.cell("parse-email");
    var params = yaml("column: email");
    var row = new HashMap<String, Object>(Map.of("email", "not-an-email"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("email")).isNull();
  }

  @Test
  void parse_phone_normalizes_to_e164() throws Exception {
    var hook = catalog.cell("parse-phone");
    var params = yaml("column: phone\ndefault-region: FR");
    var row = new HashMap<String, Object>(Map.of("phone", "01 23 45 67 89"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("phone", "+33123456789");
  }

  @Test
  void lookup_replaces_with_value_from_table() throws Exception {
    var hook = catalog.cell("lookup");
    var params = yaml("column: country\ntable:\n  FR: France\n  US: United States");
    var row = new HashMap<String, Object>(Map.of("country", "FR"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("country", "France");
  }

  @Test
  void lookup_on_missing_keep_preserves_original() throws Exception {
    var hook = catalog.cell("lookup");
    var params = yaml("column: country\ntable:\n  FR: France\non-missing: keep");
    var row = new HashMap<String, Object>(Map.of("country", "XX"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("country", "XX");
  }

  @Test
  void lookup_on_missing_null_returns_null() throws Exception {
    var hook = catalog.cell("lookup");
    var params = yaml("column: country\ntable:\n  FR: France\non-missing: 'null'");
    var row = new HashMap<String, Object>(Map.of("country", "XX"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("country")).isNull();
  }
}
