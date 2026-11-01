package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** #1588: a text order is an instant with an explicit offset, read by PostgreSQL itself, or nothing. */
@QuarkusTest
class OrderColumnIT {

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    private Instant read(String text) throws Exception {
        String sql = "SELECT " + OrderColumn.TEXT.expression("v") + " FROM (SELECT ?::text AS v) t";
        try (Connection c = readonlyDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, text);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
                return t == null ? null : t.toInstant();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-26T10:00:00Z, 2026-10-26T10:00:00Z",
        "2026-10-26T10:00:00.123Z, 2026-10-26T10:00:00.123Z",
        "2026-10-26T11:00:00+01:00, 2026-10-26T10:00:00Z",
        "2026-10-26T12:00:00+03:00, 2026-10-26T09:00:00Z",
        "2026-10-26 10:00:00Z, 2026-10-26T10:00:00Z",
        "2026-10-26T10:00Z, 2026-10-26T10:00:00Z",
        "2026-10-15T08:00:00z, 2026-10-15T08:00:00Z"})
    void an_instant_with_an_offset_is_read_as_that_instant(String text, String instant) throws Exception {
        assertThat(read(text)).isEqualTo(Instant.parse(instant));
    }

    /** NaN and infinity sort greatest in PostgreSQL: as a position they would make a garbage version current. */
    @ParameterizedTest
    @CsvSource(quoteCharacter = '"', value = {
        "DECIMAL, 'NaN'::numeric", "DECIMAL, 'Infinity'::numeric", "DECIMAL, '-Infinity'::numeric",
        "FLOAT, 'NaN'::float8", "FLOAT, 'Infinity'::real",
        "DATE, 'infinity'::date", "TIMESTAMP, '-infinity'::timestamp", "TIMESTAMPTZ, 'infinity'::timestamptz"})
    void a_non_finite_value_is_no_position(OrderColumn kind, String literal) throws Exception {
        assertThat(typed(kind, literal)).isNull();
    }

    @ParameterizedTest
    @CsvSource(quoteCharacter = '"', value = {
        "INTEGER, 42::bigint", "DECIMAL, 18.7::numeric", "FLOAT, 1.5::float8", "DATE, '2026-10-26'::date",
        "TIMESTAMPTZ, '2026-10-26T10:00:00Z'::timestamptz"})
    void a_finite_value_is_its_own_position(OrderColumn kind, String literal) throws Exception {
        assertThat(typed(kind, literal)).isNotNull();
    }

    private Object typed(OrderColumn kind, String literal) throws Exception {
        String sql = "SELECT " + kind.expression("v") + " FROM (SELECT " + literal + " AS v) t";
        try (Connection c = readonlyDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-26T10:00:00", "2026-10-26", "01/07/2026", "", " ", "not-a-date",
        "2026-13-01T10:00:00Z", "2026-10-26T10:00:00+0100", "tomorrow"})
    void anything_else_orders_as_nothing(String text) throws Exception {
        assertThat(read(text)).isNull();
    }
}
