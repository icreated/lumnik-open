package io.lumnik.testkit.search.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTypesTest {

    @Test
    void notApplicableRejectsABlankReason() {
        assertThatThrownBy(() -> new Applicability.NotApplicable(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Applicability.NotApplicable(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notApplicableKeepsANonBlankReason() {
        Applicability a = new Applicability.NotApplicable("PostgreSQL is the hub's own vital datastore");
        assertThat(a).isInstanceOf(Applicability.NotApplicable.class);
        assertThat(((Applicability.NotApplicable) a).reason())
            .isEqualTo("PostgreSQL is the hub's own vital datastore");
    }

    @Test
    void rebuildOutcomeCarriesItsRunId() {
        RebuildOutcome started = new RebuildOutcome.Started("run-123");
        assertThat(((RebuildOutcome.Started) started).runId()).isEqualTo("run-123");
    }

    @Test
    void canonicalSearchResultCarriesHitsAndDegradedState() {
        CanonicalSearchHit hit = new CanonicalSearchHit(
            "customer", Map.of("code", "ACME"), Map.of("code", "ACME", "name", "Acme Corp"),
            Map.of(), "/api/entities/customer?code=ACME");
        CanonicalSearchResult result = new CanonicalSearchResult(List.of(hit), Map.of(), false, null);
        assertThat(result.hits()).containsExactly(hit);
        assertThat(result.degraded()).isFalse();
    }
}
