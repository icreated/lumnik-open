package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2010: no default privilege covers a hybrid table, so its grants are the only way lumnik_readonly (ask-to-SQL) reads
 * it. Lost, they come back at the first write a hub process makes to the table. Under the real roles: in a plain
 * {@code %test} database the writer's role owns everything, and nothing it revokes can be seen missing.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class HybridTableGrantsRlsIT {

    @Inject HybridJsonbWriter writer;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;   // lumnik_admin, the owner

    @Test
    void aLostTableGrantComesBack() {
        String bare = freshTable();
        Jdbc.update(adminDs, "revoke", "REVOKE SELECT ON ext." + bare + " FROM lumnik_readonly", ps -> {});
        writer.clearSchemaCache();

        write(bare);

        assertThat(Jdbc.queryOne(adminDs, "granted", "SELECT has_table_privilege('lumnik_readonly', ?, 'SELECT')",
            ps -> ps.setString(1, "ext." + bare), rs -> rs.getBoolean(1))).contains(true);
    }

    @Test
    void aLostSchemaUsageComesBack() {
        Jdbc.update(adminDs, "revoke", "REVOKE USAGE ON SCHEMA ext FROM lumnik_readonly", ps -> {});
        try {
            freshTable();

            assertThat(Jdbc.queryOne(adminDs, "usage", "SELECT has_schema_privilege('lumnik_readonly', 'ext', 'USAGE')",
                ps -> {}, rs -> rs.getBoolean(1))).contains(true);
        } finally {
            Jdbc.update(adminDs, "regrant", "GRANT USAGE ON SCHEMA ext TO lumnik_readonly", ps -> {});
        }
    }

    /**
     * The record says which tables the admin writer may alter, grant and drop. The request path's role reads it (the
     * endpoint purge) and writes nothing in it: a row it could add would hand the writer any table under connector.
     */
    @Test
    void theAppRoleReadsTheRecordAndWritesNothingInIt() {
        assertThat(Jdbc.queryOne(adminDs, "privileges", "SELECT has_table_privilege('lumnik_app', "
                + "'connector.hybrid_table', 'SELECT') AND NOT has_table_privilege('lumnik_app', 'connector.hybrid_table', "
                + "'INSERT') AND NOT has_table_privilege('lumnik_app', 'connector.hybrid_table', 'UPDATE') AND NOT "
                + "has_table_privilege('lumnik_app', 'connector.hybrid_table', 'DELETE') AND NOT "
                + "has_table_privilege('lumnik_app', 'connector.hybrid_table', 'TRUNCATE')", ps -> {}, rs -> rs.getBoolean(1)))
            .contains(true);
    }

    /** A healthy table is left as it is: even a grant it already has rewrites its catalog row (cached plans go). */
    @Test
    void aHealthyTableIsLeftAsItIs() {
        String bare = freshTable();
        String before = catalogRowVersion(bare);
        writer.clearSchemaCache();

        write(bare);

        assertThat(catalogRowVersion(bare)).isEqualTo(before);
    }

    private String freshTable() {
        String bare = "grants_" + System.nanoTime();
        write(bare);
        return bare;
    }

    private void write(String bare) {
        runInTxAsSystem(utx, () -> writer.write(new HybridJsonbSpec("ext." + bare, "$.id", List.of()),
            List.of(Map.of("id", "r_1")), 1L, UUID.randomUUID(), UUID.randomUUID()));
    }

    private String catalogRowVersion(String bare) {
        return Jdbc.queryOne(adminDs, "xmin", "SELECT xmin::text FROM pg_class WHERE oid = to_regclass(?)",
            ps -> ps.setString(1, "ext." + bare), rs -> rs.getString(1)).orElseThrow();
    }
}
