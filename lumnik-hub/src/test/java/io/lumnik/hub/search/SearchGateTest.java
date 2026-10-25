package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.IndexedSearchException;
import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.InvalidIndexedSearchQueryException;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.extraction.spi.search.SearchAvailability;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #668 absence invariant as executable specification: no provider, a failing provider and a real
 * zero-hit answer are three observably different outcomes.
 */
class SearchGateTest {

    private static final IndexedSearchQuery QUERY =
        new IndexedSearchQuery(7, Set.of("customer"), "mueller", null, 10, null);

    /** A gate wired by hand, like CascadeResolverTest: a CDI mock would satisfy the Instance for every test. */
    @SuppressWarnings("unchecked")
    private static SearchGate gate(String configured, IndexedSearchPort... installed) {
        SearchGate g = new SearchGate();
        Instance<IndexedSearchPort> providers = Mockito.mock(Instance.class);
        Mockito.when(providers.iterator()).thenAnswer(inv -> List.of(installed).iterator());
        g.providers = providers;
        g.configuredProvider = Optional.ofNullable(configured);
        return g;
    }

    private static IndexedSearchPort provider(String name, ProviderStatus status,
                                              Function<IndexedSearchQuery, IndexedSearchResult> answer) {
        return new IndexedSearchPort() {
            @Override public String name() { return name; }
            @Override public ProviderStatus status() { return status; }
            @Override public IndexedSearchResult search(IndexedSearchQuery q) { return answer.apply(q); }
        };
    }

    private static IndexedSearchPort answering(String name, IndexedSearchResult result) {
        return provider(name, ProviderStatus.available(), q -> result);
    }

    @Test void no_provider_is_reported_as_not_installed() {
        SearchCapability c = gate(null).capability();
        assertThat(c.state()).isEqualTo(SearchAvailability.UNAVAILABLE);
        assertThat(c.reason()).isEqualTo("not_installed");
        assertThat(c.provider()).isNull();
    }

    @Test void no_provider_never_answers_with_an_empty_result() {
        assertThat(gate(null).search(QUERY))
            .isEqualTo(new SearchOutcome.Unavailable(SearchCapability.notInstalled()));
    }

    @Test void no_provider_is_reported_even_to_a_caller_who_may_read_nothing() {
        IndexedSearchQuery nothingVisible = new IndexedSearchQuery(7, Set.of(), "x", null, 10, null);
        assertThat(gate(null).search(nothingVisible)).isInstanceOf(SearchOutcome.Unavailable.class);
    }

    @Test void a_legitimate_zero_hit_answer_is_hits_not_unavailable() {
        SearchOutcome o = gate(null, answering("postgres", IndexedSearchResult.empty())).search(QUERY);
        assertThat(o).isEqualTo(new SearchOutcome.Hits(IndexedSearchResult.empty()));
    }

    @Test void hits_pass_through_unchanged() {
        IndexedSearchResult r = new IndexedSearchResult(
            List.of(new IndexedSearchHit("customers", "customer", Map.of("id", "42"), 3.0, null, null)),
            null, null, null);
        assertThat(gate(null, answering("postgres", r)).search(QUERY)).isEqualTo(new SearchOutcome.Hits(r));
    }

    @Test void a_throwing_provider_is_degraded_never_empty_success() {
        IndexedSearchPort failing = provider("opensearch", ProviderStatus.available(),
            q -> { throw new IndexedSearchException("connection refused"); });
        SearchOutcome o = gate(null, failing).search(QUERY);
        assertThat(o).isEqualTo(new SearchOutcome.Unavailable(SearchCapability.degraded("opensearch", "provider_error")));
    }

    @Test void an_invalid_query_is_rejected_not_degraded() {
        IndexedSearchPort invalid = provider("postgres", ProviderStatus.available(),
            q -> { throw new InvalidIndexedSearchQueryException("cursor does not match this query"); });
        SearchOutcome o = gate(null, invalid).search(QUERY);
        assertThat(o).isEqualTo(new SearchOutcome.Rejected("cursor does not match this query"));
    }

    @Test void a_refused_cursor_says_to_run_the_search_again_without_it() {
        IndexedSearchQuery nextPage = new IndexedSearchQuery(7, Set.of("customer"), "mueller", null, 10, "c2");
        IndexedSearchPort stale = provider("postgres", ProviderStatus.available(),
            q -> { throw new InvalidIndexedSearchQueryException("eligible SearchIndex set changed since this cursor was issued"); });
        assertThat(gate(null, stale).search(nextPage)).isEqualTo(new SearchOutcome.Rejected(
            "eligible SearchIndex set changed since this cursor was issued; run the search again without cursor"));
    }

