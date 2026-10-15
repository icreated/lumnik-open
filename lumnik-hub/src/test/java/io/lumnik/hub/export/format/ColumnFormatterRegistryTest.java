package io.lumnik.hub.export.format;

import io.lumnik.hub.export.ExportManifest;
import io.lumnik.hub.export.ExportManifestParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnFormatterRegistryTest {

    /** Every factory the hub ships — {@link #every_shipped_factory_is_listed_here} keeps it whole. */
    static final List<ColumnFormatterFactory> SHIPPED = List.of(
        new LookupFormatterFactory(), new FormatDateFormatterFactory(),
        new FormatNumberFormatterFactory(), new MaskFormatterFactory());

    private final ColumnFormatterRegistry registry = new ColumnFormatterRegistry(SHIPPED);
    private final ExportManifestParser parser = new ExportManifestParser();

    private ExportManifest manifest(String columns) {
        return parser.parse("""
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata: {name: partner-file}
            spec:
              scope: sales
              source: {entity: AcmeCustomer}
              columns: %s
              order: [code]
              format: {kind: csv}
              destination: {kind: s3, bucket: out, filename: "x-${timestamp}.csv"}
              mode: snapshot
            """.formatted(columns));
    }

    @Test
    void a_column_without_format_is_written_as_it_reads_and_steps_run_top_to_bottom() {
        List<ColumnFormatter> f = registry.compile(manifest("""
            [code, {name: status, format: [{lookup: {table: {ACTIVE: "12345"}}}, {mask: {keep-last: 2}}]}]"""));

        assertThat(f.get(0).format("C-1")).isEqualTo("C-1");
        assertThat(f.get(1).format("ACTIVE")).isEqualTo("***45");
    }

    @Test
    void an_unknown_formatter_is_refused_with_its_path_and_the_known_kinds() {
        assertThatThrownBy(() -> registry.compile(manifest("[code, {name: d, format: [{format-dat: {}}]}]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("spec.columns[1].format[0]: unknown formatter 'format-dat' — expected one of "
                + "[format-date, format-number, lookup, mask]");
    }

    @Test
    void a_bad_parameter_is_refused_with_its_path() {
        assertThatThrownBy(() -> registry.compile(manifest(
                "[code, {name: d, format: [{mask: {}}, {format-date: {}}]}]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("spec.columns[1].format[1].format-date: pattern is required");
    }

    @Test
    void a_run_failure_names_the_column_and_the_formatter_never_the_value() {
        ColumnFormatter f = registry.compile(manifest(
            "[code, {name: signup_date, as: DATE, format: [{format-date: {pattern: dd/MM/yyyy}}]}]")).get(1);

        assertThatThrownBy(() -> f.format("31/12/2019"))
            .isInstanceOf(ColumnFormatException.class)
            .hasMessageStartingWith("column 'signup_date', formatter 'format-date': not a date")
            .hasMessageNotContaining("31/12/2019");
    }

    /** The receipt's promise: same data, same bytes. Every shipped formatter, twice, same output. */
    @Test
    void every_shipped_formatter_writes_the_same_output_twice() {
        String[][] cases = {
            {"lookup", "{table: {A: B}, on-missing: Z}", "A"},
            {"format-date", "{pattern: dd/MM/yyyy HH:mm, zone: Europe/Paris}", "2026-03-29 01:30:00+00"},
            {"format-number", "{decimal-separator: \",\", scale: 2}", "0.125"},
            {"mask", "{keep-first: 1}", "secret"}};
        assertThat(Stream.of(cases).map(c -> c[0])).containsExactlyInAnyOrderElementsOf(
            SHIPPED.stream().map(ColumnFormatterFactory::kind).toList());
        for (String[] c : cases) {
            ColumnFormatterFactory factory = SHIPPED.stream().filter(s -> s.kind().equals(c[0])).findFirst().orElseThrow();
            String first = factory.compile(ColumnFormattersTest.params(c[1])).format(c[2]);
            String second = factory.compile(ColumnFormattersTest.params(c[1])).format(c[2]);
            assertThat(second).as(c[0]).isEqualTo(first);
        }
    }

    /** A failure reason names column and formatter, never the value: every shipped formatter,
     *  fed values it cannot write, keeps them out of its message (#1090's format-date leak). */
    @Test
    void no_shipped_formatter_quotes_the_value_it_could_not_write() {
        String[][] cases = {
            {"lookup", "{table: {A: B}, on-missing: error}", "SENTINEL-1090"},
            {"format-date", "{pattern: dd/MM/yyyy}", "SENTINEL-1090"},
            {"format-date", "{pattern: dd/MM/yyyy VV}", "2019-12-31"},
            {"format-date", "{pattern: HH:mm}", "2019-12-31"},
            {"format-number", "{}", "SENTINEL-1090"},
            {"format-number", "{scale: 2}", "1E+999999999"},
            {"mask", "{keep-last: 2}", "SENTINEL-1090"}};
        assertThat(Stream.of(cases).map(c -> c[0]).distinct()).containsExactlyInAnyOrderElementsOf(
            SHIPPED.stream().map(ColumnFormatterFactory::kind).toList());
        for (String[] c : cases) {
            ColumnFormatterFactory factory = SHIPPED.stream().filter(s -> s.kind().equals(c[0])).findFirst().orElseThrow();
            ColumnFormatter f = factory.compile(ColumnFormattersTest.params(c[1]));
            try {
                f.format(c[2]);
            } catch (ColumnFormatException e) {
                assertThat(e.getMessage()).as(c[0] + " " + c[1]).doesNotContain(c[2]);
            }
        }
    }

    /** The documented manifests' formatters compile, as the apply door compiles them. */
    @Test
    void every_documented_export_compiles() throws IOException {
        List<Path> examples;
        try (Stream<Path> files = Files.list(Path.of("..", "docs", "apps", "exports"))) {
            examples = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(examples).hasSizeGreaterThanOrEqualTo(4);
        for (Path example : examples) {
            ExportManifest m = parser.parse(Files.readString(example));
            assertThat(registry.compile(m)).as(example.toString()).hasSameSizeAs(m.columns());
        }
    }

    /** Inventory, anchored on the production sources: a new factory must join SHIPPED (and so
     *  the determinism check above) the day it is written. */
    @Test
    void every_shipped_factory_is_listed_here() throws IOException {
        Path dir = Path.of("src/main/java/io/lumnik/hub/export/format");
        List<String> onDisk;
        try (Stream<Path> files = Files.list(dir)) {
            onDisk = files.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith("FormatterFactory.java") && !n.equals("ColumnFormatterFactory.java"))
                .map(n -> n.substring(0, n.length() - ".java".length()))
                .toList();
        }
        assertThat(onDisk).isNotEmpty();
        assertThat(SHIPPED.stream().map(f -> f.getClass().getSimpleName()))
            .containsExactlyInAnyOrderElementsOf(onDisk);
    }
}
