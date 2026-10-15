package io.lumnik.extraction.spi.search;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #668: the authorization restriction is part of the query's type, and absence is never "zero hits". */
class IndexedSearchContractTest {

    private static IndexedSearchQuery query(long tenant, Set<String> allowed) {
        return new IndexedSearchQuery(tenant, allowed, "müller", null, 10, null);
    }

    @Test void a_query_cannot_be_built_without_an_allowed_entity_set() {
        assertThatThrownBy(() -> query(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test void a_non_positive_tenant_is_refused_fail_closed() {
        assertThatThrownBy(() -> query(0, Set.of("customer"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(-1, Set.of("customer"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void an_empty_allowed_set_is_kept_empty_never_widened() {
        assertThat(query(1, Set.of()).allowedEntities()).isEmpty();
    }

    @Test void the_allowed_set_is_a_snapshot_the_caller_cannot_widen_afterwards() {
        Set<String> allowed = new HashSet<>(Set.of("customer"));
        IndexedSearchQuery q = query(1, allowed);
        allowed.add("salary");
        assertThat(q.allowedEntities()).containsExactly("customer");
        assertThatThrownBy(() -> q.allowedEntities().add("salary")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void a_blank_entity_name_is_refused() {
        assertThatThrownBy(() -> query(1, Set.of(" "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void blank_text_and_a_zero_limit_are_refused() {
        assertThatThrownBy(() -> new IndexedSearchQuery(1, Set.of("c"), " ", null, 10, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexedSearchQuery(1, Set.of("c"), "x", null, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void null_filters_default_to_empty() {
        assertThat(query(1, Set.of("c")).filters()).isEmpty();
    }

    @Test void a_hit_is_identified_by_index_entity_and_a_non_empty_key() {
        IndexedSearchHit hit = new IndexedSearchHit("customers", "customer", Map.of("id", "42"), 1.5, null, null);
        assertThat(hit.fields()).isEmpty();
        assertThat(hit.highlights()).isEmpty();
        assertThatThrownBy(() -> new IndexedSearchHit("customers", "customer", Map.of(), 1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexedSearchHit("customers", " ", Map.of("id", "42"), 1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void a_result_defaults_null_collections_to_empty() {
        IndexedSearchResult r = new IndexedSearchResult(null, null, null, null);
        assertThat(r.hits()).isEmpty();
        assertThat(r.facets()).isEmpty();
        assertThat(IndexedSearchResult.empty()).isEqualTo(r);
    }

    @Test void a_result_keeps_hits_in_rank_order() {
        IndexedSearchHit a = new IndexedSearchHit("i", "e", Map.of("id", "1"), 2, null, null);
        IndexedSearchHit b = new IndexedSearchHit("i", "e", Map.of("id", "2"), 1, null, null);
        assertThat(new IndexedSearchResult(List.of(a, b), null, null, null).hits()).containsExactly(a, b);
    }

    @Test void an_installed_provider_cannot_claim_to_be_not_installed() {
        assertThatThrownBy(() -> new ProviderStatus(SearchAvailability.UNAVAILABLE, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void a_degraded_status_must_say_why() {
        assertThatThrownBy(() -> ProviderStatus.degraded(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProviderStatus.degraded("backend_unreachable").reason()).isEqualTo("backend_unreachable");
        assertThat(ProviderStatus.available().state()).isEqualTo(SearchAvailability.AVAILABLE);
    }

    @Test void availableCarriesAnOptionalVersion() {
        ProviderStatus withVersion = new ProviderStatus(SearchAvailability.AVAILABLE, null, "7.2.1");
        assertThat(withVersion.version()).isEqualTo("7.2.1");
        assertThat(ProviderStatus.available().version()).isNull();
    }
}