    @Test void provider_installed_is_false_only_when_none_is_built_in_and_never_asks_one() {
        AtomicInteger statusCalls = new AtomicInteger();
        IndexedSearchPort another = provider("opensearch", null, q -> null);
        IndexedSearchPort p = new IndexedSearchPort() {
            @Override public String name() { return "postgres"; }
            @Override public ProviderStatus status() { statusCalls.incrementAndGet(); return ProviderStatus.available(); }
            @Override public IndexedSearchResult search(IndexedSearchQuery q) { return null; }
        };
        assertThat(gate(null).providerInstalled()).isFalse();
        assertThat(gate("opensearch").providerInstalled()).isFalse();
        assertThat(gate(null, p).providerInstalled()).isTrue();
        // installed but unusable is degraded, not absent: a provider is there to build once it is fixed
        assertThat(gate(null, p, another).providerInstalled()).isTrue();
        assertThat(gate("opensearch", p).providerInstalled()).isTrue();
        assertThat(statusCalls).hasValue(0);
    }

    @Test void a_provider_returning_null_is_degraded_never_empty_success() {
        assertThat(gate(null, answering("postgres", null)).search(QUERY))
            .isInstanceOf(SearchOutcome.Unavailable.class);
    }

    @Test void an_empty_allowed_set_answers_zero_hits_without_asking_the_backend() {
        AtomicInteger calls = new AtomicInteger();
        IndexedSearchPort counting = provider("postgres", ProviderStatus.available(),
            q -> { calls.incrementAndGet(); return IndexedSearchResult.empty(); });
        IndexedSearchQuery nothingVisible = new IndexedSearchQuery(7, Set.of(), "x", null, 10, null);
        assertThat(gate(null, counting).search(nothingVisible)).isInstanceOf(SearchOutcome.Hits.class);
        assertThat(calls).hasValue(0);
    }

    @Test void the_provider_receives_the_restriction_it_must_enforce() {
        IndexedSearchQuery[] seen = new IndexedSearchQuery[1];
        IndexedSearchPort capturing = provider("postgres", ProviderStatus.available(),
            q -> { seen[0] = q; return IndexedSearchResult.empty(); });
        gate(null, capturing).search(QUERY);
        assertThat(seen[0].tenantId()).isEqualTo(7);
        assertThat(seen[0].allowedEntities()).containsExactly("customer");
    }

    @Test void a_single_healthy_provider_is_available() {
        SearchCapability c = gate(null, answering("postgres", IndexedSearchResult.empty())).capability();
        assertThat(c).isEqualTo(new SearchCapability(SearchAvailability.AVAILABLE, "postgres", null, null));
    }

    @Test void a_provider_reporting_degraded_is_degraded_with_its_reason() {
        IndexedSearchPort down = provider("opensearch", ProviderStatus.degraded("backend_unreachable"), q -> null);
        assertThat(gate(null, down).capability())
            .isEqualTo(SearchCapability.degraded("opensearch", "backend_unreachable"));
    }

    @Test void a_throwing_or_null_status_is_degraded() {
        IndexedSearchPort throwing = new IndexedSearchPort() {
            @Override public String name() { return "opensearch"; }
            @Override public ProviderStatus status() { throw new IllegalStateException("boom"); }
            @Override public IndexedSearchResult search(IndexedSearchQuery q) { return null; }
        };
        assertThat(gate(null, throwing).capability().reason()).isEqualTo("status_failed");
        assertThat(gate(null, provider("pg", null, q -> null)).capability().reason()).isEqualTo("status_failed");
    }

    /** A provider whose own name() throws — the gate must still never throw. */
    private static IndexedSearchPort nameless(ProviderStatus status,
                                              Function<IndexedSearchQuery, IndexedSearchResult> answer) {
        return new IndexedSearchPort() {
            @Override public String name() { throw new IllegalStateException("no name"); }
            @Override public ProviderStatus status() { return status; }
            @Override public IndexedSearchResult search(IndexedSearchQuery q) { return answer.apply(q); }
        };
    }

    @Test void a_provider_whose_name_throws_while_failing_a_query_is_degraded_not_thrown() {
        IndexedSearchPort p = nameless(ProviderStatus.available(), q -> { throw new IndexedSearchException("down"); });
        assertThat(gate(null, p).search(QUERY)).isInstanceOf(SearchOutcome.Unavailable.class);
        assertThat(gate(null, nameless(ProviderStatus.available(), q -> null)).search(QUERY))
            .isInstanceOf(SearchOutcome.Unavailable.class);
    }

