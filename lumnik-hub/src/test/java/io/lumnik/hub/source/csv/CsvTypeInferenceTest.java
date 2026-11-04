package io.lumnik.hub.source.csv;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-shot CSV promotion types a column from its sampled values. Two rules this table
 * pins, both broken by the first real dataset (146k Adempiere orders):
 * <ul>
 *   <li><b>Widening is a lattice, not equality</b>: a column holding {@code 0} on one row
 *   and {@code 59.4} on the next is DECIMAL — a bigint IS a decimal. It degraded to TEXT.</li>
 *   <li><b>Absence is not evidence</b>: an empty cell says nothing about a column's type.
 *   One blank in the sample dragged whole numeric columns to TEXT.</li>
 * </ul>
 * Dates staying TEXT is deliberate (the JSON round-trip constraint documented at the
 * inference site) and outside this table.
 */
class CsvTypeInferenceTest {

    @ParameterizedTest(name = "[{index}] {0} ∨ {1} = {2}")
    @CsvSource({
        "BIGINT,  BIGINT,  BIGINT",
        "DECIMAL, DECIMAL, DECIMAL",
        "BIGINT,  DECIMAL, DECIMAL",
        "DECIMAL, BIGINT,  DECIMAL",
        "BIGINT,  BOOLEAN, TEXT",
        "DECIMAL, TEXT,    TEXT",
        "BOOLEAN, BOOLEAN, BOOLEAN",
    })
    void widening_follows_the_numeric_lattice(SqlType a, SqlType b, SqlType expected) {
        assertThat(CsvIngestService.widerType(a, b)).isEqualTo(expected);
    }

    @Test
    void an_empty_cell_is_no_evidence_about_the_columns_type() {
        List<Map<String, Object>> sample = List.of(
            row("grandtotal", ""),          // blank first — the poison position
            row("grandtotal", "59.4"),
            row("grandtotal", "0")
        );
        assertThat(promotedType(sample, "grandtotal")).isEqualTo(SqlType.DECIMAL);
    }

    @Test
    void an_all_empty_column_is_still_promoted_as_text() {
        List<Map<String, Object>> sample = List.of(row("note", ""), row("note", null));
        assertThat(promotedType(sample, "note")).isEqualTo(SqlType.TEXT);
    }

    @Test
    void mixed_integer_and_decimal_rows_promote_as_decimal() {
        List<Map<String, Object>> sample = List.of(
            row("totallines", "0"),
            row("totallines", "1250.5"),
            row("totallines", "-3")
        );
        assertThat(promotedType(sample, "totallines")).isEqualTo(SqlType.DECIMAL);
    }

    /** #1622 (Copilot on #1625): under tr, "ID" lowered to "ıd" and slipped past the reserved columns. */
    @Test
    void a_reserved_header_stays_reserved_in_a_turkish_locale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(new CsvIngestService().inferSchema(List.of(Map.of("ID", "7", "amount", "12")))
                .stream().map(p -> p.name()).toList()).containsExactly("amount");
        } finally {
            Locale.setDefault(previous);
        }
    }

    /** #1783: every column the hybrid table already has is left out of the promoted ones, not only the first seven. */
    @Test
    void a_header_named_as_a_column_of_the_hybrid_table_is_not_promoted() {
        for (String reserved : new TreeSet<>(HybridJsonbSpec.RESERVED_COLUMNS)) {
            assertThat(new CsvIngestService().inferSchema(List.of(Map.of(reserved, "7", "amount", "12")))
                .stream().map(p -> p.name()).toList())
                .as("%s stays out of the promoted columns", reserved).containsExactly("amount");
        }
    }

    private static Map<String, Object> row(String col, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(col, v);
        return m;
    }

    private static SqlType promotedType(List<Map<String, Object>> sample, String col) {
        return new CsvIngestService().inferSchema(sample).stream()
            .filter(p -> p.name().equals(col)).findFirst().orElseThrow().type();
    }
}
