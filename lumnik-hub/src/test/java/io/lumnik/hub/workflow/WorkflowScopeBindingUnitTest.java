package io.lumnik.hub.workflow;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The métier scope boundary on the kind:Workflow surface — the same #131 rule kind:View already
 * holds. A bound user (≥1 {@code scope:} role) may declare, list and read back only the lifecycles
 * of scopes they hold; an unscoped user is untouched. Uniform refusal: 403 "scope not granted".
 *
 * <p>The write door is asserted with NO collaborators wired: a foreign scope must be refused before
 * anything reaches the store or the card, so a null store passing the test IS the proof.
 */
class WorkflowScopeBindingUnitTest {

    private static final String MAGASIN_WORKFLOW = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata:
          name: commande-lifecycle
        spec:
          scope: magasin
          provenance: declared
          on:
            table: connector.t_commandes_csv
            column: statut
          states:
            - code: BROUILLON
              initial: true
            - code: TERMINE
              terminal: true
          transitions:
            - { from: [BROUILLON], to: TERMINE }
        """;

    private final SchemaCardStore emptyCards = new SchemaCardStore() {
        @Override public Optional<SchemaCard> load(long tenantId, String scope) { return Optional.empty(); }
    };

    @BeforeEach void tenant() { CurrentTenantHolder.set(1L); }

    @AfterEach void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

    private static void bindTo(String... scopes) {
        var roles = new HashSet<>(Set.of("lm_integrator"));
        for (String s : scopes) roles.add("scope:" + s);
        CurrentRolesHolder.set(roles);
    }

    @SuppressWarnings("unchecked")
    private static String errorOf(Response r) {
        return String.valueOf(((Map<String, Object>) r.getEntity()).get("error"));
    }

    // ---- apply: the write door ----

    @Test
    void apply_foreign_scope_is_refused_before_anything_is_read() {
        bindTo("clients");
        // No store, no cards: if the gate did not fire first this would NPE instead of refusing.
        Response r = new WorkflowApplyResource().apply(MAGASIN_WORKFLOW);

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void apply_refuses_before_disclosing_whether_the_scope_even_exists() {
        // A 404 "no schema for scope 'magasin'" would tell a bound user which scopes exist.
        // The refusal must be indistinguishable for a real scope and an invented one.
        bindTo("clients");
        Response real = new WorkflowApplyResource().apply(MAGASIN_WORKFLOW);
        Response invented = new WorkflowApplyResource()
            .apply(MAGASIN_WORKFLOW.replace("scope: magasin", "scope: pas-un-scope"));

        assertThat(real.getStatus()).isEqualTo(invented.getStatus()).isEqualTo(403);
        assertThat(errorOf(real)).isEqualTo(errorOf(invented));
    }

    @Test
    void apply_granted_scope_passes_the_gate() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.cards = emptyCards;

        Response r = res.apply(MAGASIN_WORKFLOW);

        assertThat(r.getStatus()).as("past the gate, then the honest no-schema").isEqualTo(404);
    }

    @Test
    void apply_unscoped_user_is_untouched() {
        CurrentRolesHolder.set(Set.of("lm_integrator"));
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.cards = emptyCards;

        assertThat(res.apply(MAGASIN_WORKFLOW).getStatus()).isEqualTo(404);
    }

    // ---- the read doors ----

    private static WorkflowManifestStore storeWith(WorkflowManifestStore.StoredWorkflow... rows) {
        return new WorkflowManifestStore() {
            @Override public List<StoredWorkflow> listByTenant(long tenantId) { return List.of(rows); }
            @Override public Optional<String> findScope(long tenantId, String name) {
                return List.of(rows).stream().filter(w -> w.name().equals(name))
                    .map(StoredWorkflow::scope).findFirst();
            }
            @Override public Optional<String> findYaml(long tenantId, String name) {
                return Optional.of("apiVersion: apps.lumnik.io/v1");
            }
        };
    }

    private static WorkflowManifestStore.StoredWorkflow row(String name, String scope) {
        return new WorkflowManifestStore.StoredWorkflow(
            name, scope, "connector.t_x", "statut", true, "{}");
    }

    @Test
    void list_shows_only_the_lifecycles_of_scopes_the_user_holds() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("commande", "magasin"), row("facture", "compta"));

        @SuppressWarnings("unchecked")
        List<WorkflowApplyResource.WorkflowInfo> out =
            (List<WorkflowApplyResource.WorkflowInfo>) res.list().getEntity();

        assertThat(out).extracting(WorkflowApplyResource.WorkflowInfo::name)
            .as("a bound user must not learn that 'facture' exists")
            .containsExactly("commande");
    }

    @Test
    void list_is_untouched_for_an_unscoped_user() {
        CurrentRolesHolder.set(Set.of("lm_integrator"));
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("commande", "magasin"), row("facture", "compta"));

        @SuppressWarnings("unchecked")
        List<WorkflowApplyResource.WorkflowInfo> out =
            (List<WorkflowApplyResource.WorkflowInfo>) res.list().getEntity();

        assertThat(out).hasSize(2);
    }

    @Test
    void manifest_of_a_foreign_scope_is_refused() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("facture", "compta"));

        Response r = res.manifest("facture");

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void manifest_of_a_granted_scope_is_served() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("commande", "magasin"));

        assertThat(res.manifest("commande").getStatus()).isEqualTo(200);
    }
}
