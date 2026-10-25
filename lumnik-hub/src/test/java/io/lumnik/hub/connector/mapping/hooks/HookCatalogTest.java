package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookDescriptor.Tier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class HookCatalogTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject HookCatalog catalog;

  @Test
  void empty_catalog_returns_empty_lists() {
    assertThat(catalog.allDatasetHooks()).isNotNull();
    assertThat(catalog.allRowHooks()).isNotNull();
    assertThat(catalog.allCellHooks()).isNotNull();
  }

  /**
   * #1487: a key the hook does not read is refused by name, with the keys it does read — a misspelt knob used to be
   * dropped and the hook ran on its default. One per tier and per kind of hook.
   */
  @ParameterizedTest(name = "{0} {1}: {3}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "CELL|upper|{\"colums\": [\"a\"]}|colums|columns",
      "CELL|upper|{\"column\": \"a\"}|column|columns",
      "CELL|parse-date|{\"column\": \"d\", \"format\": [\"yyyy\"]}|format|column, formats, zone",
      "CELL|lookup|{\"column\": \"c\", \"on_missing\": \"keep\"}|on_missing|column, on-missing, table",
      "CELL|kotlin|{\"script\": \"classpath:mapping/uppercase-phone.kts\", \"contxt\": {}}|contxt|context, script",
      "ROW|aggregator|{\"group-by\": [\"k\"], \"emit-on-change\": \"k\"}|group-by|emit-on-change, keep-first, merge",
      "ROW|aggregator|{\"emit-on-change\": \"k\", \"merge\": {\"t\": {\"colect\": \"v\"}}}|merge.t.colect"
          + "|collect, dedup, into, sep",
      "ROW|map-columns|{\"renmae\": {\"a\": \"b\"}}|renmae|drop, rename",
      "ROW|split-row|{\"by\": \"c\", \"separator\": \";\"}|separator|by, escape, explode, position, sep",
      "DATASET|sort-by|{\"columns\": [\"a\"], \"max_rows\": 10}|max_rows|columns, max-rows",
      "DATASET|filter-where|{\"column\": \"c\", \"equal\": \"x\"}|equal|column, equals, in, regex"})
  void a_key_the_hook_does_not_read_is_refused_by_name(Tier tier, String hook, String params, String key,
                                                      String accepted) throws Exception {
    assertThatThrownBy(() -> catalog.validate(tier, hook, JSON.readTree(params)))
        .isInstanceOf(UnknownHookParameterException.class)
        .hasMessage(hook + " hook: unknown parameter '" + key + "' — accepted: " + accepted)
        .extracting(e -> ((UnknownHookParameterException) e).key()).isEqualTo(key);
  }

  @Test
  void a_hook_that_reads_no_key_says_so() throws Exception {
    assertThatThrownBy(() -> catalog.validate(Tier.DATASET, "skip-empty-rows", JSON.readTree("{\"strict\": true}")))
        .isInstanceOf(UnknownHookParameterException.class)
        .hasMessage("skip-empty-rows hook: unknown parameter 'strict' — it reads none");
  }

  /**
   * #1492: a parameter block written as a scalar or a list has no keys to refuse — it validated as "no parameters" and
   * the hook ran on its defaults (or on every column). Refused by shape, never by value: each shape, in each tier.
   */
  @ParameterizedTest(name = "{0} {1}: {2}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "CELL|upper|\"x\"|string",
      "CELL|truncate|5|number",
      "CELL|upper|[\"name\"]|array",
      "ROW|map-columns|\"a\"|string",
      "ROW|validate-row|[\"email\"]|array",
      "DATASET|skip-empty-rows|true|boolean",
      "DATASET|enrich-context|[\"source\"]|array",
      "ROW|defaults|\"FR\"|string"})
  void a_parameter_block_that_is_not_a_map_is_refused(Tier tier, String hook, String params, String shape) {
    assertThatThrownBy(() -> catalog.validate(tier, hook, JSON.readTree(params)))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage(hook + " hook: its parameters must be a map of name to value (got " + shape + ")");
  }

  /** The keys a hook reads, a bare or empty params block, and the hooks whose keys are the columns they fill. */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "CELL|clean|{\"columns\": [\"*\"], \"strip\": true, \"remove-control-chars\": true, \"normalize-unicode\": \"NFC\"}",
      "CELL|to-uuid|{\"column\": \"id\", \"on-invalid\": \"keep\", \"generate\": true, \"generate-from\": \"code\"}",
      "CELL|parse-date|null",
      "DATASET|skip-empty-rows|null",
      "DATASET|skip-empty-rows|{}",
      "DATASET|enrich-context|{\"source\": \"erp\", \"anything\": \"goes\"}",
      "ROW|defaults|{\"country\": \"FR\", \"currency\": \"EUR\"}",
      "ROW|aggregator|{\"emit-on-change\": \"k\", \"merge\": {\"t\": {\"collect\": \"v\", \"into\": \"csv\", "
          + "\"dedup\": true, \"sep\": \";\"}}}"})
  void the_keys_a_hook_reads_are_accepted(Tier tier, String hook, String params) {
    assertThatCode(() -> catalog.validate(tier, hook, JSON.readTree(params))).doesNotThrowAnyException();
  }

  /**
   * #1487 inventory: every registered hook declares the keys it reads, pinned here. A new hook is listed here, a key
   * added to a hook is added here — read from its code ({@code params.path/get/has}, {@code CleanHook.columnList}
   * reads {@code columns}), never from its docs.
   */
  @Test
  void every_registered_hook_declares_the_keys_it_reads() {
    Set<String> columns = Set.of("columns");
    Map<String, Set<String>> pinned = new TreeMap<>(Map.ofEntries(
        // dataset
        Map.entry("dataset/enrich-context", HookCatalog.KEYS_ARE_COLUMNS),
        Map.entry("dataset/filter-where", Set.of("column", "equals", "regex", "in")),
        Map.entry("dataset/require-headers", Set.of("columns", "any-of")),
        Map.entry("dataset/skip-empty-rows", Set.of()),
        Map.entry("dataset/sort-by", Set.of("columns", "max-rows")),
        Map.entry("dataset/test-add-marker", Set.of()),
        // row
        Map.entry("row/aggregator", Set.of("emit-on-change", "keep-first", "merge")),
        Map.entry("row/coalesce", Set.of("column", "from")),
        Map.entry("row/defaults", HookCatalog.KEYS_ARE_COLUMNS),
        Map.entry("row/map-columns", Set.of("rename", "drop")),
        Map.entry("row/split-row", Set.of("explode", "by", "sep", "escape", "position")),
        Map.entry("row/test-silent-drop", Set.of()),
        Map.entry("row/validate-row", Set.of("required")),
        // cell
        Map.entry("cell/clean", Set.of("columns", "strip", "remove-control-chars", "normalize-unicode")),
        Map.entry("cell/decode-html-entities", columns),
        Map.entry("cell/default", Set.of("column", "value")),
        Map.entry("cell/fill-down", Set.of("columns", "reset-when")),
        Map.entry("cell/fix-mojibake", columns),
        Map.entry("cell/kotlin", Set.of("script", "context")),
        Map.entry("cell/lookup", Set.of("column", "table", "on-missing")),
        Map.entry("cell/lower", columns),
        Map.entry("cell/mask", Set.of("column", "keep-first", "keep-last", "mask-char")),
        Map.entry("cell/parse-date", Set.of("column", "formats", "zone")),
        Map.entry("cell/parse-email", Set.of("column", "lower")),
        Map.entry("cell/parse-number", Set.of("column", "integer", "decimal-separator", "negatives-in-parens")),
        Map.entry("cell/parse-phone", Set.of("column", "default-region")),
        Map.entry("cell/regex-extract", Set.of("column", "pattern", "groups", "required")),
        Map.entry("cell/regex-replace", Set.of("column", "pattern", "replacement")),
        Map.entry("cell/remove-diacritics", columns),
        Map.entry("cell/replace-smart-quotes", columns),
        Map.entry("cell/split", Set.of("column", "by", "into", "into-array")),
        Map.entry("cell/strip-invisible", columns),
        Map.entry("cell/test-carry", Set.of("column")),
        Map.entry("cell/titlecase", columns),
        Map.entry("cell/to-uuid", Set.of("column", "on-invalid", "generate", "generate-from")),
        Map.entry("cell/truncate", Set.of("column", "length")),
        Map.entry("cell/upper", columns)));

    Map<String, Set<String>> declared = new TreeMap<>();
    catalog.names(Tier.DATASET).forEach(n -> declared.put("dataset/" + n, catalog.dataset(n).params()));
    catalog.names(Tier.ROW).forEach(n -> declared.put("row/" + n, catalog.row(n).params()));
    catalog.names(Tier.CELL).forEach(n -> declared.put("cell/" + n, catalog.cell(n).params()));

    assertThat(declared).isEqualTo(pinned);
  }

  /**
   * The unknown key speaks before the hook's own checks: a misspelt REQUIRED key is the typo to name, not the key it
   * replaced reported missing (aggregator would say 'emit-on-change' is required).
   */
  @Test
  void a_misspelt_required_key_is_named_as_unknown() {
    assertThatThrownBy(() -> catalog.validate(Tier.ROW, "aggregator", JSON.readTree("{\"emit_on_change\": \"key\"}")))
        .isInstanceOfSatisfying(UnknownHookParameterException.class,
            e -> assertThat(e.key()).isEqualTo("emit_on_change"))
        .hasMessage("aggregator hook: unknown parameter 'emit_on_change' — accepted: emit-on-change, keep-first, merge");
  }

  /**
   * A hook whose keys are the columns it fills may fill a column named {@code column} or {@code columns}: those are
   * its values, not the knobs the catalog checks on the hooks that declare them.
   */
  @Test
  void a_column_map_hook_fills_columns_named_column_and_columns() throws Exception {
    catalog.validate(Tier.ROW, "defaults", JSON.readTree("{\"columns\": 0, \"column\": \"\"}"));
    catalog.validate(Tier.DATASET, "enrich-context", JSON.readTree("{\"columns\": \"x\", \"column\": 1}"));
  }

  /** So does a key the hook knows not to read: kotlin's `column` is named as a parameter a script does not have. */
  @Test
  void a_key_a_hook_does_not_read_is_named_before_its_own_checks() {
    assertThatThrownBy(() -> catalog.validate(Tier.CELL, "kotlin", JSON.readTree("{\"script\": \"classpath:x.kts\", "
        + "\"column\": \"c\"}")))
        .isInstanceOfSatisfying(UnknownHookParameterException.class, e -> assertThat(e.key()).isEqualTo("column"));
  }

  /**
   * #1500 inventory: every registered hook declares how it changes a row's columns, pinned here — read from its code
   * (what {@code apply} reads, removes and puts), never from its docs. A new hook is listed here.
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "DATASET|enrich-context|{\"source\":\"erp\"}|[add source] unknown=false",
      "DATASET|filter-where|{\"column\":\"c\",\"equals\":\"x\"}|[read column:c] unknown=false",
      "DATASET|require-headers|{\"columns\":[\"a\"],\"any-of\":[\"b\",\"c\"]}|[read columns[0]:a, any any-of:b,c] unknown=false",
      "DATASET|skip-empty-rows|{}|[] unknown=false",
      "DATASET|sort-by|{\"columns\":[\"a\",\"b\"]}|[read columns[0]:a, read columns[1]:b] unknown=false",
      "DATASET|test-add-marker|{}|[add marker] unknown=false",
      "ROW|aggregator|{\"emit-on-change\":\"k\",\"keep-first\":[\"f\"],\"merge\":{\"t\":{\"collect\":\"v\"}}}|[read emit-on-change:k, read keep-first[0]:f, read merge.t.collect:v, add t] unknown=false",
      "ROW|coalesce|{\"column\":\"c\",\"from\":[\"a\",\"b\"]}|[any from:a,b, add c] unknown=false",
      "ROW|defaults|{\"country\":\"FR\"}|[add country] unknown=false",
      "ROW|map-columns|{\"rename\":{\"old\":\"new\"},\"drop\":[\"x\"]}|[read rename.old:old, remove old, add new, read drop[0]:x, remove x] unknown=false",
      "ROW|split-row|{\"by\":\"c\",\"sep\":\";\",\"position\":\"n\"}|[read by:c, add n] unknown=false",
      "ROW|test-silent-drop|{}|[] unknown=false",
      "ROW|validate-row|{\"required\":[\"a\"]}|[read required[0]:a] unknown=false",
      "CELL|default|{\"column\":\"c\",\"value\":\"v\"}|[add c] unknown=false",
      "CELL|fill-down|{\"columns\":[\"c\"],\"reset-when\":{\"column\":\"t\",\"in\":[\"H\"]}}|[read columns[0]:c, read reset-when.column:t] unknown=false",
      "CELL|kotlin|{\"script\":\"classpath:x.kts\"}|[] unknown=true",
      "CELL|regex-extract|{\"column\":\"c\",\"pattern\":\"(.)\",\"groups\":{\"1\":\"g\"}}|[read column:c, add g] unknown=false",
      "CELL|split|{\"column\":\"c\",\"by\":\";\",\"into\":[\"a\",\"b\"]}|[read column:c, add a, add b] unknown=false",
      "CELL|test-carry|{\"column\":\"c\"}|[read column:c, add seq, add prev] unknown=false",
      "CELL|to-uuid|{\"column\":\"id\",\"generate-from\":\"code\"}|[read generate-from:code, add id] unknown=false",
      "CELL|to-uuid|{\"column\":\"id\",\"generate\":true}|[add id] unknown=false",
      "CELL|to-uuid|{\"column\":\"id\",\"on-invalid\":\"null\"}|[read column:id] unknown=false",
      "CELL|clean|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|decode-html-entities|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|fix-mojibake|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|lower|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|remove-diacritics|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|replace-smart-quotes|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|strip-invisible|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|titlecase|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|upper|{\"columns\":[\"c\"]}|[read columns[0]:c] unknown=false",
      "CELL|lookup|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|mask|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|parse-date|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|parse-email|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|parse-number|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|parse-phone|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|regex-replace|{\"column\":\"c\"}|[read column:c] unknown=false",
      "CELL|truncate|{\"column\":\"c\"}|[read column:c] unknown=false"})
  void every_registered_hook_declares_its_column_effect(Tier tier, String hook, String params, String expected)
      throws Exception {
    JsonNode p = JSON.readTree(params);
    ColumnEffect e = catalog.columns(tier, hook, p);
    String got = e.steps().stream().map(step -> switch (step) {
      case ColumnEffect.Read r -> "read " + r.path() + ":" + r.column();
      case ColumnEffect.AnyOf a -> "any " + a.path() + ":" + String.join(",", a.columns());
      case ColumnEffect.Remove r -> "remove " + r.column();
      case ColumnEffect.Add a -> "add " + a.column();
    }).toList() + " unknown=" + e.unknownAfter();
    assertThat(got).isEqualTo(expected);
  }

  /** The table above names every registered hook: a new one cannot ship without its declaration pinned. */
  @Test
  void the_column_effect_inventory_names_every_hook() throws Exception {
    Set<String> pinned = new TreeSet<>();
    var source = getClass().getDeclaredMethod("every_registered_hook_declares_its_column_effect", Tier.class,
        String.class, String.class, String.class).getAnnotation(CsvSource.class);
    for (String row : source.value()) {
      String[] cols = row.split("\\|");
      pinned.add(cols[0].toLowerCase(Locale.ROOT) + "/" + cols[1]);
    }
    Set<String> registered = new TreeSet<>();
    catalog.names(Tier.DATASET).forEach(n -> registered.add("dataset/" + n));
    catalog.names(Tier.ROW).forEach(n -> registered.add("row/" + n));
    catalog.names(Tier.CELL).forEach(n -> registered.add("cell/" + n));
    assertThat(pinned).isEqualTo(registered);
  }
}
