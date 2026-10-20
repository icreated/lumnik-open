package io.lumnik.hub.sql;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A soft-deleted row must not keep holding a unique value nobody can see any more.
 *
 * <p>V44/V45/V46 fixed three tables where it did: the delete door stamped {@code deleted_at}, every
 * reader filtered the row out, and the unique constraint went on counting it — so the name was gone
 * from every listing and unusable at the same time, and the door that tried to reuse it answered an
 * anonymous 500. The rule this pins is the one V32 states for {@code core.workflow_manifest}: the
 * constraint must agree with the pre-check that guards it.
 *
 * <p>The domain is every unique rule (the primary key aside) on a table that can be soft-deleted at
 * all. SQL cannot see WHICH doors actually stamp {@code deleted_at}, so intent is declared here
 * rather than inferred, and a table that appears with a bare rule and no declaration fails until
 * its author says which of the two it is.
 */
@QuarkusTest
class SoftDeleteNameUniquenessIT {

    /**
     * The tables a production door soft-deletes AND whose unique rule must therefore be scoped to
     * live rows. These are three of the four call sites that stamp {@code deleted_at} anywhere in
     * {@code src/main}: {@code SecretResource.delete} (native UPDATE), {@code EndpointResource} and
     * {@code IntegrationResource} ({@code softDelete()}). The fourth is {@code ScimUserService} —
     * see {@code identity.app_user} below, which is the exception that makes the rule readable.
     */
    private static final Set<String> MUST_BE_SCOPED_TO_LIVE_ROWS = Set.of(
        "platform.secret",
        "connector.connector_endpoint",
        "core.webhook_endpoint");

    /**
     * Tables whose unique rule is deliberately bare, and why. A new row appearing in the query
     * below belongs in one list or the other: either its door soft-deletes (scope the rule) or it
     * does not (say so here, naming what makes it true).
     */
    private static final Map<String, String> BARE_ON_PURPOSE = Map.ofEntries(
        entry("connector.connector",
            "ConnectorAdminResource.delete is a real DELETE (repo.delete) — the row leaves and frees its name"),
        entry("connector.connector_run",
            "no door stamps deleted_at on a run (the ledger shows every run), and uq_connector_run_in_flight (#952) "
          + "is keyed on status: a run leaves it by ending, not by being tombstoned"),
        entry("connector.connector_credential",
            "read-only: nothing in src/main persists or deletes one (CredentialResolverImpl and DiscoveryService only read)"),
        entry("identity.app_role", "no door stamps deleted_at on a role"),
        entry("identity.org", "no door stamps deleted_at on an org"),
        entry("identity.tenant", "no door stamps deleted_at on a tenant"),
        entry("identity.scim_token", "no door stamps deleted_at on a token — rotation replaces the hash"),
        entry("identity.app_user",
            "DELIBERATE, and the opposite of the three above: SCIM deprovision DOES soft-delete, and the "
          + "tombstone must keep holding the key. Both doors resolve an external identity in ANY state "
          + "(UserRepository.findByExternalIdentityAnyState) and act on what they find — a login is "
          + "refused with 403 (JitUserProvisioner, guardActive=true), a SCIM re-POST REVIVES that same "
          + "row (ScimUserService, guardActive=false then undelete()). Scoping this rule to live rows "
          + "would let one identity own two rows, and that ANY-state lookup takes firstResultOptional: "
          + "it could then return either, hiding the deactivated one behind a fresh one"));

    @Inject
    DataSource dataSource;

    /**
     * The other direction, and the only reason this list exists: a rule that is SCOPED is
     * indistinguishable, to the test below, from a rule that is GONE. Dropping uniqueness would free
     * a deleted name by freeing the live one too.
     */
    @Test
    void a_table_its_door_soft_deletes_still_has_a_unique_rule() {
        Map<String, List<String>> byTable = uniqueRulesOnSoftDeletableTables();
        for (String table : MUST_BE_SCOPED_TO_LIVE_ROWS) {
            assertThat(byTable.get(table))
                .as("%s lost its unique rule entirely — freeing a deleted name must not free a live one", table)
                .isNotEmpty();
        }
    }

    /** The policy itself, over every table at once: a bare rule is a defect unless it is declared. */
    @Test
    void a_bare_unique_rule_on_a_soft_deletable_table_is_declared() {
        List<String> undeclared = new ArrayList<>();
        uniqueRulesOnSoftDeletableTables().forEach((table, rules) -> {
            if (BARE_ON_PURPOSE.containsKey(table)) return;
            rules.stream().filter(r -> !r.contains("WHERE (deleted_at IS NULL)"))
                .forEach(r -> undeclared.add(table + ": " + r));
        });
        assertThat(undeclared)
            .as("a table that can be soft-deleted carries a unique rule a tombstone would keep holding. "
              + "Either its door stamps deleted_at — scope the rule WHERE deleted_at IS NULL, as V44-V46 do — "
              + "or it never does, and that belongs in BARE_ON_PURPOSE with what makes it true")
            .isEmpty();
    }

    /** index definitions per schema-qualified table, for every table carrying a deleted_at column. */
    private Map<String, List<String>> uniqueRulesOnSoftDeletableTables() {
        String sql = """
            SELECT n.nspname || '.' || c.relname AS tbl, pg_get_indexdef(x.indexrelid) AS def
            FROM pg_index x
            JOIN pg_class c ON c.oid = x.indrelid
            JOIN pg_class i ON i.oid = x.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE x.indisunique AND NOT x.indisprimary
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND EXISTS (SELECT 1 FROM pg_attribute a
                          WHERE a.attrelid = c.oid AND a.attname = 'deleted_at' AND NOT a.attisdropped)
            ORDER BY 1, 2
            """;
        Map<String, List<String>> byTable = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                byTable.computeIfAbsent(rs.getString("tbl"), k -> new ArrayList<>()).add(rs.getString("def"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return byTable;
    }
}
