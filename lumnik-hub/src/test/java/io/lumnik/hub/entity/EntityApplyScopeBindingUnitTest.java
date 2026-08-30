package io.lumnik.hub.entity;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The métier scope boundary on the kind:Entity write door. {@code EntityReadResource} already binds
 * reads with {@code allowsAny(m.scopes())}; apply did not, so a bound admin could fuse data into a
 * scope they cannot read back.
 *
 * <p>Asserted with NO collaborators wired beyond the parser: the refusal must land before the
 * compiler writes a view on the BYPASSRLS admin datasource.
 */
class EntityApplyScopeBindingUnitTest {

    private static final String CLIENTS_ENTITY = """
        kind: Entity
        name: Customer
        scopes: [clients]
        match: [id]
        sources:
          - table: t_cust_src
            map:
              ROW_ID: id
              NAME: name
        """;

    @BeforeEach void tenant() { CurrentTenantHolder.set(1L); }

    @AfterEach void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

    private static void bindTo(String... scopes) {
        var roles = new HashSet<>(Set.of("lm_admin"));
        for (String s : scopes) roles.add("scope:" + s);
        CurrentRolesHolder.set(roles);
    }

    private static EntityApplyResource resource() {
        EntityApplyResource res = new EntityApplyResource();
        res.parser = new EntityManifestParser();
        return res;
    }

    @SuppressWarnings("unchecked")
    private static String errorOf(WebApplicationException e) {
        return String.valueOf(((Map<String, Object>) e.getResponse().getEntity()).get("error"));
    }

    @Test
    void apply_into_a_foreign_scope_is_refused_before_any_view_is_written() {
        bindTo("magasin");

        assertThatThrownBy(() -> resource().apply(CLIENTS_ENTITY))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(403);
                assertThat(errorOf(e)).isEqualTo("scope not granted");
            });
    }

    @Test
    void a_scopeless_entity_is_refused_for_a_bound_user() {
        // The same rule EntityReadResource already applies: a scopeless resource is invisible to a
        // bound user, so a bound admin must not be able to create one either.
        bindTo("clients");

        assertThatThrownBy(() -> resource().apply(CLIENTS_ENTITY.replace("scopes: [clients]\n", "")))
            .isInstanceOfSatisfying(WebApplicationException.class,
                e -> assertThat(e.getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    void apply_into_a_partly_held_scope_list_is_refused() {
        // granted = {clients}, manifest = [clients, magasin]. An overlap is enough to READ a
        // resource, never enough to CREATE one: applying this would make the fused entity askable
        // in 'magasin', a scope this user does not hold. Scope escalation by partial overlap.
        bindTo("clients");

        assertThatThrownBy(() -> resource()
            .apply(CLIENTS_ENTITY.replace("scopes: [clients]", "scopes: [clients, magasin]")))
            .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(403);
                assertThat(errorOf(e)).isEqualTo("scope not granted");
            });
    }

    @Test
    void apply_into_every_held_scope_passes_the_gate() {
        bindTo("clients", "magasin");

        assertThatThrownBy(() -> resource()
            .apply(CLIENTS_ENTITY.replace("scopes: [clients]", "scopes: [clients, magasin]")))
            .as("holding both, the gate lets it through to the unwired compiler")
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void apply_into_a_granted_scope_passes_the_gate() {
        bindTo("clients");
        // Past the gate it reaches the compiler, which is not wired here — an NPE means the gate
        // let it through, which is exactly what this row asserts.
        assertThatThrownBy(() -> resource().apply(CLIENTS_ENTITY))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void an_unscoped_user_is_untouched() {
        CurrentRolesHolder.set(Set.of("lm_admin"));

        assertThatThrownBy(() -> resource().apply(CLIENTS_ENTITY))
            .isInstanceOf(NullPointerException.class);
    }
}
