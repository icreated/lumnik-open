package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.InvalidIndexedSearchQueryException;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.extraction.spi.search.SearchAvailability;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The hub's single door to the optional {@link IndexedSearchPort} (#649/#668). Every consumer — the
 * direct Search API, the operations door — goes through here, so "absence never looks like an empty
 * result" is enforced once instead of at each call site.
 *
 * <p>Zero providers on the classpath is the normal state of Open and of Pro without Search. Several
 * providers (a PostgreSQL-native one and a cluster one) are chosen between by
 * {@code lumnik.search.provider}; without that setting the choice is refused rather than guessed.
 */
@ApplicationScoped
public class SearchGate {

    private static final Logger LOG = Logger.getLogger(SearchGate.class);

    @Inject Instance<IndexedSearchPort> providers;

    @ConfigProperty(name = "lumnik.search.provider")
    Optional<String> configuredProvider = Optional.empty();

    /**
     * Resolved once: installed beans and configuration are fixed for the application's lifetime, and
     * iterating the {@code Instance} per request would create — and never release — a fresh instance
     * of any {@code @Dependent} provider each time.
     */
    private volatile Selection selection;

    /** Capability discovery: never throws, never calls the backend beyond the provider's own status. */
    public SearchCapability capability() {
        Selection s = select();
        return s.provider() == null ? s.capability() : statusOf(s.provider(), s.name());
    }

    /**
     * Whether any provider is built in — the opposite of {@link #capability()}'s {@code not_installed}.
     * Never asks a provider anything: an installed provider that is failing or ambiguous still counts,
     * since fixing it is what builds a declared index; with none, nothing ever will.
     */
    public boolean providerInstalled() {
        SearchCapability whyNone = select().capability();
        return whyNone == null || whyNone.state() != SearchAvailability.UNAVAILABLE;
    }

    /**
     * Run one query through the selected provider. Never throws and never returns a successful empty
     * answer for a provider that is absent, failing, or returned nothing at all.
     */
    public SearchOutcome search(IndexedSearchQuery query) {
        Selection s = select();
        if (s.provider() == null) return new SearchOutcome.Unavailable(s.capability());

        // Checked after selection on purpose: a caller who may read nothing still learns that Search
        // is not installed, rather than an empty answer that hides it. With a provider, an empty
        // allowed set has exactly one honest answer, and the backend is not asked to produce it.
        if (query.allowedEntities().isEmpty()) return new SearchOutcome.Hits(IndexedSearchResult.empty());

        try {
            IndexedSearchResult result = s.provider().search(query);
            if (result != null) return new SearchOutcome.Hits(result);
            LOG.warnf("Search provider '%s' returned no result object", s.name());
        } catch (InvalidIndexedSearchQueryException e) {
            // A refused cursor (stale, tampered, from another query) is never fixed by sending it again:
            // the way on is page one. Said here, once, rather than in each provider's own wording.
            return new SearchOutcome.Rejected(query.cursor() == null
                ? e.getMessage()
                : e.getMessage() + "; run the search again without cursor");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Search provider '%s' failed a query", s.name());
        }
        return new SearchOutcome.Unavailable(SearchCapability.degraded(s.name(), SearchCapability.PROVIDER_ERROR));
    }

    private static SearchCapability statusOf(IndexedSearchPort p, String name) {
        try {
            ProviderStatus st = p.status();
            if (st != null) return new SearchCapability(st.state(), name, st.reason(), st.version());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Search provider '%s' failed its status check", name);
        }
        return SearchCapability.degraded(name, SearchCapability.STATUS_FAILED);
    }

    private Selection select() {
        Selection s = selection;
        if (s == null) selection = s = resolve();
        return s;
    }

    /**
     * Exactly one provider with a usable name, or the capability explaining why there is none. Each
     * provider's {@code name()} is read here and only here: a provider that throws or answers blank
     * breaks the port's contract, so it never matches a configured name and is never selected.
     */
    private Selection resolve() {
        List<Named> installed = new ArrayList<>();
        for (IndexedSearchPort p : providers) installed.add(new Named(p, nameOf(p)));
        if (installed.isEmpty()) return Selection.none(SearchCapability.notInstalled());

        Optional<String> wanted = configuredProvider.map(String::strip).filter(n -> !n.isEmpty());
        if (wanted.isPresent()) {
            for (Named n : installed) {
                if (wanted.get().equals(n.name())) return new Selection(n.provider(), n.name(), null);
            }
            return Selection.none(SearchCapability.degraded(wanted.get(), SearchCapability.UNKNOWN_PROVIDER));
        }
        if (installed.size() > 1)
            return Selection.none(SearchCapability.degraded(null, SearchCapability.AMBIGUOUS_PROVIDER));
        Named sole = installed.get(0);
        if (sole.name() == null)
            return Selection.none(SearchCapability.degraded(null, SearchCapability.PROVIDER_ERROR));
        return new Selection(sole.provider(), sole.name(), null);
    }

    /** The provider's name, or null when its own {@code name()} throws or answers blank. */
    private static String nameOf(IndexedSearchPort p) {
        try {
            String name = p.name();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            LOG.warnf(e, "Search provider %s failed to report its name", p.getClass().getName());
            return null;
        }
    }

    private record Named(IndexedSearchPort provider, String name) {}

    /** Either a provider with its validated name, or the capability explaining its absence. */
    private record Selection(IndexedSearchPort provider, String name, SearchCapability capability) {
        static Selection none(SearchCapability why) {
            return new Selection(null, null, why);
        }
    }
}
