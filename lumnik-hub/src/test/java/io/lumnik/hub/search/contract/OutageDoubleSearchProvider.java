package io.lumnik.hub.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchException;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.ProviderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** A controllable double proving the hub-side outage contract once, independent of which real
 *  provider(s) are installed (#674, §2.2) — deliberately DEGRADED/throwing always, never a real
 *  backend. `@Alternative` only, no `@Priority`: confined to {@link SearchOutageApiContractProfile}'s
 *  own test run via {@code getEnabledAlternatives()}, never globally active. */
@Alternative
@ApplicationScoped
public class OutageDoubleSearchProvider implements IndexedSearchPort {

    public static final String NAME = "contract-suite-outage-double";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderStatus status() {
        return ProviderStatus.degraded("contract_suite_outage_double");
    }

    @Override
    public IndexedSearchResult search(IndexedSearchQuery query) {
        throw new IndexedSearchException("outage double always fails",
            new RuntimeException("contract-suite simulated outage"));
    }
}
