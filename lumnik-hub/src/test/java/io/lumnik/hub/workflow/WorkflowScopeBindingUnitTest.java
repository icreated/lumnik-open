package io.lumnik.hub.workflow;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import jakarta.ws.rs.WebApplicationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        res.store = storeWith();   // the name is free — apply also asks the STORED scope (#509)
        res.cards = emptyCards;

        Response r = res.apply(MAGASIN_WORKFLOW);

        assertThat(r.getStatus()).as("past the gate, then the honest no-schema").isEqualTo(404);
    }

    @Test
    void apply_unscoped_user_is_untouched() {
        CurrentRolesHolder.set(Set.of("lm_integrator"));
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith();
        res.cards = emptyCards;

        assertThat(res.apply(MAGASIN_WORKFLOW).getStatus()).isEqualTo(404);
    }

    // ---- the STORED scope: an apply may not re-home what the caller does not hold (#509) ----

    @Test
    void apply_may_not_rehome_a_lifecycle_that_lives_in_a_scope_the_caller_does_not_hold() {
        // Holding magasin passes the incoming check. The name is taken by compta, and the upsert
        // conflicts on (tenant_id, name) setting scope = EXCLUDED.scope: the compta lifecycle would
        // be rewritten into magasin, chunk and all, with no word said.
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("commande-lifecycle", "compta"));
        res.cards = emptyCards;

        // The boundary THROWS rather than handing back a refusal a door could drop; JAX-RS serves
        // the attached response, so the wire is the same 403 (ApplyScopeBoundary's javadoc names
        // where that last claim is measured).
        assertThatThrownBy(() -> res.apply(MAGASIN_WORKFLOW))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(403);
                assertThat(errorOf(e.getResponse())).isEqualTo("scope not granted");
            });
    }

    @Test
    void apply_updates_a_lifecycle_of_a_scope_the_caller_does_hold() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("commande-lifecycle", "magasin"));
        res.cards = emptyCards;

        assertThat(res.apply(MAGASIN_WORKFLOW).getStatus())
            .as("its own manifest: past both gates, then the honest no-schema").isEqualTo(404);
    }

    @Test
    void apply_of_a_free_name_is_not_refused() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("facture", "compta"));   // a different name
        res.cards = emptyCards;

        assertThat(res.apply(MAGASIN_WORKFLOW).getStatus())
            .as("nothing is stored under this name — creating it is not re-homing").isEqualTo(404);
    }

    // ---- the read doors ----

    /** A card for `magasin` that advertises the manifest's trigger table, so apply reaches the
     *  one-lifecycle-per-column check instead of stopping at the honest no-schema 404. */
    private final SchemaCardStore magasinSeesTheTable = new SchemaCardStore() {
        @Override public Optional<SchemaCard> load(long tenantId, String scope) {
            return Optional.of(new SchemaCard(scope, List.of(new SchemaCard.Table(
                "connector.t_commandes_csv", "Commandes",
                List.of(new SchemaCard.Column("statut", "text", "Statut"))))));
        }
    };

    /**
     * The one-lifecycle-per-column refusal must not read another métier's card aloud.
     *
     * <p>It used to stop it by NAME: "workflow 'facture-compta'", a name from a métier the
     * {@code scope:} roles were drawing a boundary around (#516). Narrow, and real: reaching it
     * takes a table advertised in both cards, which the check above lets through.
     */
    @Test
    void the_column_taken_refusal_does_not_name_a_workflow_of_a_scope_the_caller_lacks() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWithColumnOwner(new WorkflowManifestStore.ColumnOwner("facture-compta", "compta"));
        res.cards = magasinSeesTheTable;

        Response r = res.apply(MAGASIN_WORKFLOW);

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(errorOf(r))
            .as("the column is taken, and that is all a caller outside that métier may learn")
            .contains("already has a declared lifecycle")
            .doesNotContain("facture-compta")
            .doesNotContain("compta");
    }

    @Test
    void the_column_taken_refusal_names_the_workflow_when_the_caller_holds_its_scope() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWithColumnOwner(new WorkflowManifestStore.ColumnOwner("commande-v1", "magasin"));
        res.cards = magasinSeesTheTable;

        Response r = res.apply(MAGASIN_WORKFLOW);

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(errorOf(r))
            .as("inside the métier the name is what makes the refusal actionable")
            .contains("commande-v1").contains("re-apply under the name");
    }

    private static WorkflowManifestStore storeWithColumnOwner(WorkflowManifestStore.ColumnOwner owner) {
        return new WorkflowManifestStore() {
            @Override public List<StoredWorkflow> listByTenant(long tenantId) { return List.of(); }
            @Override public Optional<String> findScope(long tenantId, String name) { return Optional.empty(); }
            @Override public Optional<ColumnOwner> findOtherOnColumn(
                    long tenantId, String table, String column, String name) {
                return Optional.of(owner);
            }
        };
    }

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

    // ---- disable and delete: the two gestures #487 gave kind:Process, missing here (#507) ----

    @Test
    void delete_and_enabled_of_a_foreign_scope_are_refused() {
        // Answered BEFORE touching the row, so a bound integrator cannot learn that 'facture'
        // exists by trying to remove it — the same boundary the manifest door already draws.
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith(row("facture", "compta"));

        Response deleted = res.delete("facture");
        Response toggled = res.setEnabled("facture", new WorkflowApplyResource.EnabledRequest(false));

        assertThat(deleted.getStatus()).isEqualTo(403);
        assertThat(errorOf(deleted)).isEqualTo("scope not granted");
        assertThat(toggled.getStatus()).isEqualTo(403);
        assertThat(errorOf(toggled)).isEqualTo("scope not granted");
    }

    @Test
    void delete_and_enabled_of_an_unknown_workflow_are_404_not_403() {
        bindTo("magasin");
        WorkflowApplyResource res = new WorkflowApplyResource();
        res.store = storeWith();

        assertThat(res.delete("nope").getStatus()).isEqualTo(404);
        assertThat(res.setEnabled("nope", new WorkflowApplyResource.EnabledRequest(true)).getStatus())
            .isEqualTo(404);
    }

    @Test
    void enabled_without_a_flag_is_refused_before_the_store_is_touched() {
        bindTo("magasin");
        // No store wired: a 400 here proves the body check runs first.
        assertThat(new WorkflowApplyResource().setEnabled("commande", null).getStatus()).isEqualTo(400);
    }
}
