package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  // The fall-through between formats, pinned on its edges (#910 stopped asking each format by
  // catching its exception): a text that FITS an earlier format's shape but cannot resolve under it
  // — month 23 — must still reach the later format; so must a text an earlier format reads only a
  // prefix of; and a text that resolves under none is nulled and counted.

  @Test
  void parse_date_falls_through_a_format_the_text_fits_but_cannot_resolve() throws Exception {
    var hook = catalog.cell("parse-date");
    var params = yaml("column: d\nformats: ['dd/MM/yyyy', 'MM/dd/yyyy']");
    var row = new HashMap<String, Object>(Map.of("d", "05/23/2026"));
    var s = new MappingState();
    hook.apply(row, params, s);
    assertThat(row.get("d")).isEqualTo(LocalDate.of(2026, 5, 23));
    assertThat(s.counters().cellErrors().get()).isZero();
  }

  @Test
  void parse_date_falls_through_a_format_that_reads_only_a_prefix() throws Exception {
    var hook = catalog.cell("parse-date");
    var params = yaml("column: d\nformats: ['yyyy-MM-dd', \"yyyy-MM-dd'T'HH:mm\"]");
    var row = new HashMap<String, Object>(Map.of("d", "2026-05-23T10:30"));
    hook.apply(row, params, new MappingState());
    assertThat(row.get("d")).isEqualTo(LocalDateTime.of(2026, 5, 23, 10, 30));
  }

  @Test
  void parse_date_counts_a_text_that_fits_every_format_shape_but_resolves_under_none() throws Exception {
    var hook = catalog.cell("parse-date");
    var params = yaml("column: d\nformats: ['dd/MM/yyyy', 'MM/dd/yyyy']");
    var row = new HashMap<String, Object>(Map.of("d", "45/45/2026"));
    var s = new MappingState();
    hook.apply(row, params, s);
    assertThat(row).containsEntry("d", null);
    assertThat(s.counters().cellErrors().get()).isEqualTo(1);
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

  /** #1463: the documented, unquoted form is a YAML null — it empties the cell, it does not keep it. */
  @Test
  void lookup_on_missing_unquoted_null_returns_null() throws Exception {
    var hook = catalog.cell("lookup");
    var params = yaml("column: country\ntable:\n  FR: France\non-missing: null");
    var row = new HashMap<String, Object>(Map.of("country", "XX"));
    var state = new MappingState();
    hook.validateParams(params);
    hook.apply(row, params, state);
    assertThat(row).containsEntry("country", null);
    assertThat(state.counters().cellErrors().get()).isEqualTo(1);
  }

  @Test
  void lookup_on_missing_absent_keeps_the_value() throws Exception {
    var hook = catalog.cell("lookup");
    var params = yaml("column: country\ntable:\n  FR: France");
    var row = new HashMap<String, Object>(Map.of("country", "XX"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("country", "XX");
  }

  /** #1467: the refusal names the column and the policy, never the cell it could not match. */
  @Test
  void lookup_on_missing_error_names_the_column_not_the_value() throws Exception {
    var hook = catalog.cell("lookup");
    var params = yaml("column: country\ntable:\n  FR: France\non-missing: error");
    var row = new HashMap<String, Object>(Map.of("country", "SENTINEL-1467"));
    assertThatThrownBy(() -> hook.apply(row, params, new MappingState()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("lookup: no match in column 'country' (on-missing: error)");
  }
}
