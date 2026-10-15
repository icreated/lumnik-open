package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1092 against PostgreSQL itself: a session in a zone other than UTC — what a hub JVM outside UTC
 * gets from pgJDBC — must not move a parse-date time. The raw LocalDateTime is the control: it does
 * move, so this test can see the defect even on a UTC machine.
 */
@QuarkusTest
class TimestampBindingIT {

    @Inject @DataSource("admin") AgroalDataSource ds;

    @Test
    void a_time_without_an_offset_lands_as_utc_whatever_the_session_zone() throws Exception {
        LocalDateTime written = LocalDateTime.parse("2026-08-02T23:30:00");
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL TIME ZONE 'America/New_York'");
                st.execute("CREATE TEMP TABLE t1092 (k text, v timestamptz, t text) ON COMMIT DROP");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t1092 VALUES (?, ?, ?)")) {
                ps.setString(1, "raw");
                ps.setObject(2, written);
                ps.setObject(3, written);
                ps.execute();
                ps.setString(1, "bound");
                ps.setObject(2, DynamicTableWriter.bindable(written, "TIMESTAMPTZ"));
                ps.setObject(3, DynamicTableWriter.bindable(written, "TEXT"));
                ps.execute();
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT k, to_char(v AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'), t FROM t1092 ORDER BY k")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("bound");
                assertThat(rs.getString(2)).isEqualTo("2026-08-02 23:30");
                assertThat(rs.getString(3)).as("a text column keeps the wall-clock text, whatever the session zone")
                    .isEqualTo("2026-08-02 23:30:00");
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("raw");
                assertThat(rs.getString(2)).as("the control: a bare LocalDateTime follows the session zone")
                    .isEqualTo("2026-08-03 03:30");
            }
            c.rollback();
        }
    }
}
