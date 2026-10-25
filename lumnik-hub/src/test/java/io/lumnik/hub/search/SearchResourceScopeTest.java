package io.lumnik.hub.search;

import io.lumnik.hub.entity.EntityManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entities one search may read: visible to the caller, narrowed to the app's scope when the
 * request names one. The grants are an argument (the resource passes {@code ScopeGrants.granted()}),
 * so no case depends on the global roles holder.
 */
class SearchResourceScopeTest {

    private static EntityManifest entity(String name, String... scopes) {
        return new EntityManifest("Entity", name, null, List.of("code"), List.of(), List.of(scopes), null, null, null);
    }

    private static final List<EntityManifest> ALL = List.of(
        entity("Client", "clients"),
        entity("Article", "stock"),
        entity("Livraison", "stock", "clients"),
        entity("Orphan"));

    private static Optional<Set<String>> bound(String... grants) { return Optional.of(Set.of(grants)); }

    @Test
    void a_bound_caller_with_two_scopes_gets_only_the_apps_metier() {
        assertThat(SearchResource.allowedFor(ALL, bound("clients", "stock"), "stock"))
            .containsExactlyInAnyOrder("Article", "Livraison");
    }

    @Test
    void an_unscoped_caller_is_still_restricted_to_the_requested_scope() {
        assertThat(SearchResource.allowedFor(ALL, Optional.empty(), "stock"))
            .containsExactlyInAnyOrder("Article", "Livraison");
    }

    @Test
    void a_multi_scope_entity_is_included_when_it_carries_the_scope_and_stays_readable() {
        assertThat(SearchResource.allowedFor(ALL, bound("clients"), "clients"))
            .containsExactlyInAnyOrder("Client", "Livraison");
    }

    @Test
    void an_entity_the_caller_cannot_read_is_never_added_by_naming_its_scope() {
        // holds only `stock`; `Client` carries only `clients`
        assertThat(SearchResource.allowedFor(ALL, bound("stock"), "clients")).containsExactly("Livraison");
    }

    @Test
    void no_entity_carries_the_scope_so_the_set_is_empty_never_everything() {
        assertThat(SearchResource.allowedFor(ALL, Optional.empty(), "nobody")).isEmpty();
    }

    @Test
    void without_a_scope_the_result_is_exactly_what_it_always_was() {
        assertThat(SearchResource.allowedFor(ALL, bound("clients", "stock"), null))
            .containsExactlyInAnyOrder("Client", "Article", "Livraison");
        assertThat(SearchResource.allowedFor(ALL, Optional.empty(), null))
            .containsExactlyInAnyOrder("Client", "Article", "Livraison", "Orphan");
    }
}
