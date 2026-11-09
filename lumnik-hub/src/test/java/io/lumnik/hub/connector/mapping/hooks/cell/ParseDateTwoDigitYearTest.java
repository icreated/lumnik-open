package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** parse-date's {@code century-from}: where a two-digit year (yy) starts its 100-year window (#1857). */
class ParseDateTwoDigitYearTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final ParseDateHook hook = new ParseDateHook();

    private Object parse(String params, String value) throws Exception {
        JsonNode p = YAML.readTree(params);
        hook.validateParams(p);
        Map<String, Object> row = new HashMap<>(Map.of("d", value));
        hook.apply(row, p, new MappingState());
        return row.get("d");
    }

    @ParameterizedTest
    @CsvSource({"12/03/98,1998-12-03", "01/15/05,2005-01-15", "11/05/26,2026-11-05", "01/01/49,2049-01-01", "01/01/50,1950-01-01"})
    void yy_reads_inside_the_declared_window(String in, String out) throws Exception {
        assertThat(parse("column: d\nformats: ['MM/dd/yy']\ncentury-from: 1950", in)).isEqualTo(LocalDate.parse(out));
    }

    @Test
    void another_window_moves_the_pivot() throws Exception {
        assertThat(parse("column: d\nformats: ['dd/MM/yy']\ncentury-from: 1900", "03/12/98")).isEqualTo(LocalDate.of(1998, 12, 3));
        assertThat(parse("column: d\nformats: ['dd/MM/yy']\ncentury-from: 1900", "03/12/05")).isEqualTo(LocalDate.of(1905, 12, 3));
    }

    @Test
    void yy_with_a_time_and_a_following_literal_keeps_its_window() throws Exception {
        assertThat(parse("column: d\nformats: [\"dd/MM/yy 'at' HH:mm\"]\ncentury-from: 1950", "03/12/98 at 10:30"))
            .isEqualTo(LocalDateTime.of(1998, 12, 3, 10, 30));
    }

    @Test
    void a_yy_between_quotes_is_a_literal_not_a_year() throws Exception {
        assertThat(parse("column: d\nformats: [\"yyyy-MM-dd 'yy'\"]", "2026-11-05 yy")).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void a_four_digit_year_needs_no_window() throws Exception {
        assertThat(parse("column: d\nformats: ['dd/MM/yyyy']", "03/12/1998")).isEqualTo(LocalDate.of(1998, 12, 3));
    }

    @Test
    void yy_without_a_window_is_refused_naming_the_parameter() {
        assertThatThrownBy(() -> parse("column: d\nformats: ['MM/dd/yy']", "12/03/98"))
            .isInstanceOf(MappingValidationException.class)
            .hasMessage("parse-date hook: 'century-from' is required when a format reads a two-digit year (yy)");
    }

    @Test
    void a_window_with_no_yy_format_is_refused() {
        assertThatThrownBy(() -> parse("column: d\nformats: ['dd/MM/yyyy']\ncentury-from: 1950", "03/12/1998"))
            .isInstanceOf(MappingValidationException.class)
            .hasMessage("parse-date hook: 'century-from' has no effect: no format reads a two-digit year (yy)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'1950'", "19.5", "0", "-5", "9901", "[1950]"})
    void a_window_that_is_not_a_year_is_refused(String bad) {
        assertThatThrownBy(() -> parse("column: d\nformats: ['MM/dd/yy']\ncentury-from: " + bad, "12/03/98"))
            .isInstanceOf(MappingValidationException.class)
            .hasMessageStartingWith("parse-date hook: 'century-from' must be a whole year between 1 and 9900");
    }
}
