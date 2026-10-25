package io.lumnik.hub.process;

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
 * The métier scope boundary on the kind:Process surface — kind:View's rule, applied to its sibling.
 * The write door is asserted with NO collaborators wired: a foreign scope must be refused before
 * anything reaches the store or the card, so a null store passing the test IS the proof.
 */
class ProcessScopeBindingUnitTest {

    private static final String CLIENTS_PROCESS = """
        apiVersion: apps.lumnik.io/v1
        kind: Process
        metadata:
          name: gold-client-arrive
        spec:
          scope: clients
          on:
            event: data.row.changed
            table: connector.t_cust_csv
          when:
            - segment=GOLD
          emit:
            type: decision.gold-client-arrive
            message: "Nouveau client GOLD"
        """;

    private final SchemaCardStore emptyCards = new SchemaCardStore() {
        @Override public Optional<SchemaCard> load(long tenantId, String scope) { return Optional.empty(); }
    };

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
    void apply_foreign_scope_is_refused_before_anything_is_read() {
        bindTo("magasin");
        Response r = new ProcessApplyResource().apply(CLIENTS_PROCESS);

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void apply_refuses_before_disclosing_whether_the_scope_even_exists() {
        bindTo("magasin");
        Response real = new ProcessApplyResource().apply(CLIENTS_PROCESS);
        Response invented = new ProcessApplyResource()
            .apply(CLIENTS_PROCESS.replace("scope: clients", "scope: pas-un-scope"));

        assertThat(real.getStatus()).isEqualTo(invented.getStatus()).isEqualTo(403);
        assertThat(errorOf(real)).isEqualTo(errorOf(invented));
    }

    @Test
    void apply_granted_scope_passes_the_gate() {
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith();   // the name is free — apply also asks the STORED scope (#509)
        res.cards = emptyCards;

        assertThat(res.apply(CLIENTS_PROCESS).getStatus())
            .as("past the gate, then the honest no-schema").isEqualTo(404);
    }

    @Test
    void apply_unscoped_user_is_untouched() {
        CurrentRolesHolder.set(Set.of("lm_admin"));
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith();
        res.cards = emptyCards;

        assertThat(res.apply(CLIENTS_PROCESS).getStatus()).isEqualTo(404);
    }

    // ---- the STORED scope: an apply may not re-home what the caller does not hold (#509) ----

    /** The same manifest, same name, declared into the OTHER métier. */
    private static final String MAGASIN_PROCESS = CLIENTS_PROCESS.replace("scope: clients", "scope: magasin");

    @Test
    void apply_may_not_rehome_a_process_that_lives_in_a_scope_the_caller_does_not_hold() {
        // Holding magasin is enough to declare INTO magasin — that is the incoming check, and it
        // passes. The name is the other half of the address, and it is taken by clients: the upsert
        // would rewrite that row's scope to magasin and the clients process would be gone.
        bindTo("magasin");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("gold-client-arrive", "clients"));
        res.cards = emptyCards;

        // The boundary THROWS rather than handing back a refusal a door could drop on the floor
        // (ApplyScopeBoundary's javadoc); JAX-RS serves the attached response, so the wire is 403.
        assertThatThrownBy(() -> res.apply(MAGASIN_PROCESS))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(403);
                assertThat(errorOf(e.getResponse())).isEqualTo("scope not granted");
            });
    }

    @Test
    void apply_updates_a_process_of_a_scope_the_caller_does_hold() {
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("gold-client-arrive", "clients"));
        res.cards = emptyCards;

        assertThat(res.apply(CLIENTS_PROCESS).getStatus())
            .as("its own manifest: past both gates, then the honest no-schema").isEqualTo(404);
    }

    @Test
    void apply_of_a_free_name_is_not_refused() {
        bindTo("magasin");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("stock-bas", "magasin"));   // a different name
        res.cards = emptyCards;

        assertThat(res.apply(MAGASIN_PROCESS).getStatus())
            .as("nothing is stored under this name — creating it is not re-homing").isEqualTo(404);
    }

    // ---- the read doors ----

    private static ProcessManifestStore storeWith(ProcessManifestStore.StoredProcess... rows) {
        return new ProcessManifestStore() {
            @Override public List<StoredProcess> listByTenant(long tenantId) { return List.of(rows); }
            @Override public Optional<String> findScope(long tenantId, String name) {
                return List.of(rows).stream().filter(p -> p.name().equals(name))
                    .map(StoredProcess::scope).findFirst();
            }
            @Override public Optional<String> findYaml(long tenantId, String name) {
                return Optional.of("apiVersion: apps.lumnik.io/v1");
            }
        };
    }

    private static ProcessManifestStore.StoredProcess row(String name, String scope) {
        return new ProcessManifestStore.StoredProcess(
            name, scope, "connector.t_x", List.of("a=b"), "decision.x", "msg", List.of(), true);
    }

    @Test
    void list_shows_only_the_processes_of_scopes_the_user_holds() {
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("gold", "clients"), row("stock-bas", "magasin"));

        @SuppressWarnings("unchecked")
        List<ProcessApplyResource.ProcessInfo> out =
            (List<ProcessApplyResource.ProcessInfo>) res.list().getEntity();

        assertThat(out).extracting(ProcessApplyResource.ProcessInfo::name)
            .as("a bound user must not learn that 'stock-bas' exists")
            .containsExactly("gold");
    }

    @Test
    void manifest_of_a_foreign_scope_is_refused() {
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("stock-bas", "magasin"));

        Response r = res.manifest("stock-bas");

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void manifest_of_a_granted_scope_is_served() {
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("gold", "clients"));

        assertThat(res.manifest("gold").getStatus()).isEqualTo(200);
    }

    @Test
    void delete_and_enabled_of_a_foreign_scope_are_refused() {
        // The two doors #487 added answer the boundary the manifest door already did — and answer
        // it BEFORE touching the row, so a bound admin cannot learn that 'stock-bas' exists by
        // trying to remove it.
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith(row("stock-bas", "magasin"));

        Response deleted = res.delete("stock-bas");
        Response toggled = res.setEnabled("stock-bas", new ProcessApplyResource.EnabledRequest(false));

        assertThat(deleted.getStatus()).isEqualTo(403);
        assertThat(errorOf(deleted)).isEqualTo("scope not granted");
        assertThat(toggled.getStatus()).isEqualTo(403);
        assertThat(errorOf(toggled)).isEqualTo("scope not granted");
    }

    @Test
    void delete_and_enabled_of_an_unknown_process_are_404_not_403() {
        bindTo("clients");
        ProcessApplyResource res = new ProcessApplyResource();
        res.store = storeWith();

        assertThat(res.delete("nope").getStatus()).isEqualTo(404);
        assertThat(res.setEnabled("nope", new ProcessApplyResource.EnabledRequest(true)).getStatus())
            .isEqualTo(404);
    }

    @Test
    void enabled_without_a_flag_is_refused_before_the_store_is_touched() {
        bindTo("clients");
        // No store wired: a 400 here proves the body check runs first.
        assertThat(new ProcessApplyResource().setEnabled("gold", null).getStatus()).isEqualTo(400);
    }
}
