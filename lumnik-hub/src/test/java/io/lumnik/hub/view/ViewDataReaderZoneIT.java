package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.process.ProcessRuleEvaluator;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1304: on a hub whose session zone is not UTC, a {@link ViewDataReader} filter and a process pick
 * the same rows. The session zone is set with {@code SET LOCAL} on the test's transaction:
 * production enlists the readonly connection, so the reader's handle is the same session
 * ({@link ReadonlyTransactionsProfile}).
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class ViewDataReaderZoneIT {

    @Inject ViewDataReader reader;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

    private static final ResolvedView VIEW = new ResolvedView("Zone", "connector.t_view_zone", List.of(
        new ResolvedView.Column("code", "Code", "text"),
        new ResolvedView.Column("at", "At", "timestamp with time zone"),
        new ResolvedView.Column("day", "Day", "date"),
        new ResolvedView.Column("naive", "Naive", "text")));

    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector.t_view_zone");
            st.execute("CREATE TABLE connector.t_view_zone (code text, at timestamptz, day date, naive text)");
            // Each row sits within an hour of a UTC midnight, inside the band a +09:00 session moves.
            st.execute("INSERT INTO connector.t_view_zone VALUES "
                + "('A', '2025-12-31 23:30:00+00', '2026-01-01', '2026-01-01 00:30'), "
                + "('B', '2026-01-01 00:30:00+00', '2025-12-31', '2025-12-31 23:30')");
        }
    }

    @Test
    void aViewAndAProcessPickTheSameRows_whateverTheSessionZone() {
        QuarkusTransaction.requiringNew().run(() -> {
            setSessionZone("Asia/Tokyo");
            List<Map<String, Object>> all = reader.read(1L, VIEW, List.of()).rows();

            assertThat(agreed(all, "at>=2026-01-01")).containsExactly("B");
            assertThat(agreed(all, "day>2025-12-31T20:00:00Z")).containsExactly("A");
            assertThat(agreed(all, "naive<2026-01-01T00:00:00Z")).containsExactly("B");

            // #1310: = with a date on a date or timestamp column compares instants, on both paths;
            // on a text column it stays exact text.
            assertThat(agreed(all, "at=2025-12-31T23:30:00Z")).containsExactly("A");
            assertThat(agreed(all, "at=2026-01-01T09:30:00+09:00")).containsExactly("B");
            assertThat(agreed(all, "day=2026-01-01T00:00:00Z")).containsExactly("A");
            assertThat(agreed(all, "naive=2026-01-01T00:30:00Z")).isEmpty();
        });
    }

    /** The codes the view returns for {@code filter}, asserted equal to the codes the process matches. */
    private List<Object> agreed(List<Map<String, Object>> all, String filter) {
        ViewFilter f = ViewFilter.parse(filter);
        List<Object> view = reader.read(1L, VIEW, List.of(f)).rows().stream().map(r -> r.get("code")).toList();
        List<Object> process = all.stream()
            .filter(r -> ProcessRuleEvaluator.matches(r, List.of(f))).map(r -> r.get("code")).toList();
        assertThat(view).as("view vs process on %s", filter).isEqualTo(process);
        return view;
    }

    /** A second handle in the same transaction is the same session: the premise of this test. */
    private void setSessionZone(String zone) {
        try (Connection set = readonlyDs.getConnection();
             PreparedStatement ps = set.prepareStatement("SELECT set_config('TimeZone', ?, true)")) {
            ps.setString(1, zone);  // is_local = true: SET LOCAL, with the zone bound
            ps.execute();
            try (Connection again = readonlyDs.getConnection(); Statement show = again.createStatement();
                 ResultSet rs = show.executeQuery("SHOW TimeZone")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("the reader must see the session zone the test set").isEqualTo(zone);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
