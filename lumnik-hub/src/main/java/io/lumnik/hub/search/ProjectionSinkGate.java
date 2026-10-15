package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.ProjectionSink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The hub's single door to the optional write-side {@link ProjectionSink} (#671). Mirrors
 * {@link SearchGate} exactly, resolving the SAME {@code lumnik.search.provider} config key: the two
 * gates must always agree on which provider is active, or the worker would write documents a
 * different backend never queries (design doc D-sink-discovery, §2).
 */
@ApplicationScoped
public class ProjectionSinkGate {

    private static final Logger LOG = Logger.getLogger(ProjectionSinkGate.class);

    @Inject Instance<ProjectionSink> providers;

    @ConfigProperty(name = "lumnik.search.provider")
    Optional<String> configuredProvider = Optional.empty();

    /**
     * Resolved fresh on every call, unlike {@link SearchGate}'s application-lifetime cache: no
     * caching belongs here so a provider swap is visible immediately, not only after some
     * caller-held reference expires. {@code discoveryTick}'s own dispatch decision resolves once
     * per pair to compare {@code sink.name()} against the state row's remembered provider — and
     * {@code runBuild}/{@code runCatchUp} deliberately resolve AGAIN right before they actually
     * write, rather than trusting that first call's result: the same "never trust a snapshot
     * taken earlier than the actual write" posture {@code readDeclaration}'s own re-read follows
     * elsewhere in this class, minimizing the staleness window between a dispatch decision and
     * the write it leads to, at the cost of one extra (cheap, in-memory) bean-iteration call.
     */
    public Optional<ProjectionSink> resolve() {
        // Counts every INSTALLED bean, including one whose own name() throws or answers blank
        // (Named.name() null below) — SearchGate.resolve()'s own ">1 installed -> ambiguous" count,
        // matched exactly (minor #2, #671 final review) so the two gates can never disagree about
        // ambiguity for the same set of installed beans, the whole point of D-sink-discovery.
        List<Named> installed = new ArrayList<>();
        for (ProjectionSink p : providers) installed.add(new Named(p, nameOf(p)));
        if (installed.isEmpty()) return Optional.empty();

        Optional<String> wanted = configuredProvider.map(String::strip).filter(n -> !n.isEmpty());
        if (wanted.isPresent()) {
            for (Named n : installed) if (wanted.get().equals(n.name())) return Optional.of(n.sink());
            return Optional.empty();
        }
        if (installed.size() > 1) return Optional.empty(); // ambiguous, same as SearchGate
        Named sole = installed.get(0);
        return sole.name() == null ? Optional.empty() : Optional.of(sole.sink());
    }

    /** The provider's name, or null when its own {@code name()} throws or answers blank. */
    private static String nameOf(ProjectionSink p) {
        try {
            String name = p.name();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            LOG.warnf(e, "Projection sink %s failed to report its name", p.getClass().getName());
            return null;
        }
    }

    private record Named(ProjectionSink sink, String name) {}
}
