package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.IndexedSearchResult;

/**
 * The three things a search can come back as. An absent or failing provider is {@link Unavailable},
 * so no caller can turn it into an empty successful answer; the caller's own unanswerable request
 * (a stale cursor, an undeclared facet key) is {@link Rejected} instead — retrying later never
 * helps it, only a changed request does, which is why it is not folded into {@link Unavailable}.
 */
public sealed interface SearchOutcome {

    /** The provider answered; zero hits here means exactly "nothing matched". */
    record Hits(IndexedSearchResult result) implements SearchOutcome {}

    /** Not installed, or installed and unable to answer — {@code capability} says which. */
    record Unavailable(SearchCapability capability) implements SearchOutcome {}

    /** The request itself cannot be answered as given — the provider's own explanation. */
    record Rejected(String message) implements SearchOutcome {}
}
