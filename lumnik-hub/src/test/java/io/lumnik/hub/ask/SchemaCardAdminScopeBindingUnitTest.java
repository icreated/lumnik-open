package io.lumnik.hub.ask;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.extraction.spi.ask.SchemaCard;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The métier scope boundary on the schema-card admin doors — kind:View's rule, applied to the pair
 * that was outside it.
 *
 * <p>These two endpoints spent their life as {@code RagSchemaResource} in {@code lumnik-rag}, where
 * {@code ScopeBoundaryInventoryTest} — which walks the HUB's resources — could not see them, and
 * they never asked whether the caller held the scope they served. A bound admin could read another
 * scope's card, or rebuild it. Moving the door into the hub (#488) is what surfaced it: the
 * inventory failed the build on the first run after the move.
 *
 * <p>Asserted with NO collaborator wired: a foreign scope must be refused before anything reaches
 * {@code DiscoveryService}, so a null service passing this test IS the proof.
 */
class SchemaCardAdminScopeBindingUnitTest {

    @BeforeEach void tenant() { CurrentTenantHolder.set(1L); }

    @AfterEach void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

    private static void bindTo(String... scopes) {
        var roles = new HashSet<>(Set.of("lm_admin"));
        for (String s : scopes) roles.add("scope:" + s);
        CurrentRolesHolder.set(roles);
    }

    @SuppressWarnings("unchecked")
    private static String errorOf(Response r) {
        return String.valueOf(((Map<String, Object>) r.getEntity()).get("error"));
    }

    @Test
    void reading_a_foreign_scopes_card_is_refused_before_anything_is_read() {
        bindTo("magasin");
        Response r = new SchemaCardAdminResource().getSchema("clients");

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void rebuilding_a_foreign_scopes_card_is_refused_before_anything_is_written() {
        bindTo("magasin");
        Response r = new SchemaCardAdminResource().rediscover("clients");

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void the_refusal_does_not_disclose_whether_the_scope_even_exists() {
        bindTo("magasin");
        Response real = new SchemaCardAdminResource().getSchema("clients");
        Response invented = new SchemaCardAdminResource().getSchema("pas-un-scope");

        assertThat(real.getStatus()).isEqualTo(invented.getStatus()).isEqualTo(403);
        assertThat(errorOf(real)).isEqualTo(errorOf(invented));
    }

    @Test
    void a_blank_scope_is_still_the_parameter_refusal() {
        bindTo("magasin");
        assertThat(new SchemaCardAdminResource().getSchema("  ").getStatus()).isEqualTo(400);
    }

    @Test
    void a_padded_scope_is_the_same_scope() {
        // `?scope=clients%20` used to be a different name: refused for a bound caller naming a tag
        // they DO hold, and looked up under a key no card is stored beneath for an unscoped one.
        // Both doors strip once, before the boundary and before the lookup (#510, Copilot round 4).
        bindTo("clients");
        SchemaCardAdminResource res = new SchemaCardAdminResource();
        res.discovery = noCards;

        assertThat(res.getSchema(" clients ").getStatus())
            .as("past the gate, then the honest no-schema").isEqualTo(404);
        assertThat(res.rediscover(" magasin ").getStatus())
            .as("and the refusal reads the same name the caller does not hold").isEqualTo(403);
    }

    @Test
    void an_unscoped_admin_is_untouched_and_reaches_the_service() {
        // No scope:* role means tenant-wide, so the gate must let this one through. Proven by the
        // ANSWER past it — the honest 404 of a scope with no card — and not by an NPE from the
        // unwired collaborator: a test that passes because something was null stops proving
        // anything the day that something is no longer null, without saying so.
        CurrentRolesHolder.set(Set.of("lm_admin"));
        SchemaCardAdminResource res = new SchemaCardAdminResource();
        res.discovery = noCards;

        Response r = res.getSchema("clients");

        assertThat(r.getStatus()).as("past the gate, then the honest no-schema").isEqualTo(404);
        assertThat(errorOf(r)).contains("clients");
    }

    /** A discovery that holds no card: enough to see what answers PAST the scope gate. */
    private final DiscoveryService noCards = new DiscoveryService() {
        @Override public Optional<SchemaCard> load(long tenantId, String scope) {
            return Optional.empty();
        }
    };
}
