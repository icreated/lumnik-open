package io.lumnik.hub.ask;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #2008: what the role running model-written SQL may read, on a catalog every migration has run on. It reads data —
 * the dynamic tables and the entity views, each granted when the hub creates it — and nothing a migration creates,
 * except what is named below. A migration that makes a new platform table readable fails here, not in production.
 *
 * <p>The container is shared with every IT of the profile, so tables they seed are in the catalog too: what is
 * accepted is the registry, the hub's names for what ingestion and the entity compiler create ({@code connector.t_*},
 * as DynamicTableRegistry.isHubDynamicTable draws it, schema {@code ext}, which only the hybrid writer fills, and
 * {@code connector.v_*}), and the
 * allowlist. A schema-qualified name, not only the registry: some ITs drop a registry row and keep its table. Every platform table V92 and V93 revoke
 * fails here by name if it becomes readable again. ReadonlyHardening keeps its own narrow list for a production that
 * drifts; this test guards the code.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ReadonlyGrantsInventoryRlsIT {

    /** core.entity_identity: the entity views are security_invoker and join it (V50). Nothing else is needed. */
    static final List<String> ALLOWLIST = List.of("core.entity_identity");

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void the_readonly_role_reads_data_and_the_allowlist_only() {
        List<String> readable = Jdbc.query(adminDs, "inventory.readable", """
            SELECT n.nspname || '.' || c.relname FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
              AND pg_catalog.has_any_column_privilege('lumnik_readonly', c.oid, 'SELECT')
              AND NOT (n.nspname = 'connector' AND c.relname ~ '^(t|v)_')
              AND n.nspname <> 'ext'
              AND NOT EXISTS (SELECT 1 FROM connector.dynamic_table d
                              WHERE d.table_schema = n.nspname AND d.table_name = c.relname)
            ORDER BY 1""", ps -> {}, rs -> rs.getString(1));

        assertThat(readable).as("readable by lumnik_readonly without being data").containsExactlyElementsOf(ALLOWLIST);
    }

    @Test
    void no_migration_grants_the_readonly_role_by_default() {
        List<String> defaults = Jdbc.query(adminDs, "inventory.defaults",
            "SELECT coalesce(n.nspname, '(all)') FROM pg_catalog.pg_default_acl d "
                + "LEFT JOIN pg_catalog.pg_namespace n ON n.oid = d.defaclnamespace "
                // the role by name, or PUBLIC (an aclitem with no grantee: "=r/…"), which hands it the same tables
                + "WHERE d.defaclacl::text ~ '(^|[{,])(lumnik_readonly)?='",
            ps -> {}, rs -> rs.getString(1));

        assertThat(defaults).as("each data relation is granted when it is created, never by default").isEmpty();
    }
}
