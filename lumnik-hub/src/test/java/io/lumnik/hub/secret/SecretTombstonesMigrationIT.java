package io.lumnik.hub.secret;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V84 (#1569) replayed over a table that still holds tombstones — what an install that ran
 * {@code lm secret rm} before the delete removed the row is in when it upgrades. Flyway applied V84
 * at startup, over whatever this database held then, so this test seeds tombstones next to live rows,
 * then runs the SHIPPED script text, all in one transaction that is rolled back.
 */
@QuarkusTest
class SecretTombstonesMigrationIT {

    private static final String SCRIPT = "/db/migration/platform/V84__secret_value_gone_after_delete.sql";
    private static final long TENANT = 15691L;
    private static final long OTHER_TENANT = 15692L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void tombstonesAndTheirValuesGoAndLiveSecretsStay() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                seed(c, TENANT, "REUSED", true);         // removed once...
                seed(c, TENANT, "REUSED", false);        // ...then created again: the live one stays
                seed(c, TENANT, "GONE", true);
                seed(c, OTHER_TENANT, "THEIRS", true);   // every tenant's tombstones go
                seed(c, OTHER_TENANT, "KEPT", false);

                try (Statement st = c.createStatement()) {
                    st.execute(script());
                }

                assertThat(rows(c, "SELECT tenant_id || ':' || name || ':' || (deleted_at IS NULL) "
                        + "FROM platform.secret WHERE tenant_id IN (" + TENANT + ", " + OTHER_TENANT + ") ORDER BY 1"))
                    .containsExactly(TENANT + ":REUSED:true", OTHER_TENANT + ":KEPT:true");
                assertThat(rows(c, "SELECT count(*) FROM platform.secret WHERE deleted_at IS NOT NULL"))
                    .as("no tombstone is left anywhere, whichever tenant wrote it")
                    .containsExactly("0");
            } finally {
                c.rollback();
            }
        }
    }

    private static void seed(Connection c, long tenant, String name, boolean deleted) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO platform.secret (tenant_id, name, value_encrypted, deleted_at) "
              + "VALUES (?, ?, decode('00', 'hex'), CASE WHEN ? THEN now() END)")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setBoolean(3, deleted);
            ps.executeUpdate();
        }
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String script() throws Exception {
        try (InputStream in = SecretTombstonesMigrationIT.class.getResourceAsStream(SCRIPT)) {
            assertThat(in).as("shipped migration " + SCRIPT).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
