package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The métier scope boundary on the kind:Connector write door — the twin of
 * {@code EntityApplyScopeBindingUnitTest}, on the door that had none.
 *
 * <p>{@code metadata.scopes} became the one declared, validated field the connector's scopes come
 * from (#388), which is exactly the seam a write gate belongs on: a bound user must hold EVERY
 * scope they publish into ({@code ScopeGrants.allowsAll}), or applying {@code scopes: [magasin]}
 * makes tables askable in a métier they were never granted. Reads accept an overlap; writes may
 * not — {@code ScopeGrants} calls the asymmetry "the whole point".
 *
 * <p>Asserted with NO collaborators wired beyond the JSON mapper: the refusal must land before
 * validation and long before a handler writes a connector row. Past the gate, the unwired
 * validation service NPEs — that NPE is the assertion that the gate let the call through.
 */
class ConnectorApplyScopeBindingUnitTest {

    private static final String CLIENTS_CONNECTOR = """
        apiVersion: connectors.lumnik.io/v1
        kind: Connector
        metadata:
          name: cust
          connector_type: csv-file
          scopes: [clients]
        spec:
          transport: { kind: local, path: /imports/*.csv }
          parser: { has_header: true }
          after_process: none
          target: {}
        """;

    @BeforeEach void tenant() { CurrentTenantHolder.set(1L); }

    @AfterEach void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

    private static void bindTo(String... scopes) {
        var roles = new HashSet<>(Set.of("lm_integrator"));
        for (String s : scopes) roles.add("scope:" + s);
        CurrentRolesHolder.set(roles);
    }

    private static ConnectorApplyResource resource() {
        return resource(Optional.empty());
    }

    /** @param storedScopes the tags already on this tenant's connector of that name. */
    private static ConnectorApplyResource resource(Optional<List<String>> storedScopes) {
        ConnectorApplyResource res = new ConnectorApplyResource();
        res.json = new ObjectMapper();
        res.connectors = new ConnectorRepository() {
            @Override public Optional<List<String>> scopesOf(long tenant, String name) { return storedScopes; }
        };
        return res;
    }

    @SuppressWarnings("unchecked")
    private static String errorOf(Response r) {
        return String.valueOf(((Map<String, Object>) r.getEntity()).get("error"));
    }

    @Test
    void apply_into_a_foreign_scope_is_refused_before_validation_runs() throws Exception {
        bindTo("magasin");

        Response r = resource().apply(CLIENTS_CONNECTOR);

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void a_scopeless_connector_is_refused_for_a_bound_user() throws Exception {
        // The same rule as reads: a scopeless resource is invisible to a bound user, so a bound
        // integrator must not be able to create one either — they could never see what they made.
        bindTo("clients");

        Response r = resource().apply(CLIENTS_CONNECTOR.replace("  scopes: [clients]\n", ""));

        assertThat(r.getStatus()).isEqualTo(403);
    }

    @Test
    void apply_into_a_partly_held_scope_list_is_refused() throws Exception {
        // granted = {clients}, manifest = [clients, magasin]: an overlap reads, it does not write.
        bindTo("clients");

        Response r = resource().apply(CLIENTS_CONNECTOR.replace("scopes: [clients]", "scopes: [clients, magasin]"));

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(errorOf(r)).isEqualTo("scope not granted");
    }

    @Test
    void apply_into_every_held_scope_passes_the_gate() {
        bindTo("clients", "magasin");

        assertThatThrownBy(() -> resource()
            .apply(CLIENTS_CONNECTOR.replace("scopes: [clients]", "scopes: [clients, magasin]")))
            .as("holding both, the gate lets it through to the unwired validation service")
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("validationService");
    }

    @Test
    void apply_into_a_granted_scope_passes_the_gate() {
        bindTo("clients");

        // Java's helpful NPE names the null: the assertion proves the call reached the unwired
        // validation service — the first thing past the gate — and not some earlier null.
        assertThatThrownBy(() -> resource().apply(CLIENTS_CONNECTOR))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("validationService");
    }

    @Test
    void an_unscoped_user_is_untouched() {
        CurrentRolesHolder.set(Set.of("lm_integrator"));

        // Java's helpful NPE names the null: the assertion proves the call reached the unwired
        // validation service — the first thing past the gate — and not some earlier null.
        assertThatThrownBy(() -> resource().apply(CLIENTS_CONNECTOR))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("validationService");
    }

    // ---- the STORED scopes: an apply may not re-home what the caller does not hold (#509) ----

    @Test
    void apply_may_not_rehome_a_connector_that_lives_in_scopes_the_caller_does_not_hold() {
        // Holding magasin passes the incoming check. `cust` already belongs to clients, and
        // ApplyUpsert.connector finds it by (tenantId, name) and overwrites its tags: the clients
        // connector — schedule, window, endpoints and all — would become a magasin connector.
        bindTo("magasin");

        // The boundary THROWS rather than handing back a refusal a door could drop; JAX-RS serves
        // the attached response, so the wire is the same 403 — measured on this very door by
        // ScopeRefusalOnTheWireIT, which reads it off the socket rather than asserting it here.
        assertThatThrownBy(() -> resource(Optional.of(List.of("clients")))
            .apply(CLIENTS_CONNECTOR.replace("scopes: [clients]", "scopes: [magasin]")))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(403);
                assertThat(errorOf(e.getResponse())).isEqualTo("scope not granted");
            });
    }

    @Test
    void apply_may_not_narrow_a_connector_out_of_a_scope_the_caller_does_not_hold() {
        // The incoming list is a subset the caller fully holds, so allowsAll passes on it. The
        // stored tags are [clients, magasin]; re-applying as [clients] drops magasin and the
        // connector's tables stop being askable in a métier this user was never granted.
        bindTo("clients");

        assertThatThrownBy(() -> resource(Optional.of(List.of("clients", "magasin"))).apply(CLIENTS_CONNECTOR))
            .isInstanceOfSatisfying(WebApplicationException.class,
                e -> assertThat(e.getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    void apply_updates_a_connector_of_scopes_the_caller_does_hold() {
        bindTo("clients");

        assertThatThrownBy(() -> resource(Optional.of(List.of("clients"))).apply(CLIENTS_CONNECTOR))
            .as("its own connector: past both gates, into the unwired validation service")
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("validationService");
    }

    @Test
    void apply_of_a_free_name_is_not_refused() {
        bindTo("magasin");

        assertThatThrownBy(() -> resource()
            .apply(CLIENTS_CONNECTOR.replace("scopes: [clients]", "scopes: [magasin]")))
            .as("nothing is stored under this name — creating it is not re-homing")
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("validationService");
    }
}
