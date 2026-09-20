package io.lumnik.common.security;

import java.util.Optional;
import java.util.Set;

/**
 * rag's query-shaped view of {@link ScopeGrants}: not a second grant primitive, a wrapper that
 * turns a request's {@code scope} field into a decision. Grant = Keycloak realm role
 * {@code scope:<tag>} (flows JWT groups → {@link CurrentRolesHolder} via the hub's auth filter).
 *
 * Opt-in semantics (ratified 2026-07-07): a user with ZERO {@code scope:*} roles is
 * unscoped — tenant-wide access, behavior unchanged (no admin special-case needed);
 * a user with at least one is scope-bound — only the granted scopes may be queried,
 * and {@code all}/blank is rewritten to the granted set instead of unfiltered.
 *
 * <p>Public, and living beside {@link ScopeGrants}, so {@code ScopeBoundaryInventoryTest} can
 * reflect over its {@link ScopeDecision}-annotated methods the same way it reflects over
 * {@code ScopeGrants}'s — a package-private class in another module is invisible to
 * {@code Class.getMethods()}, which would otherwise force that guard back onto exactly the
 * hand-kept list its javadoc warns against (#508).
 */
public final class ScopeAccess {

    private static final String ALL = "all";

    private ScopeAccess() {}

    /** Query decision: {@code allowed=false} → 403; empty tags = no tag restriction. */
    public record Decision(boolean allowed, Set<String> tags) {
        static Decision forbid() { return new Decision(false, Set.of()); }
        static Decision allow(Set<String> tags) { return new Decision(true, tags); }
    }

    /**
     * Client input, once: strip whitespace; blank and {@code all} collapse to null =
     * "no specific scope named". What that MEANS is the caller's semantics: unrestricted
     * for {@link #forQuery} (rewritten onto the granted set when bound), but NOT allowed
     * for a bound user on {@link #mayAsk} — an ask must name one scope.
     */
    public static String normalized(String requestedScope) {
        if (requestedScope == null) return null;
        String s = requestedScope.strip();
        return s.isEmpty() || ALL.equals(s) ? null : s;
    }

    /** Empty = unscoped user (tenant-wide); present = the bound set, prefix stripped. */
    @ScopeDecision
    public static Optional<Set<String>> granted() {
        return ScopeGrants.granted();
    }

    /** Search/chat path: forbid outside the granted set; rewrite {@code all}/blank onto it. */
    @ScopeDecision
    public static Decision forQuery(String requestedScope) {
        String scope = normalized(requestedScope);
        var granted = granted();
        if (granted.isEmpty()) {
            return Decision.allow(scope == null ? Set.of() : Set.of(scope));
        }
        if (scope == null) return Decision.allow(granted.get());
        return granted.get().contains(scope)
            ? Decision.allow(Set.of(scope))
            : Decision.forbid();
    }

    /** Analytic ask: a bound user must NAME a granted scope — no fall-through to 'system'. */
    @ScopeDecision
    public static boolean mayAsk(String requestedScope) {
        String scope = normalized(requestedScope);
        var granted = granted();
        if (granted.isEmpty()) return true;
        return scope != null && granted.get().contains(scope);
    }
}
