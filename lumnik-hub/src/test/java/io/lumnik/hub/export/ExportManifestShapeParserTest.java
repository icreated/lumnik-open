package io.lumnik.hub.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec.columns in its mapping form ({name, as, format}) and spec.filter — #1090. */
class ExportManifestShapeParserTest {

    private final ExportManifestParser parser = new ExportManifestParser();

    private static String manifest(String columnsAndFilter) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata:
              name: partner-file
            spec:
              scope: sales
              source:
                entity: AcmeCustomer
            %s
              order: [code]
              format:
                kind: csv
              destination:
                kind: s3
                bucket: out
                filename: customers-${date}.csv
              mode: snapshot
            """.formatted(columnsAndFilter.indent(2).stripTrailing());
    }

    private static final String SHAPED = """
        columns:
          - code
          - name: status
            as: STATUS
            format:
              - lookup: { table: { ACTIVE: A }, on-missing: error }
              - mask: { keep-last: 1 }
          - name: signup_date
            format:
              - format-date: { pattern: dd/MM/yyyy }
        filter:
          - { column: status, in: [ACTIVE, PENDING] }
          - { column: country, equals: FR }
        """;

    @Test
    void bare_names_keep_their_meaning_header_is_the_name_and_nothing_formats() {
        ExportManifest m = parser.parse(manifest("columns: [code, name]"));

        assertThat(m.columns()).containsExactly("code", "name");
        assertThat(m.output()).extracting(ExportManifest.Column::header).containsExactly("code", "name");
        assertThat(m.output()).allSatisfy(c -> assertThat(c.format()).isEmpty());
        assertThat(m.filter()).isEmpty();
    }

    @Test
    void the_mapping_form_carries_header_and_steps_in_declared_order() {
        ExportManifest m = parser.parse(manifest(SHAPED));

        assertThat(m.columns()).containsExactly("code", "status", "signup_date");
        assertThat(m.output()).extracting(ExportManifest.Column::header)
            .containsExactly("code", "STATUS", "signup_date");
        assertThat(m.output().get(1).format()).extracting(ExportManifest.FormatStep::kind)
            .containsExactly("lookup", "mask");
        assertThat(m.output().get(1).format().get(1).params().path("keep-last").asInt()).isEqualTo(1);
        assertThat(m.filter()).containsExactly(
            new ExportManifest.Filter("status", List.of("ACTIVE", "PENDING")),
            new ExportManifest.Filter("country", List.of("FR")));
    }

    @Test
    void unknown_keys_in_a_column_or_a_filter_entry_are_reported_by_path() {
        JsonNode root = parser.readTree(manifest("""
            columns:
              - code
              - { name: status, alias: S }
            filter:
              - { column: status, equals: A, like: B }
            """));

        assertThat(parser.unknownKeys(root)).containsExactly("spec.columns[1].alias", "spec.filter[0].like");
    }

    @Test
    void a_step_without_params_compiles_to_empty_params() {
        ExportManifest m = parser.parse(manifest("""
            columns:
              - name: code
                format:
                  - mask:
            """));

        assertThat(m.output().get(0).format().get(0).params().isObject()).isTrue();
        assertThat(m.output().get(0).format().get(0).params().isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        a number is neither a name nor a mapping | 'columns: [code, 7]'                                       | spec.columns[1] must be a column name or a mapping {name, as, format}
        a mapping needs a name                   | 'columns: [code, {as: X}]'                                  | spec.columns[1].name is required
        a blank header                           | 'columns: [code, {name: n, as: " "}]'                       | spec.columns[1].as must not be blank
        a header with a line break               | 'columns: [code, {name: n, as: "A\\nB"}]'                   | spec.columns[1].as must not contain a line break
        two columns under one header             | 'columns: [code, {name: n, as: code}]'                      | spec.columns[1]: duplicate header
        a bare name repeating an earlier header  | 'columns: [{name: id, as: code}, code]'                     | spec.columns[1]: duplicate header
        the same column twice                    | 'columns: [code, {name: code, as: C2}]'                     | spec.columns: duplicate column
        an internal column in mapping form       | 'columns: [code, {name: _handle}]'                          | is an internal column and cannot be exported
        format is not a list                     | 'columns: [code, {name: n, format: {mask: {}}}]'            | spec.columns[1].format must be a list
        an empty format list                     | 'columns: [code, {name: n, format: []}]'                    | spec.columns[1].format must declare at least one formatter
        a step with two kinds                    | 'columns: [code, {name: n, format: [{mask: {}, lookup: {}}]}]' | spec.columns[1].format[0] must name exactly one formatter
        a step whose params are not a mapping    | 'columns: [code, {name: n, format: [{mask: 3}]}]'           | spec.columns[1].format[0].mask must be a mapping
        """)
    void a_malformed_column_is_refused_naming_its_path(String why, String columns, String message) {
        assertThatThrownBy(() -> parser.parse(manifest(columns)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(message);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        filter is not a list           | 'filter: {column: status}'                       | spec.filter must be a list
        an empty filter                | 'filter: []'                                     | spec.filter must declare at least one entry
        no column                      | 'filter: [{equals: A}]'                          | spec.filter[0].column is required
        an internal column             | 'filter: [{column: _conflicts, equals: A}]'      | is an internal column and cannot be filtered on
        neither equals nor in          | 'filter: [{column: status}]'                     | spec.filter[0] must declare exactly one of equals or in
        both equals and in             | 'filter: [{column: status, equals: A, in: [B]}]' | spec.filter[0] must declare exactly one of equals or in
        a number to compare            | 'filter: [{column: year, equals: 2024}]'         | spec.filter[0].equals must be a string
        an empty in                    | 'filter: [{column: status, in: []}]'             | spec.filter[0].in must list at least one value
        a number inside in             | 'filter: [{column: status, in: [A, 1]}]'         | spec.filter[0].in must contain only strings
        """)
    void a_malformed_filter_is_refused_naming_its_path(String why, String filter, String message) {
        assertThatThrownBy(() -> parser.parse(manifest("columns: [code, status]\n" + filter)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(message);
    }
}