    @Test void a_nameless_sole_provider_is_never_asked_even_when_it_would_answer() {
        AtomicInteger calls = new AtomicInteger();
        Function<IndexedSearchQuery, IndexedSearchResult> answering = q -> {
            calls.incrementAndGet();
            return IndexedSearchResult.empty();
        };
        assertThat(gate(null, nameless(ProviderStatus.available(), answering)).search(QUERY))
            .isEqualTo(new SearchOutcome.Unavailable(SearchCapability.degraded(null, "provider_error")));
        assertThat(gate(null, provider(" ", ProviderStatus.available(), answering)).search(QUERY))
            .isEqualTo(new SearchOutcome.Unavailable(SearchCapability.degraded(null, "provider_error")));
        assertThat(calls).hasValue(0);
    }

    @Test void a_provider_whose_name_throws_is_reported_degraded_by_capability() {
        assertThat(gate(null, nameless(ProviderStatus.available(), q -> null)).capability().state())
            .isEqualTo(SearchAvailability.DEGRADED);
    }

    @Test void a_provider_whose_name_throws_never_matches_a_configured_name() {
        SearchGate g = gate("postgres", nameless(ProviderStatus.available(), q -> IndexedSearchResult.empty()));
        assertThat(g.capability()).isEqualTo(SearchCapability.degraded("postgres", "unknown_provider"));
        assertThat(g.search(QUERY)).isInstanceOf(SearchOutcome.Unavailable.class);
    }

    @Test void providers_are_resolved_once_but_health_is_asked_every_time() {
        AtomicInteger statusCalls = new AtomicInteger();
        IndexedSearchPort p = new IndexedSearchPort() {
            @Override public String name() { return "postgres"; }
            @Override public ProviderStatus status() { statusCalls.incrementAndGet(); return ProviderStatus.available(); }
            @Override public IndexedSearchResult search(IndexedSearchQuery q) { return IndexedSearchResult.empty(); }
        };
        SearchGate g = gate(null, p);
        g.capability();
        g.capability();
        g.search(QUERY);
        Mockito.verify(g.providers, Mockito.times(1)).iterator();
        assertThat(statusCalls).hasValue(2);
    }

    @Test void two_providers_without_configuration_are_refused_not_guessed() {
        SearchGate g = gate(null, answering("postgres", IndexedSearchResult.empty()),
                                  answering("opensearch", IndexedSearchResult.empty()));
        assertThat(g.capability()).isEqualTo(SearchCapability.degraded(null, "ambiguous_provider"));
        assertThat(g.search(QUERY)).isInstanceOf(SearchOutcome.Unavailable.class);
    }

    @Test void configuration_selects_among_several_providers() {
        IndexedSearchResult fromCluster = new IndexedSearchResult(null, null, "next", null);
        SearchGate g = gate(" opensearch ", answering("postgres", IndexedSearchResult.empty()),
                                            answering("opensearch", fromCluster));
        assertThat(g.capability().provider()).isEqualTo("opensearch");
        assertThat(g.search(QUERY)).isEqualTo(new SearchOutcome.Hits(fromCluster));
    }

    @Test void a_configured_provider_that_is_not_installed_is_degraded() {
        SearchGate g = gate("opensearch", answering("postgres", IndexedSearchResult.empty()));
        assertThat(g.capability()).isEqualTo(SearchCapability.degraded("opensearch", "unknown_provider"));
    }

    @Test void configuration_without_any_provider_is_still_not_installed() {
        assertThat(gate("opensearch").capability()).isEqualTo(SearchCapability.notInstalled());
    }

    @Test void the_capability_body_keeps_every_key_and_lowercases_the_state() {
        Map<String, Object> body = SearchCapabilityResource.body(SearchCapability.notInstalled());
        assertThat(body.keySet()).containsExactly("state", "provider", "reason", "version");
        assertThat(body.get("state")).isEqualTo("unavailable");
        assertThat(body.get("provider")).isNull();
        assertThat(body.get("reason")).isEqualTo("not_installed");
        assertThat(body.get("version")).isNull();
    }

    @Test void capability_carries_the_providers_own_version() {
        IndexedSearchPort fake = provider("acme", new ProviderStatus(SearchAvailability.AVAILABLE, null, "3.1"), q -> null);
        SearchGate g = gate(null, fake);
        SearchCapability cap = g.capability();
        assertThat(cap.version()).isEqualTo("3.1");
    }
}
