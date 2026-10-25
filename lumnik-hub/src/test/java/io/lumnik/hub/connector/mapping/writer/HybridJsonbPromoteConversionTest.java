package io.lumnik.hub.connector.mapping.writer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #1398: a promoted value reaches its declared type, or is named as a value that does not read as one. A JSON API
 * sends instants and often numbers as strings; bound as varchar they failed every run on a database type error.
 */
class HybridJsonbPromoteConversionTest {

    @Test
    void an_iso_string_is_a_timestamptz() {
        assertThat(HybridJsonbWriter.converted("2026-10-01T08:01:00.000Z", SqlType.TIMESTAMPTZ))
            .isEqualTo(OffsetDateTime.of(2026, 10, 1, 8, 1, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void numerals_become_numbers_true_and_false_booleans_and_anything_text() {
        assertThat(HybridJsonbWriter.converted("123", SqlType.BIGINT)).isEqualTo(123L);
        assertThat(HybridJsonbWriter.converted("7", SqlType.INTEGER)).isEqualTo(7);
        assertThat(HybridJsonbWriter.converted("10.50", SqlType.DECIMAL)).isEqualTo(new BigDecimal("10.50"));
        assertThat(HybridJsonbWriter.converted("true", SqlType.BOOLEAN)).isEqualTo(true);
        assertThat(HybridJsonbWriter.converted(12, SqlType.TEXT)).isEqualTo("12");
        assertThat(HybridJsonbWriter.converted(12, SqlType.BIGINT)).isEqualTo(12L);
    }

    @ParameterizedTest(name = "[{index}] {0} as {1}")
    @CsvSource({
        "not a date, TIMESTAMPTZ",
        "12.5,       BIGINT",
        "99999999999999999999, BIGINT",
        "3000000000, INTEGER",
        "ten,        DECIMAL",
        "yes,        BOOLEAN",
    })
    void a_value_that_does_not_read_as_its_type_is_unreadable(String raw, SqlType type) {
        assertThat(HybridJsonbWriter.converted(raw, type)).isSameAs(HybridJsonbWriter.UNREADABLE);
    }

    @Test
    void a_type_the_column_cannot_take_is_unreadable_and_a_date_reads_from_an_instant() {
        assertThat(HybridJsonbWriter.converted(1700000000, SqlType.TIMESTAMPTZ)).isSameAs(HybridJsonbWriter.UNREADABLE);
        assertThat(HybridJsonbWriter.converted(true, SqlType.DATE)).isSameAs(HybridJsonbWriter.UNREADABLE);
        assertThat(HybridJsonbWriter.converted("2026-10-01T00:00:00Z", SqlType.DATE))
            .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(HybridJsonbWriter.converted("1e999999999", SqlType.DECIMAL)).isSameAs(HybridJsonbWriter.UNREADABLE);
    }
}
